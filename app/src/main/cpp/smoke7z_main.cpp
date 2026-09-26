// 独立冒烟程序：在设备上直接驱动 7z C 引擎解加密包，定位 Kotlin 层"native 不支持加密"
// 的真实失败点。用法: smoke7z <archive.7z> <password>。验证完成后此文件删除。
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <vector>
#include <string>

extern "C" {
#include "7z.h"
#include "7zAlloc.h"
#include "7zBuf.h"
#include "7zCrc.h"
#include "7zFile.h"
#include "7zTypes.h"
}

#define kInputBufSize ((size_t)2 << 20)

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

struct SmokeArchive {
    CSzArEx db;
    CFileInStream archiveStream;
    CLookToRead2 lookStream;
    UInt32 blockIndex;
    Byte *outBuffer;
    size_t outBufferSize;
    CSzFolderIncrementalDecoder *incDecoder;
    std::mutex mutex;
    std::vector<uint8_t> passwordBytes;

    SmokeArchive() : blockIndex(0xFFFFFFFF), outBuffer(nullptr), outBufferSize(0), incDecoder(nullptr) {
        SzArEx_Init(&db);
        FileInStream_CreateVTable(&archiveStream);
        archiveStream.wres = 0;
        File_Construct(&archiveStream.file);
        LookToRead2_CreateVTable(&lookStream, False);
        lookStream.buf = nullptr;
        incDecoder = SzFolderDecoder_Create(&g_Alloc);
    }

    void setPassword(const uint8_t *pwd, size_t len) {
        if (pwd && len > 0) {
            passwordBytes.assign(pwd, pwd + len);
            db.db.passwordBytes = passwordBytes.data();
            db.db.passwordLen = passwordBytes.size();
        } else {
            passwordBytes.clear();
            db.db.passwordBytes = nullptr;
            db.db.passwordLen = 0;
        }
    }

    ~SmokeArchive() {
        if (incDecoder) SzFolderDecoder_Destroy(incDecoder, &g_Alloc);
        if (outBuffer) ISzAlloc_Free(&g_Alloc, outBuffer);
        SzArEx_Free(&db, &g_Alloc);
        if (lookStream.buf) ISzAlloc_Free(&g_Alloc, lookStream.buf);
        File_Close(&archiveStream.file);
    }
};

static const char *sresName(SRes res) {
    switch (res) {
        case SZ_OK: return "SZ_OK";
        case SZ_ERROR_DATA: return "SZ_ERROR_DATA";
        case SZ_ERROR_MEM: return "SZ_ERROR_MEM";
        case SZ_ERROR_CRC: return "SZ_ERROR_CRC";
        case SZ_ERROR_UNSUPPORTED: return "SZ_ERROR_UNSUPPORTED";
        case SZ_ERROR_PARAM: return "SZ_ERROR_PARAM";
        case SZ_ERROR_INPUT_EOF: return "SZ_ERROR_INPUT_EOF";
        case SZ_ERROR_OUTPUT_EOF: return "SZ_ERROR_OUTPUT_EOF";
        case SZ_ERROR_READ: return "SZ_ERROR_READ";
        case SZ_ERROR_WRITE: return "SZ_ERROR_WRITE";
        case SZ_ERROR_THREAD: return "SZ_ERROR_THREAD";
        case SZ_ERROR_ARCHIVE: return "SZ_ERROR_ARCHIVE";
        case SZ_ERROR_NO_ARCHIVE: return "SZ_ERROR_NO_ARCHIVE";
        default: return "SZ_ERROR_?";
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        printf("usage: smoke7z <archive.7z> <password>\n");
        return 2;
    }
    const char *path = argv[1];
    const char *password = argv[2];

    auto *archive = new SmokeArchive();
    WRes wres = InFile_Open(&archive->archiveStream.file, path);
    if (wres != 0) {
        printf("[smoke] FAIL InFile_Open wres=%d errno=%d\n", wres, errno);
        return 1;
    }

    archive->lookStream.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, kInputBufSize);
    archive->lookStream.bufSize = kInputBufSize;
    archive->lookStream.realStream = &archive->archiveStream.vt;
    LookToRead2_INIT(&archive->lookStream);
    CrcGenerateTable();

    // 7z 规范：密码按 UTF-16-LE 字节参与 KDF（与 JNI extractPasswordBytes 一致）
    std::vector<uint8_t> pwdBytes;
    for (const char *p = password; *p; ++p) {
        uint16_t u = (uint8_t)*p; // ASCII 密码逐字节扩展即可
        pwdBytes.push_back((uint8_t)(u & 0xFF));
        pwdBytes.push_back((uint8_t)(u >> 8));
    }
    archive->setPassword(pwdBytes.data(), pwdBytes.size());
    printf("[smoke] password set: %zu bytes (UTF-16-LE)\n", pwdBytes.size());

    SRes res = SzArEx_Open(&archive->db, &archive->lookStream.vt, &g_Alloc, &g_Alloc);
    printf("[smoke] SzArEx_Open -> %s (%d), numFiles=%u\n", sresName(res), res, archive->db.NumFiles);
    if (res != SZ_OK) {
        delete archive;
        return 1;
    }

    // 找前两个非目录条目
    UInt32 targets[2];
    int found = 0;
    for (UInt32 i = 0; i < archive->db.NumFiles && found < 2; i++) {
        if (!SzArEx_IsDir(&archive->db, i)) {
            UInt64 size = SzArEx_GetFileSize(&archive->db, i);
            if (size > 0) {
                targets[found++] = i;
                UInt16 name[256];
                size_t nameLen = SzArEx_GetFileNameUtf16(&archive->db, i, name);
                printf("[smoke] entry[%u] size=%llu nameLen=%zu\n", i, (unsigned long long)size, nameLen);
            }
        }
    }
    if (found == 0) {
        printf("[smoke] no file entries\n");
        delete archive;
        return 1;
    }

    for (int t = 0; t < found; t++) {
        size_t offset = 0, outSize = 0;
        std::lock_guard<std::mutex> lock(archive->mutex);
        SRes er = SzArEx_ExtractIncremental(
            &archive->db, &archive->lookStream.vt, targets[t],
            &archive->blockIndex, &archive->outBuffer, &archive->outBufferSize,
            &offset, &outSize, archive->incDecoder, &g_Alloc, &g_Alloc);
        printf("[smoke] extract[%u] -> %s (%d), outSize=%zu\n", targets[t], sresName(er), er, outSize);
        if (er == SZ_OK && outSize >= 4) {
            const uint8_t *d = archive->outBuffer + offset;
            printf("[smoke] head bytes: %02X %02X %02X %02X (jpeg=FFD8FF, png=89504E47)\n",
                   d[0], d[1], d[2], d[3]);
        }
        if (er != SZ_OK) break;
    }

    delete archive;
    printf("[smoke] done\n");
    return 0;
}
