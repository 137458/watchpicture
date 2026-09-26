cmake_minimum_required(VERSION 3.22.1)

project("native7z" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Performance definitions for 7-Zip C LZMA SDK
add_definitions(
    -DZ7_PPMD_SUPPORT
    -DZ7_EXTRACT_ONLY
    -D_REENTRANT
    -D_FILE_OFFSET_BITS=64
    -D_LARGEFILE_SOURCE
)

add_compile_options(-O3 -DNDEBUG -funroll-loops)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/7z)

set(7Z_C_SOURCES
    7z/7zAlloc.c
    7z/7zArcIn.c
    7z/7zBuf.c
    7z/7zBuf2.c
    7z/7zCrc.c
    7z/7zCrcOpt.c
    7z/7zDec.c
    7z/7zFile.c
    7z/7zStream.c
    7z/aes256_cbc.c
    7z/Bcj2.c
    7z/Bra.c
    7z/Bra86.c
    7z/BraIA64.c
    7z/CpuArch.c
    7z/Delta.c
    7z/LzmaDec.c
    7z/Lzma2Dec.c
    7z/Ppmd7.c
    7z/Ppmd7Dec.c
)

add_library(
    native7z
    SHARED
    ${7Z_C_SOURCES}
    sha256_kdf.c
    native7z_jni.cpp
)

find_library(log-lib log)

target_link_libraries(
    native7z
    ${log-lib}
)

# ---------------------------------------------------------------------------
# nativezip: self-built native ZIP engine (research item Z3)
# Whole-file read-only mmap container parsing + STORED zero-copy slices +
# whole-buffer DEFLATE inflate. Primary inflate backend is the vendored
# libdeflate (MIT, app/src/main/cpp/zip_engine); when absent the engine
# falls back to the NDK system zlib (raw deflate, inflateInit2(-MAX_WBITS)).
# ---------------------------------------------------------------------------
set(NATIVEZIP_SOURCES nativezip_jni.cpp)
set(NATIVEZIP_HAS_LIBDEFLATE OFF)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/zip_engine/lib/deflate_decompress.c")
    set(NATIVEZIP_HAS_LIBDEFLATE ON)
    list(APPEND NATIVEZIP_SOURCES
        zip_engine/lib/deflate_decompress.c
        zip_engine/lib/utils.c
    )
endif()

add_library(
    nativezip
    SHARED
    ${NATIVEZIP_SOURCES}
)

if(NATIVEZIP_HAS_LIBDEFLATE)
    target_compile_definitions(nativezip PRIVATE NATIVEZIP_USE_LIBDEFLATE)
    target_include_directories(nativezip PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/zip_engine
        ${CMAKE_CURRENT_SOURCE_DIR}/zip_engine/lib
    )
endif()

target_link_libraries(
    nativezip
    ${log-lib}
)

if(NOT NATIVEZIP_HAS_LIBDEFLATE)
    # zlib fallback backend (raw deflate streaming inflate)
    target_link_libraries(
        nativezip
        z
    )
endif()
