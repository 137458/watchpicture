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

#define kInputBufSize ((size_t)1 << 18) // 256 KB stream buffer

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

struct Native7zArchive {
    CSzArEx db;
    CFileInStream archiveStream;
    CLookToRead2 lookStream;
    UInt32 blockIndex;
    Byte *outBuffer;
    size_t outBufferSize;
    std::mutex mutex;

    Native7zArchive() : blockIndex(0xFFFFFFFF), outBuffer(nullptr), outBufferSize(0) {
        SzArEx_Init(&db);
        FileInStream_CreateVTable(&archiveStream);
        archiveStream.wres = 0;
        File_Construct(&archiveStream.file);
        LookToRead2_CreateVTable(&lookStream, False);
        lookStream.buf = nullptr;
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
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) {
        env->ThrowNew(cls, message);
    }
}

static void throwUnsupportedException(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/UnsupportedOperationException");
    if (cls) {
        env->ThrowNew(cls, message);
    }
}

static jlong initArchiveStream(JNIEnv *env, Native7zArchive *archive) {
    archive->lookStream.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, kInputBufSize);
    if (!archive->lookStream.buf) {
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

    return reinterpret_cast<jlong>(archive);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeOpen(
    JNIEnv *env,
    jclass /* clazz */,
    jstring path
) {
    if (!path) {
        throwIOException(env, "Path cannot be null");
        return 0;
    }

    const char *cPath = env->GetStringUTFChars(path, nullptr);
    if (!cPath) {
        throwIOException(env, "Failed to get path string");
        return 0;
    }

    auto *archive = new Native7zArchive();
    WRes wres = InFile_Open(&archive->archiveStream.file, cPath);
    env->ReleaseStringUTFChars(path, cPath);

    if (wres != 0) {
        delete archive;
        char buf[64];
        snprintf(buf, sizeof(buf), "Cannot open archive file (wres %d)", wres);
        throwIOException(env, buf);
        return 0;
    }

    return initArchiveStream(env, archive);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_Native7z_nativeOpenFd(
    JNIEnv *env,
    jclass /* clazz */,
    jint fd
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
        throwIOException(env, "Native7zEntry class not found");
        return nullptr;
    }

    jmethodID entryCtor = env->GetMethodID(entryClass, "<init>", "(ILjava/lang/String;JZJ)V");
    if (!entryCtor) {
        throwIOException(env, "Native7zEntry constructor not found");
        return nullptr;
    }

    UInt32 numFiles = archive->db.NumFiles;
    jobjectArray array = env->NewObjectArray((jsize)numFiles, entryClass, nullptr);
    if (!array) {
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
