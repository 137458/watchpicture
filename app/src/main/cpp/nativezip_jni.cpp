// nativezip_jni.cpp - Self-built native ZIP engine (research item Z3).
//
// Whole-file read-only mmap container parsing (EOCD / ZIP64 EOCD locator /
// ZIP64 EOCD / central directory incl. 0x0001 extra field) + whole-buffer
// DEFLATE inflate. STORED entries are delivered as zero-copy read-only
// direct ByteBuffers sliced straight out of the mapping.
//
// Inflate backend:
//   - NATIVEZIP_USE_LIBDEFLATE: vendored libdeflate (MIT), whole-buffer API.
//   - otherwise: NDK system zlib, raw-deflate streaming (inflateInit2(-MAX_WBITS)).
// The backend is chosen by CMakeLists depending on whether zip_engine/ exists.
//
// Thread safety: mmap read-only sharing is inherently safe; every extraction
// call allocates its own decompressor / inflate stream and its own output
// buffer, so no decompression state is shared between threads.
//
// CRC32 is intentionally not verified (on-demand validation is allowed for
// the browsing scenario; see docs/research Z3).

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#if defined(NATIVEZIP_USE_LIBDEFLATE)
#include "libdeflate.h"
#else
#include <zlib.h>
#endif

#define LOG_TAG "NativeZip"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kSigEocd = 0x06054b50u;        // PK\x05\x06
constexpr uint32_t kSigZip64EocdLocator = 0x07064b50u; // PK\x06\x07
constexpr uint32_t kSigZip64Eocd = 0x06064b50u;   // PK\x06\x06
constexpr uint32_t kSigCentralDir = 0x02014b50u;  // PK\x01\x02
constexpr uint32_t kSigLocalHeader = 0x04034b50u; // PK\x03\x04

constexpr size_t kEocdMinSize = 22;
constexpr size_t kZip64LocatorSize = 20;
constexpr size_t kZip64EocdMinSize = 56;
constexpr size_t kCentralHeaderSize = 46;
constexpr size_t kLocalHeaderSize = 30;
constexpr uint64_t kMaxWriteChunk = 1u << 20; // 1 MB per write() call

constexpr uint16_t kMethodStored = 0;
constexpr uint16_t kMethodDeflate = 8;
constexpr uint16_t kFlagEncrypted = 0x0001;
constexpr uint16_t kExtraIdZip64 = 0x0001;

struct ZipEntry {
    std::string name;
    uint64_t localHeaderOffset = 0;
    uint64_t compressedSize = 0;
    uint64_t uncompressedSize = 0;
    uint16_t method = 0;
    bool isDirectory = false;
    bool encrypted = false;
};

struct NativeZipArchive {
    uint8_t *map = nullptr;
    size_t mapSize = 0;
    std::vector<ZipEntry> entries;

    // Deflate direct-buffer allocations handed out via NewDirectByteBuffer.
    // The JVM never frees JNI-created direct buffers, so the engine owns
    // them and reclaims everything when the handle is closed. Guarded by
    // ownedMutex; registration is the only cross-thread shared mutation,
    // decompression itself stays fully per-call.
    std::mutex ownedMutex;
    std::vector<void *> ownedBuffers;

    ~NativeZipArchive() {
        if (map && mapSize > 0) {
            munmap(map, mapSize);
        }
        for (void *p : ownedBuffers) {
            free(p);
        }
    }

    void registerOwnedBuffer(void *p) {
        std::lock_guard<std::mutex> lock(ownedMutex);
        ownedBuffers.push_back(p);
    }
};

// ------------------------------ little-endian readers (bounds checked) ------

inline bool inBounds(const uint8_t *base, size_t total, const uint8_t *pos, size_t need) {
    return pos >= base && need <= total && static_cast<size_t>(pos - base) <= total - need;
}

inline uint16_t readLe16(const uint8_t *p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

inline uint32_t readLe32(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

inline uint64_t readLe64(const uint8_t *p) {
    return static_cast<uint64_t>(readLe32(p)) | (static_cast<uint64_t>(readLe32(p + 4)) << 32);
}

inline uint16_t le16At(const uint8_t *base, size_t total, const uint8_t *pos, size_t offset) {
    return inBounds(base, total, pos + offset, 2) ? readLe16(pos + offset) : 0;
}

inline uint32_t le32At(const uint8_t *base, size_t total, const uint8_t *pos, size_t offset) {
    return inBounds(base, total, pos + offset, 4) ? readLe32(pos + offset) : 0;
}

inline uint64_t le64At(const uint8_t *base, size_t total, const uint8_t *pos, size_t offset) {
    return inBounds(base, total, pos + offset, 8) ? readLe64(pos + offset) : 0;
}

// ------------------------------ inflate backends ----------------------------

#if defined(NATIVEZIP_USE_LIBDEFLATE)

// libdeflate whole-buffer inflate: one decompressor per call, never shared.
bool inflateWhole(const uint8_t *in, size_t inNbytes, uint8_t *out, size_t outNbytes) {
    struct libdeflate_decompressor *d = libdeflate_alloc_decompressor();
    if (!d) {
        LOGE("inflateWhole: libdeflate_alloc_decompressor failed");
        return false;
    }
    enum libdeflate_result result =
        libdeflate_deflate_decompress(d, in, inNbytes, out, outNbytes, nullptr);
    libdeflate_free_decompressor(d);
    if (result != LIBDEFLATE_SUCCESS) {
        LOGE("inflateWhole: libdeflate_deflate_decompress failed with result %d", (int)result);
        return false;
    }
    return true;
}

#else // zlib fallback backend

// Raw-deflate streaming inflate over the system zlib. Input is fed in <=1GB
// chunks (zlib's avail_in/avail_out are 32-bit), output window likewise.
// A 1-byte scratch window keeps the empty-stream (0-byte output) case valid,
// because zlib requires non-zero avail_out to make progress.
bool inflateWhole(const uint8_t *in, size_t inNbytes, uint8_t *out, size_t outNbytes) {
    uint8_t scratch = 0;
    if (outNbytes == 0) {
        out = &scratch;
        outNbytes = 1;
    }

    z_stream strm{};
    if (inflateInit2(&strm, -MAX_WBITS) != Z_OK) {
        LOGE("inflateWhole: inflateInit2 failed");
        return false;
    }

    size_t inOff = 0;
    size_t outOff = 0;
    bool ok = false;
    int ret = Z_OK;

    while (true) {
        if (strm.avail_in == 0 && inOff < inNbytes) {
            size_t chunk = inNbytes - inOff;
            if (chunk > (static_cast<size_t>(1) << 30)) chunk = static_cast<size_t>(1) << 30;
            strm.next_in = const_cast<Bytef *>(in + inOff);
            strm.avail_in = static_cast<uInt>(chunk);
            inOff += chunk;
        }
        if (strm.avail_out == 0 && outOff < outNbytes) {
            size_t chunk = outNbytes - outOff;
            if (chunk > (static_cast<size_t>(1) << 30)) chunk = static_cast<size_t>(1) << 30;
            strm.next_out = out + outOff;
            strm.avail_out = static_cast<uInt>(chunk);
            outOff += chunk;
        }
        if (strm.avail_in == 0 && strm.avail_out == 0) {
            // Everything handed to zlib; only Z_STREAM_END can still be valid.
            if (ret != Z_OK) break;
        }

        ret = inflate(&strm, Z_FINISH);
        if (ret == Z_STREAM_END) {
            // Final block reached; trailing input beyond the stream (rare
            // writer padding) is tolerated, matching the libdeflate backend
            // semantics with actual_in_nbytes_ret == NULL.
            ok = true;
            break;
        }
        if (ret != Z_OK) {
            LOGE("inflateWhole: zlib inflate failed with %d (%s)", ret, strm.msg ? strm.msg : "no msg");
            break;
        }
        if (strm.avail_in == 0 && inOff >= inNbytes && strm.avail_out > 0) {
            // Truncated stream: input exhausted before Z_STREAM_END.
            break;
        }
    }

    inflateEnd(&strm);
    return ok;
}

#endif // NATIVEZIP_USE_LIBDEFLATE

// ------------------------------ container parsing ---------------------------

// Scans backwards from the end of file for the End Of Central Directory
// record, tolerating a trailing comment of up to 64KB.
const uint8_t *findEocd(const NativeZipArchive *a) {
    if (a->mapSize < kEocdMinSize) return nullptr;
    const uint8_t *base = a->map;
    const size_t total = a->mapSize;
    size_t maxBack = total - kEocdMinSize;
    const size_t kCommentMax = 65535;
    if (maxBack > kCommentMax) maxBack = kCommentMax;

    for (size_t back = 0; back <= maxBack; ++back) {
        const uint8_t *pos = base + (total - kEocdMinSize) - back;
        if (readLe32(pos) != kSigEocd) continue;
        uint16_t commentLen = readLe16(pos + 20);
        // EOCD record + comment must end exactly within the file: the
        // comment occupies the last commentLen bytes, i.e. commentLen <= back.
        // This rejects fake signatures inside a real trailing comment.
        if (commentLen <= back) {
            return pos;
        }
    }
    return nullptr;
}

// Resolves ZIP64 values via the locator + ZIP64 EOCD when present.
// Returns true when the central directory location is usable.
bool resolveCentralDirectory(NativeZipArchive *a, const uint8_t *eocd, uint64_t &cdOffset,
                             uint64_t &cdSize, uint64_t &entryCount) {
    const uint8_t *base = a->map;
    const size_t total = a->mapSize;
    const size_t eocdPos = static_cast<size_t>(eocd - base);

    uint32_t eocdDisk = le16At(base, total, eocd, 4);
    uint32_t eocdCdDisk = le16At(base, total, eocd, 6);
    entryCount = le16At(base, total, eocd, 10); // 16-bit total entry count
    uint64_t eocdCdSize = le32At(base, total, eocd, 12);
    uint64_t eocdCdOffset = le32At(base, total, eocd, 16);

    bool needsZip64 = (eocdDisk == 0xFFFF || eocdCdDisk == 0xFFFF ||
                       entryCount == 0xFFFF || eocdCdSize == 0xFFFFFFFFu ||
                       eocdCdOffset == 0xFFFFFFFFu);

    // Prefer the ZIP64 EOCD whenever its locator is present (some writers
    // emit it even while the 32-bit EOCD fields still fit). All-zero ZIP64
    // fields (a malformed writer artifact) fall back to the 32-bit values.
    if (eocdPos >= kZip64LocatorSize) {
        const uint8_t *locator = base + (eocdPos - kZip64LocatorSize);
        if (readLe32(locator) == kSigZip64EocdLocator) {
            uint64_t zip64EocdOffset = le64At(base, total, locator, 8);
            if (zip64EocdOffset < total &&
                inBounds(base, total, base + zip64EocdOffset, kZip64EocdMinSize) &&
                readLe32(base + zip64EocdOffset) == kSigZip64Eocd) {
                const uint8_t *z64 = base + zip64EocdOffset;
                const uint64_t z64Entries = le64At(base, total, z64, 32);
                const uint64_t z64CdSize = le64At(base, total, z64, 40);
                const uint64_t z64CdOffset = le64At(base, total, z64, 48);
                if (z64CdOffset != 0 || z64CdSize != 0) {
                    entryCount = z64Entries;
                    eocdCdSize = z64CdSize;
                    eocdCdOffset = z64CdOffset;
                    needsZip64 = false;
                }
            }
        }
    }

    if (needsZip64) {
        // ZIP64 markers required but the ZIP64 EOCD is missing/invalid.
        LOGE("resolveCentralDirectory: ZIP64 markers present but ZIP64 EOCD not found");
        return false;
    }

    cdOffset = eocdCdOffset;
    cdSize = eocdCdSize;
    return true;
}

// Applies the ZIP64 extra field (0x0001) of a central directory record.
// Per APPNOTE the 64-bit fields appear in order: uncompressed size,
// compressed size, local header offset; each present only when the matching
// 32-bit field holds its 0xFF.. escape value.
void applyZip64Extra(const uint8_t *base, size_t total, const uint8_t *extra, size_t extraLen,
                     uint64_t &uncompressedSize, uint64_t &compressedSize, uint64_t &lfhOffset) {
    size_t pos = 0;
    while (pos + 4 <= extraLen) {
        const uint8_t *field = extra + pos;
        if (!inBounds(base, total, field, 4)) return;
        uint16_t id = readLe16(field);
        uint16_t size = readLe16(field + 2);
        if (pos + 4 + size > extraLen) return;
        if (id == kExtraIdZip64) {
            const uint8_t *payload = field + 4;
            size_t cursor = 0;
            if (uncompressedSize == 0xFFFFFFFFu && cursor + 8 <= size) {
                uncompressedSize = readLe64(payload + cursor);
                cursor += 8;
            }
            if (compressedSize == 0xFFFFFFFFu && cursor + 8 <= size) {
                compressedSize = readLe64(payload + cursor);
                cursor += 8;
            }
            if (lfhOffset == 0xFFFFFFFFu && cursor + 8 <= size) {
                lfhOffset = readLe64(payload + cursor);
                cursor += 8;
            }
            return; // 0x0001 appears at most once per record
        }
        pos += 4 + size;
    }
}

// Parses the central directory into the entry table. Any inconsistency
// (bad signature, out-of-bounds record, ZIP64 mismatch) fails the open.
bool parseCentralDirectory(NativeZipArchive *a, uint64_t cdOffset, uint64_t cdSize,
                           uint64_t entryCount) {
    const uint8_t *base = a->map;
    const size_t total = a->mapSize;

    if (cdOffset > total || cdSize > total - cdOffset) {
        LOGE("parseCentralDirectory: CD range out of bounds (offset=%llu size=%llu file=%zu)",
             (unsigned long long)cdOffset, (unsigned long long)cdSize, total);
        return false;
    }
    if (entryCount == 0 || entryCount > 0x7FFFFFFEu) {
        LOGE("parseCentralDirectory: invalid entry count %llu", (unsigned long long)entryCount);
        return false;
    }

    a->entries.reserve(static_cast<size_t>(entryCount));
    const uint8_t *pos = base + cdOffset;
    const uint8_t *cdEnd = base + cdOffset + cdSize;

    for (uint64_t i = 0; i < entryCount; ++i) {
        if (pos + kCentralHeaderSize > cdEnd || !inBounds(base, total, pos, kCentralHeaderSize)) {
            LOGE("parseCentralDirectory: truncated at entry %llu", (unsigned long long)i);
            return false;
        }
        if (readLe32(pos) != kSigCentralDir) {
            LOGE("parseCentralDirectory: bad signature at entry %llu", (unsigned long long)i);
            return false;
        }

        uint16_t flags = readLe16(pos + 8);
        uint16_t method = readLe16(pos + 10);
        uint64_t compressedSize = readLe32(pos + 20);
        uint64_t uncompressedSize = readLe32(pos + 24);
        uint16_t nameLen = readLe16(pos + 28);
        uint16_t extraLen = readLe16(pos + 30);
        uint16_t commentLen = readLe16(pos + 32);
        uint64_t lfhOffset = readLe32(pos + 42);

        const uint8_t *name = pos + kCentralHeaderSize;
        if (nameLen > static_cast<size_t>(cdEnd - pos) - kCentralHeaderSize ||
            !inBounds(base, total, name, nameLen)) {
            LOGE("parseCentralDirectory: entry %llu name out of bounds", (unsigned long long)i);
            return false;
        }
        const uint8_t *extra = name + nameLen;
        if (extraLen > 0 &&
            (extraLen > static_cast<size_t>(cdEnd - pos) - kCentralHeaderSize - nameLen ||
             !inBounds(base, total, extra, extraLen))) {
            LOGE("parseCentralDirectory: entry %llu extra out of bounds", (unsigned long long)i);
            return false;
        }
        // The whole record (name + extra + comment) must stay inside the CD
        // so that the forward scan below can never step past cdEnd.
        if (static_cast<uint64_t>(kCentralHeaderSize) + nameLen + extraLen + commentLen >
            static_cast<uint64_t>(cdEnd - pos)) {
            LOGE("parseCentralDirectory: entry %llu record overruns CD", (unsigned long long)i);
            return false;
        }

        if (extraLen > 0) {
            applyZip64Extra(base, total, extra, extraLen, uncompressedSize, compressedSize,
                            lfhOffset);
        }

        ZipEntry entry;
        entry.name.assign(reinterpret_cast<const char *>(name), nameLen);
        entry.method = method;
        entry.compressedSize = compressedSize;
        entry.uncompressedSize = uncompressedSize;
        entry.localHeaderOffset = lfhOffset;
        entry.encrypted = (flags & kFlagEncrypted) != 0;
        entry.isDirectory = !entry.name.empty() &&
                            (entry.name.back() == '/' || entry.name.back() == '\\');
        a->entries.push_back(std::move(entry));

        pos += kCentralHeaderSize + nameLen + extraLen + commentLen;
    }

    return true;
}

// Opens a regular file, mmaps it read-only and closes the fd (the mapping
// stays valid after close). Returns false and fills errOut on failure.
bool mapFileForRead(const char *path, int existingFd, NativeZipArchive *archive, std::string &errOut) {
    int fd;
    if (existingFd >= 0) {
        fd = dup(existingFd);
        if (fd < 0) {
            errOut = "Failed to dup file descriptor";
            return false;
        }
    } else {
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            errOut = "Cannot open file";
            return false;
        }
    }

    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        errOut = "Not a regular file";
        return false;
    }
    const off_t fileSize = st.st_size;
    if (fileSize <= 0) {
        close(fd);
        errOut = "Empty file";
        return false;
    }

    void *mapped = mmap(nullptr, static_cast<size_t>(fileSize), PROT_READ, MAP_PRIVATE, fd, 0);
    int mmapErrno = errno;
    close(fd); // safe: the mapping remains valid independent of the fd
    if (mapped == MAP_FAILED) {
        LOGE("mapFileForRead: mmap failed, errno=%d (%s)", mmapErrno, strerror(mmapErrno));
        errOut = "mmap failed";
        return false;
    }

    archive->map = static_cast<uint8_t *>(mapped);
    archive->mapSize = static_cast<size_t>(fileSize);
    return true;
}

// Computes the compressed data offset of an entry from its local file header
// and validates that the payload lies inside the mapping.
bool resolveDataOffset(const NativeZipArchive *a, const ZipEntry &entry, const uint8_t **dataOut) {
    const uint8_t *base = a->map;
    const size_t total = a->mapSize;
    if (entry.localHeaderOffset >= total ||
        !inBounds(base, total, base + entry.localHeaderOffset, kLocalHeaderSize)) {
        return false;
    }
    const uint8_t *lfh = base + entry.localHeaderOffset;
    if (readLe32(lfh) != kSigLocalHeader) {
        LOGE("resolveDataOffset: bad local header signature for '%s'", entry.name.c_str());
        return false;
    }
    uint16_t nameLen = readLe16(lfh + 26);
    uint16_t extraLen = readLe16(lfh + 28);
    const uint64_t dataOffset =
        entry.localHeaderOffset + kLocalHeaderSize + nameLen + extraLen;
    if (dataOffset > total || entry.compressedSize > total - dataOffset) {
        LOGE("resolveDataOffset: payload out of bounds for '%s'", entry.name.c_str());
        return false;
    }
    *dataOut = base + dataOffset;
    return true;
}

bool writeAll(int fd, const uint8_t *src, uint64_t remaining) {
    while (remaining > 0) {
        size_t chunk = remaining > kMaxWriteChunk ? static_cast<size_t>(kMaxWriteChunk)
                                                  : static_cast<size_t>(remaining);
        ssize_t written = write(fd, src, chunk);
        if (written < 0) {
            if (errno == EINTR) continue;
            LOGE("writeAll: write failed, errno=%d (%s)", errno, strerror(errno));
            return false;
        }
        if (written == 0) {
            LOGE("writeAll: write returned 0");
            return false;
        }
        src += written;
        remaining -= static_cast<uint64_t>(written);
    }
    return true;
}

// Extracts entry payload into freshly allocated memory (malloc'd, caller
// frees) using the configured inflate backend. Returns nullptr on failure.
uint8_t *inflateEntryToHeap(const NativeZipArchive *a, const ZipEntry &entry) {
    const uint8_t *data = nullptr;
    if (!resolveDataOffset(a, entry, &data)) {
        return nullptr;
    }
    if (entry.uncompressedSize == 0) {
        uint8_t *empty = static_cast<uint8_t *>(malloc(1)); // non-null marker
        return empty;
    }
    uint8_t *out = static_cast<uint8_t *>(malloc(static_cast<size_t>(entry.uncompressedSize)));
    if (!out) {
        LOGE("inflateEntryToHeap: malloc(%llu) failed", (unsigned long long)entry.uncompressedSize);
        return nullptr;
    }
    if (!inflateWhole(data, static_cast<size_t>(entry.compressedSize), out,
                      static_cast<size_t>(entry.uncompressedSize))) {
        free(out);
        return nullptr;
    }
    return out;
}

// Validates that an entry can go through the extraction paths at all.
bool isExtractable(const NativeZipArchive *a, jint index, const ZipEntry *&entry) {
    if (!a || index < 0 || static_cast<size_t>(index) >= a->entries.size()) {
        return false;
    }
    entry = &a->entries[static_cast<size_t>(index)];
    if (entry->isDirectory || entry->encrypted ||
        (entry->method != kMethodStored && entry->method != kMethodDeflate)) {
        LOGE("isExtractable: entry %d not extractable (dir=%d encrypted=%d method=%u)",
             index, entry->isDirectory ? 1 : 0, entry->encrypted ? 1 : 0, entry->method);
        return false;
    }
    return true;
}

void throwIOException(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) {
        env->ThrowNew(cls, message);
    }
}

// Decodes a raw UTF-8 entry name into UTF-16 and wraps it with NewString.
// Malformed sequences are replaced with U+FFFD, matching the Java UTF-8
// decoder, so names stay comparable with charset-decoded Zip4j paths.
// (NewStringUTF is unsuitable: it expects modified UTF-8 and mangles
// 4-byte sequences, breaking findEntry for supplementary-plane names.)
jstring entryNameToJString(JNIEnv *env, const char *name, size_t len) {
    std::vector<jchar> utf16;
    utf16.reserve(len);
    size_t i = 0;
    while (i < len) {
        const uint8_t b0 = static_cast<uint8_t>(name[i++]);
        uint32_t cp;
        if (b0 < 0x80) {
            cp = b0;
        } else {
            size_t seq = 0;
            uint32_t minCp = 0;
            if ((b0 & 0xE0) == 0xC0) { seq = 2; minCp = 0x80; }
            else if ((b0 & 0xF0) == 0xE0) { seq = 3; minCp = 0x800; }
            else if ((b0 & 0xF8) == 0xF0) { seq = 4; minCp = 0x10000; }

            // seq-1 continuation bytes follow b0: indices i .. i+seq-2.
            bool ok = seq != 0 && (i + (seq - 1)) <= len;
            uint32_t acc = ok ? (b0 & (0xFFu >> (seq + 1))) : 0;
            for (size_t k = 1; ok && k < seq; ++k) {
                const uint8_t bk = static_cast<uint8_t>(name[i + k - 1]);
                if ((bk & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                acc = (acc << 6) | (bk & 0x3F);
            }
            if (ok && acc >= minCp && acc <= 0x10FFFF &&
                !(acc >= 0xD800 && acc <= 0xDFFF)) {
                cp = acc;
                i += seq - 1;
            } else {
                cp = 0xFFFD;
            }
        }
        if (cp < 0x10000) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    static const jchar kEmpty[1] = {0};
    return env->NewString(utf16.empty() ? kEmpty : utf16.data(),
                          static_cast<jsize>(utf16.size()));
}

} // namespace

// ------------------------------ JNI entry points ----------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeOpen(
    JNIEnv *env,
    jclass /* clazz */,
    jstring path
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
    auto *archive = new NativeZipArchive();
    std::string err;
    if (!mapFileForRead(cPath, -1, archive, err)) {
        LOGE("nativeOpen: mapFileForRead failed for '%s': %s", cPath, err.c_str());
        delete archive;
        env->ReleaseStringUTFChars(path, cPath);
        throwIOException(env, err.c_str());
        return 0;
    }
    env->ReleaseStringUTFChars(path, cPath);

    const uint8_t *eocd = findEocd(archive);
    if (!eocd) {
        LOGE("nativeOpen: EOCD not found in '%s'", cPath);
        delete archive;
        throwIOException(env, "End of central directory not found");
        return 0;
    }

    uint64_t cdOffset = 0;
    uint64_t cdSize = 0;
    uint64_t entryCount = 0;
    if (!resolveCentralDirectory(archive, eocd, cdOffset, cdSize, entryCount)) {
        LOGE("nativeOpen: failed to resolve central directory in '%s'", cPath);
        delete archive;
        throwIOException(env, "Failed to resolve central directory");
        return 0;
    }

    if (!parseCentralDirectory(archive, cdOffset, cdSize, entryCount)) {
        LOGE("nativeOpen: failed to parse central directory in '%s'", cPath);
        delete archive;
        throwIOException(env, "Failed to parse central directory");
        return 0;
    }

    LOGD("nativeOpen: '%s' mapped (%zu bytes), %zu entries", cPath, archive->mapSize,
         archive->entries.size());
    return reinterpret_cast<jlong>(archive);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeOpenFd(
    JNIEnv *env,
    jclass /* clazz */,
    jint fd
) {
    if (fd < 0) {
        throwIOException(env, "Invalid file descriptor");
        return 0;
    }

    LOGD("nativeOpenFd: opening fd %d", fd);
    auto *archive = new NativeZipArchive();
    std::string err;
    if (!mapFileForRead(nullptr, fd, archive, err)) {
        LOGE("nativeOpenFd: mapFileForRead failed for fd %d: %s", fd, err.c_str());
        delete archive;
        throwIOException(env, err.c_str());
        return 0;
    }

    const uint8_t *eocd = findEocd(archive);
    if (!eocd) {
        LOGE("nativeOpenFd: EOCD not found in fd %d", fd);
        delete archive;
        throwIOException(env, "End of central directory not found");
        return 0;
    }

    uint64_t cdOffset = 0;
    uint64_t cdSize = 0;
    uint64_t entryCount = 0;
    if (!resolveCentralDirectory(archive, eocd, cdOffset, cdSize, entryCount)) {
        LOGE("nativeOpenFd: failed to resolve central directory in fd %d", fd);
        delete archive;
        throwIOException(env, "Failed to resolve central directory");
        return 0;
    }

    if (!parseCentralDirectory(archive, cdOffset, cdSize, entryCount)) {
        LOGE("nativeOpenFd: failed to parse central directory in fd %d", fd);
        delete archive;
        throwIOException(env, "Failed to parse central directory");
        return 0;
    }

    LOGD("nativeOpenFd: fd %d mapped (%zu bytes), %zu entries", fd, archive->mapSize,
         archive->entries.size());
    return reinterpret_cast<jlong>(archive);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeGetEntries(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return nullptr;
    auto *archive = reinterpret_cast<NativeZipArchive *>(handle);
    if (archive->entries.size() > static_cast<size_t>(INT32_MAX)) {
        LOGE("nativeGetEntries: entry count exceeds INT32_MAX");
        return nullptr;
    }

    jclass entryClass = env->FindClass("com/watchpicture/app/archive/NativeZipEntry");
    if (!entryClass) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        throwIOException(env, "NativeZipEntry class not found");
        return nullptr;
    }

    // (index: Int, path: String, size: Long, compressedSize: Long, isDirectory: Boolean)
    jmethodID entryCtor = env->GetMethodID(entryClass, "<init>", "(ILjava/lang/String;JJZ)V");
    if (!entryCtor) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        throwIOException(env, "NativeZipEntry constructor not found");
        return nullptr;
    }

    const jsize count = static_cast<jsize>(archive->entries.size());
    LOGD("nativeGetEntries: count=%d", count);
    jobjectArray array = env->NewObjectArray(count, entryClass, nullptr);
    if (!array) {
        LOGE("nativeGetEntries: Failed to allocate object array for %d entries", count);
        return nullptr;
    }

    for (jsize i = 0; i < count; ++i) {
        const ZipEntry &entry = archive->entries[static_cast<size_t>(i)];
        jstring pathStr = entryNameToJString(env, entry.name.data(), entry.name.size());
        if (!pathStr) {
            return nullptr; // pending OutOfMemoryError
        }
        jobject entryObj = env->NewObject(
            entryClass,
            entryCtor,
            i,
            pathStr,
            static_cast<jlong>(entry.uncompressedSize),
            static_cast<jlong>(entry.compressedSize),
            static_cast<jboolean>(entry.isDirectory ? JNI_TRUE : JNI_FALSE)
        );
        env->SetObjectArrayElement(array, i, entryObj);
        env->DeleteLocalRef(entryObj);
        env->DeleteLocalRef(pathStr);
    }

    return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeExtractToFile(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex,
    jstring outPath
) {
    auto *archive = reinterpret_cast<NativeZipArchive *>(handle);
    const ZipEntry *entry = nullptr;
    if (!archive || !outPath || !isExtractable(archive, fileIndex, entry)) {
        return JNI_FALSE;
    }

    const char *cOutPath = env->GetStringUTFChars(outPath, nullptr);
    if (!cOutPath) {
        return JNI_FALSE;
    }

    int outFd = open(cOutPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (outFd < 0) {
        LOGE("nativeExtractToFile: failed to open output file, errno=%d (%s)", errno,
             strerror(errno));
        env->ReleaseStringUTFChars(outPath, cOutPath);
        return JNI_FALSE;
    }

    bool ok = true;
    if (entry->method == kMethodStored) {
        // Zero intermediate copy: stream straight out of the mapping.
        const uint8_t *data = nullptr;
        if (!resolveDataOffset(archive, *entry, &data)) {
            ok = false;
        } else {
            ok = writeAll(outFd, data, entry->uncompressedSize);
        }
    } else {
        uint8_t *inflated = inflateEntryToHeap(archive, *entry);
        if (!inflated) {
            ok = false;
        } else {
            ok = writeAll(outFd, inflated, entry->uncompressedSize);
            free(inflated);
        }
    }

    if (close(outFd) != 0) {
        LOGE("nativeExtractToFile: close failed, errno=%d", errno);
        ok = false;
    }
    if (!ok) {
        // Remove the partial output so callers never observe a broken file.
        unlink(cOutPath);
    }
    env->ReleaseStringUTFChars(outPath, cOutPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeExtractToDirectBuffer(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jint fileIndex
) {
    auto *archive = reinterpret_cast<NativeZipArchive *>(handle);
    const ZipEntry *entry = nullptr;
    if (!archive || !isExtractable(archive, fileIndex, entry)) {
        return nullptr;
    }

    if (entry->method == kMethodStored) {
        // Zero-copy read-only slice straight into the mapping. The mapping
        // lives as long as the handle, so the buffer stays valid until close.
        const uint8_t *data = nullptr;
        if (!resolveDataOffset(archive, *entry, &data)) {
            return nullptr;
        }
        return env->NewDirectByteBuffer(
            const_cast<uint8_t *>(data), static_cast<jlong>(entry->uncompressedSize));
    }

    // DEFLATE: decompress into engine-owned heap memory and wrap it in a
    // direct buffer. The JVM never frees JNI-created direct buffers, so the
    // allocation is registered on the handle and reclaimed at nativeClose.
    uint8_t *inflated = inflateEntryToHeap(archive, *entry);
    if (!inflated) {
        return nullptr;
    }
    archive->registerOwnedBuffer(inflated);
    return env->NewDirectByteBuffer(inflated, static_cast<jlong>(entry->uncompressedSize));
}

extern "C" JNIEXPORT void JNICALL
Java_com_watchpicture_app_archive_NativeZip_nativeClose(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jlong handle
) {
    if (!handle) return;
    auto *archive = reinterpret_cast<NativeZipArchive *>(handle);
    delete archive; // destructor: munmap + frees registered deflate buffers
}
