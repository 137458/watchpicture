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
    native7z_jni.cpp
)

find_library(log-lib log)

target_link_libraries(
    native7z
    ${log-lib}
)
