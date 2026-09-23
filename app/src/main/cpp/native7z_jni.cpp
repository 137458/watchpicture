#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
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

#define LOG_TAG "Native7z"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define kInputBufSize ((size_t)2 << 20) // 2 MB stream buffer for high-speed sequential read

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

struct Native7zArchive {
    CSzArEx db;
    CFileInStream archiveStream;
    CLookToRead2 lookStream;
    UInt32 blockIndex;
    Byte *outBuffer;
    size_t outBufferSize;
    std::mutex mutex;
    std::vector<uint8_t> passwordBytes;

    Native7zArchive() : blockIndex(0xFFFFFFFF), outBuffer(nullptr), outBufferSize(0) {
        SzArEx_Init(&db);
        FileInStream_CreateVTable(&archiveStream);
        archiveStream.wres = 0;
        File_Construct(&archiveStream.file);
        LookToRead2_CreateVTable(&lookStream, False);
        lookStream.buf = nullptr;
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

    ~Native7zArchive() {
        close();
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex);
        if (outBuffer) {
            ISzAlloc_Free(&g_Alloc, outBuffer);
            outBuffer = nullptr;
            outBufferSize = 0;
            blockIndex = 0xFFFFFFFF;
        }
        SzArEx_Free(&db, &g_Alloc);
        if (lookStream.buf) {
            ISzAlloc_Free(&g_Alloc, lookStream.buf);
            lookStream.buf = nullptr;
        }
        File_Close(&archiveStream.file);
    }

    void purgeBlockCache() {
        std::lock_guard<std::mutex> lock(mutex);
        if (outBuffer) {
            ISzAlloc_Free(&g_Alloc, outBuffer);
            outBuffer = nullptr;
            outBufferSize = 0;
            blockIndex = 0xFFFFFFFF;
        }
    }
};

static void throwIOException(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) {
        env->ThrowNew(cls, message);
    }
}

static void throwUnsupportedException(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    jclass cls = env->FindClass("java/lang/UnsupportedOperationException");
    if (cls) {
        env->ThrowNew(cls, message);
    }
}

static jlong initArchiveStream(JNIEnv *env, Native7zArchive *archive) {
    archive->lookStream.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, kInputBufSize);
    if (!archive->lookStream.buf) {
        LOGE("initArchiveStream: Failed to alloc input buffer of %zu bytes", kInputBufSize);
        delete archive;
        throwIOException(env, "Out of memory allocating 7z input buffer");
        return 0;
    }
    archive->lookStream.bufSize = kInputBufSize;
    archive->lookStream.realStream = &archive->archiveStream.vt;
    LookToRead2_INIT(&archive->lookStream);

    CrcGenerateTable();

    SRes res = SzArEx_Open(&archive->db, &archive->lookStream.vt, &g_Alloc, &g_Alloc);
    if (res != SZ_OK) {
        LOGE("initArchiveStream: SzArEx_Open failed with error code %d", res);
        delete archive;
        if (res == SZ_ERROR_UNSUPPORTED) {
            throwUnsupportedException(env, "7z archive contains unsupported compression method or encryption");
        } else if (res == SZ_ERROR_MEM) {
            throwIOException(env, "Out of memory during 7z archive header parsing");
        } else if (res == SZ_ERROR_CRC) {
            throwIOException(env, "7z archive header CRC check failed");
        } else {
            char buf[64];
            snprintf(buf, sizeof(buf), "Failed to open 7z archive (error code %d)", res);
            throwIOException(env, buf);
        }
        return 0;
    }

    LOGD("initArchiveStream: SzArEx_Open success, numFiles=%u", archive->db.NumFiles);
    return reinterpret_cast<jlong>(archive);
}

static void extractPasswordBytes(JNIEnv *env, jstring password, std::vector<uint8_t> &outBytes) {
    outBytes.clear();
    if (!password) return;
    jsize len = env->GetStringLength(password);
    if (len <= 0) return;
    const jchar *chars = env->GetStringChars(password, nullptr);
    if (!chars) return;
    outBytes.resize(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; i++) {
        outBytes[static_cast<size_t>(i) * 2] = static_cast<uint8_t>(chars[i] & 0xFF);
        outBytes[static_cast<size_t>(i) * 2 + 1] = static_cast<uint8_t>((chars[i] >> 8) & 0xFF);
    }
    env->ReleaseStringChars(password, chars);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeOpen(
    JNIEnv *env,
    jclass /* clazz */,
    jstring path,
    jstring password
) {
    if (!path) {
        LOGE("nativeOpen: path is null");
        throwIOException(env, "Path cannot be null");
        return 0;
    }

    const char *cPath = env->GetStringUTFChars(path, nullptr);
    if (!cPath) {
        LOGE("nativeOpen: Failed to get path string");
        throwIOException(env, "Failed to get path string");
        return 0;
    }

    LOGD("nativeOpen: opening '%s'", cPath);
    auto *archive = new Native7zArchive();
    WRes wres = InFile_Open(&archive->archiveStream.file, cPath);
    if (wres != 0) {
        LOGE("nativeOpen: InFile_Open failed for '%s', wres=%d, errno=%d (%s)", cPath, wres, errno, strerror(errno));
        delete archive;
        char buf[128];
        snprintf(buf, sizeof(buf), "Cannot open archive file (wres %d)", wres);
        env->ReleaseStringUTFChars(path, cPath);
        throwIOException(env, buf);
        return 0;
    }
    env->ReleaseStringUTFChars(path, cPath);

    std::vector<uint8_t> pwdBytes;
    extractPasswordBytes(env, password, pwdBytes);
    if (!pwdBytes.empty()) {
        LOGD("nativeOpen: setPassword with %zu bytes", pwdBytes.size());
        archive->setPassword(pwdBytes.data(), pwdBytes.size());
    } else {
        LOGD("nativeOpen: no password provided");
    }

    return initArchiveStream(env, archive);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeOpenFd(
    JNIEnv *env,
    jclass /* clazz */,
    jint fd,
    jstring password
) {
    if (fd < 0) {
        throwIOException(env, "Invalid file descriptor");
        return 0;
    }

    int dupFd = dup(fd);
    if (dupFd < 0) {
        throwIOException(env, "Failed to dup file descriptor");
        return 0;
    }

    auto *archive = new Native7zArchive();
    archive->archiveStream.file.fd = dupFd;

    std::vector<uint8_t> pwdBytes;
    extractPasswordBytes(env, password, pwdBytes);
    if (!pwdBytes.empty()) {
        archive->setPassword(pwdBytes.data(), pwdBytes.size());
    }

    return initArchiveStream(env, archive);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeGetNumFiles(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return 0;
    auto *archive = reinterpret_cast<Native7zArchive *>(handle);
    return static_cast<jint>(archive->db.NumFiles);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeGetEntries(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return nullptr;
    auto *archive = reinterpret_cast<Native7zArchive *>(handle);

    jclass entryClass = env->FindClass("com/watchpicture/app/archive/Native7zEntry");
    if (!entryClass) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        throwIOException(env, "Native7zEntry class not found");
        return nullptr;
    }

    jmethodID entryCtor = env->GetMethodID(entryClass, "<init>", "(ILjava/lang/String;JZJ)V");
    if (!entryCtor) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        throwIOException(env, "Native7zEntry constructor not found");
        return nullptr;
    }

    UInt32 numFiles = archive->db.NumFiles;
    LOGD("nativeGetEntries: numFiles=%u", numFiles);
    jobjectArray array = env->NewObjectArray((jsize)numFiles, entryClass, nullptr);
    if (!array) {
        LOGE("nativeGetEntries: Failed to allocate object array for %u entries", numFiles);
        return nullptr;
    }

    std::vector<UInt16> tempNameBuf(512);

    for (UInt32 i = 0; i < numFiles; ++i) {
        size_t len = SzArEx_GetFileNameUtf16(&archive->db, i, nullptr);
        if (len > tempNameBuf.size()) {
            tempNameBuf.resize(len);
        }
        SzArEx_GetFileNameUtf16(&archive->db, i, tempNameBuf.data());

        // len includes terminating null character
        jsize strLen = (len > 0) ? static_cast<jsize>(len - 1) : 0;
        jstring pathStr = env->NewString(reinterpret_cast<const jchar *>(tempNameBuf.data()), strLen);

        UInt64 fileSize = SzArEx_GetFileSize(&archive->db, i);
        BoolInt isDir = SzArEx_IsDir(&archive->db, i);
        jlong crc = SzBitWithVals_Check(&archive->db.CRCs, i) ? static_cast<jlong>(archive->db.CRCs.Vals[i] & 0xFFFFFFFFL) : 0L;

        jobject entryObj = env->NewObject(
            entryClass,
            entryCtor,
            static_cast<jint>(i),
            pathStr,
            static_cast<jlong>(fileSize),
            static_cast<jboolean>(isDir != 0),
            crc
        );

        env->SetObjectArrayElement(array, static_cast<jsize>(i), entryObj);

        env->DeleteLocalRef(entryObj);
        env->DeleteLocalRef(pathStr);
    }

    return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeExtractToFile(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex,
    jstring outPath
) {
    if (!handle || !outPath || fileIndex < 0) {
        return JNI_FALSE;
    }

    auto *archive = reinterpret_cast<Native7zArchive *>(handle);
    const char *cOutPath = env->GetStringUTFChars(outPath, nullptr);
    if (!cOutPath) {
        return JNI_FALSE;
    }

    size_t offset = 0;
    size_t outSizeProcessed = 0;

    std::lock_guard<std::mutex> lock(archive->mutex);
    SRes res = SzArEx_Extract(
        &archive->db,
        &archive->lookStream.vt,
        static_cast<UInt32>(fileIndex),
        &archive->blockIndex,
        &archive->outBuffer,
        &archive->outBufferSize,
        &offset,
        &outSizeProcessed,
        &g_Alloc,
        &g_Alloc
    );

    if (res != SZ_OK) {
        env->ReleaseStringUTFChars(outPath, cOutPath);
        LOGE("SzArEx_Extract failed for index %d with error %d", fileIndex, res);
        return JNI_FALSE;
    }

    LOGD("nativeExtractToFile: Extracted %zu bytes at offset %zu for index %d", outSizeProcessed, offset, fileIndex);

    int outFd = open(cOutPath, O_CREAT | O_WRONLY | O_TRUNC, 0666);
    env->ReleaseStringUTFChars(outPath, cOutPath);

    if (outFd < 0) {
        LOGE("Failed to open output file for write: errno %d", errno);
        return JNI_FALSE;
    }

    const Byte *src = archive->outBuffer + offset;
    size_t remaining = outSizeProcessed;
    while (remaining > 0) {
        ssize_t written = write(outFd, src, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;
            close(outFd);
            LOGE("Failed write to destination file: errno %d", errno);
            return JNI_FALSE;
        }
        src += written;
        remaining -= written;
    }

    close(outFd);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeExtractToFd(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex,
    jint outFd
) {
    if (!handle || outFd < 0 || fileIndex < 0) {
        return JNI_FALSE;
    }

    auto *archive = reinterpret_cast<Native7zArchive *>(handle);

    size_t offset = 0;
    size_t outSizeProcessed = 0;

    std::lock_guard<std::mutex> lock(archive->mutex);
    SRes res = SzArEx_Extract(
        &archive->db,
        &archive->lookStream.vt,
        static_cast<UInt32>(fileIndex),
        &archive->blockIndex,
        &archive->outBuffer,
        &archive->outBufferSize,
        &offset,
        &outSizeProcessed,
        &g_Alloc,
        &g_Alloc
    );

    if (res != SZ_OK) {
        LOGE("SzArEx_Extract failed for index %d with error %d", fileIndex, res);
        return JNI_FALSE;
    }

    const Byte *src = archive->outBuffer + offset;
    size_t remaining = outSizeProcessed;
    while (remaining > 0) {
        ssize_t written = write(outFd, src, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;
            LOGE("Failed write to fd: errno %d", errno);
            return JNI_FALSE;
        }
        src += written;
        remaining -= written;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeExtractToBytes(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex
) {
    if (!handle || fileIndex < 0) {
        return nullptr;
    }

    auto *archive = reinterpret_cast<Native7zArchive *>(handle);

    size_t offset = 0;
    size_t outSizeProcessed = 0;

    std::lock_guard<std::mutex> lock(archive->mutex);
    SRes res = SzArEx_Extract(
        &archive->db,
        &archive->lookStream.vt,
        static_cast<UInt32>(fileIndex),
        &archive->blockIndex,
        &archive->outBuffer,
        &archive->outBufferSize,
        &offset,
        &outSizeProcessed,
        &g_Alloc,
        &g_Alloc
    );

    if (res != SZ_OK) {
        LOGE("SzArEx_Extract failed for index %d with error %d", fileIndex, res);
        return nullptr;
    }

    jbyteArray byteArray = env->NewByteArray(static_cast<jsize>(outSizeProcessed));
    if (!byteArray) {
        return nullptr;
    }

    env->SetByteArrayRegion(
        byteArray,
        0,
        static_cast<jsize>(outSizeProcessed),
        reinterpret_cast<const jbyte *>(archive->outBuffer + offset)
    );

    return byteArray;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeExtractToDirectBuffer(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex
) {
    if (!handle || fileIndex < 0) {
        return nullptr;
    }

    auto *archive = reinterpret_cast<Native7zArchive *>(handle);

    size_t offset = 0;
    size_t outSizeProcessed = 0;

    std::lock_guard<std::mutex> lock(archive->mutex);
    SRes res = SzArEx_Extract(
        &archive->db,
        &archive->lookStream.vt,
        static_cast<UInt32>(fileIndex),
        &archive->blockIndex,
        &archive->outBuffer,
        &archive->outBufferSize,
        &offset,
        &outSizeProcessed,
        &g_Alloc,
        &g_Alloc
    );

    if (res != SZ_OK || !archive->outBuffer) {
        LOGE("SzArEx_Extract failed for index %d with error %d", fileIndex, res);
        return nullptr;
    }

    return env->NewDirectByteBuffer(archive->outBuffer + offset, static_cast<jlong>(outSizeProcessed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_watchpicture_app_archive_Native7z_nativePurgeBlockCache(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return;
    auto *archive = reinterpret_cast<Native7zArchive *>(handle);
    archive->purgeBlockCache();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeVerify(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex
) {
    if (!handle || fileIndex < 0) return JNI_FALSE;
    auto *archive = reinterpret_cast<Native7zArchive *>(handle);
    size_t offset = 0;
    size_t outSizeProcessed = 0;

    std::lock_guard<std::mutex> lock(archive->mutex);
    SRes res = SzArEx_Extract(
        &archive->db,
        &archive->lookStream.vt,
        static_cast<UInt32>(fileIndex),
        &archive->blockIndex,
        &archive->outBuffer,
        &archive->outBufferSize,
        &offset,
        &outSizeProcessed,
        &g_Alloc,
        &g_Alloc
    );
    return (res == SZ_OK) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeClose(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return;
    auto *archive = reinterpret_cast<Native7zArchive *>(handle);
    delete archive;
}

#include "sha256_kdf.h"

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeDeriveKey(
    JNIEnv *env,
    jclass /* clazz */,
    jbyteArray passwordBytes,
    jbyteArray saltBytes,
    jint numCyclesPower
) {
    if (!passwordBytes || !saltBytes) {
        return nullptr;
    }

    jsize pwdLen = env->GetArrayLength(passwordBytes);
    jsize saltLen = env->GetArrayLength(saltBytes);

    jbyte *pwdPtr = env->GetByteArrayElements(passwordBytes, nullptr);
    jbyte *saltPtr = env->GetByteArrayElements(saltBytes, nullptr);

    uint8_t outKey[32];
    sha256_7z_derive_key(
        reinterpret_cast<const uint8_t *>(pwdPtr),
        static_cast<size_t>(pwdLen),
        reinterpret_cast<const uint8_t *>(saltPtr),
        static_cast<size_t>(saltLen),
        numCyclesPower,
        outKey
    );

    env->ReleaseByteArrayElements(passwordBytes, pwdPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(saltBytes, saltPtr, JNI_ABORT);

    jbyteArray result = env->NewByteArray(32);
    if (!result) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte *>(outKey));
    return result;
}

