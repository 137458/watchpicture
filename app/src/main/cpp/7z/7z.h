/* 7z.h -- 7z interface
2023-04-02 : Igor Pavlov : Public domain */

#ifndef ZIP7_INC_7Z_H
#define ZIP7_INC_7Z_H

#include "7zTypes.h"

EXTERN_C_BEGIN

#define k7zStartHeaderSize 0x20
#define k7zSignatureSize 6

extern const Byte k7zSignature[k7zSignatureSize];

typedef struct
{
  const Byte *Data;
  size_t Size;
} CSzData;

/* CSzCoderInfo & CSzFolder support only default methods */

typedef struct
{
  size_t PropsOffset;
  UInt32 MethodID;
  Byte NumStreams;
  Byte PropsSize;
} CSzCoderInfo;

typedef struct
{
  UInt32 InIndex;
  UInt32 OutIndex;
} CSzBond;

#define SZ_NUM_CODERS_IN_FOLDER_MAX 4
#define SZ_NUM_BONDS_IN_FOLDER_MAX 3
#define SZ_NUM_PACK_STREAMS_IN_FOLDER_MAX 4

typedef struct
{
  UInt32 NumCoders;
  UInt32 NumBonds;
  UInt32 NumPackStreams;
  UInt32 UnpackStream;
  UInt32 PackStreams[SZ_NUM_PACK_STREAMS_IN_FOLDER_MAX];
  CSzBond Bonds[SZ_NUM_BONDS_IN_FOLDER_MAX];
  CSzCoderInfo Coders[SZ_NUM_CODERS_IN_FOLDER_MAX];
} CSzFolder;


SRes SzGetNextFolderItem(CSzFolder *f, CSzData *sd);

typedef struct
{
  UInt32 Low;
  UInt32 High;
} CNtfsFileTime;

typedef struct
{
  Byte *Defs; /* MSB 0 bit numbering */
  UInt32 *Vals;
} CSzBitUi32s;

typedef struct
{
  Byte *Defs; /* MSB 0 bit numbering */
  // UInt64 *Vals;
  CNtfsFileTime *Vals;
} CSzBitUi64s;

#define SzBitArray_Check(p, i) (((p)[(i) >> 3] & (0x80 >> ((i) & 7))) != 0)

#define SzBitWithVals_Check(p, i) ((p)->Defs && ((p)->Defs[(i) >> 3] & (0x80 >> ((i) & 7))) != 0)

typedef struct
{
  UInt32 NumPackStreams;
  UInt32 NumFolders;

  UInt64 *PackPositions;          // NumPackStreams + 1
  CSzBitUi32s FolderCRCs;         // NumFolders

  size_t *FoCodersOffsets;        // NumFolders + 1
  UInt32 *FoStartPackStreamIndex; // NumFolders + 1
  UInt32 *FoToCoderUnpackSizes;   // NumFolders + 1
  Byte *FoToMainUnpackSizeIndex;  // NumFolders
  UInt64 *CoderUnpackSizes;       // for all coders in all folders

  Byte *CodersData;

  UInt64 RangeLimit;

  const Byte *passwordBytes;
  size_t passwordLen;
  Byte cachedSalt[16];
  size_t cachedSaltLen;
  int cachedNumCyclesPower;
  Byte cachedKey[32];
  BoolInt isKeyValid;
  BoolInt isHeaderEncrypted;
} CSzAr;

UInt64 SzAr_GetFolderUnpackSize(const CSzAr *p, UInt32 folderIndex);

SRes SzAr_DecodeFolder(const CSzAr *p, UInt32 folderIndex,
    ILookInStreamPtr stream, UInt64 startPos,
    Byte *outBuffer, size_t outSize,
    ISzAllocPtr allocMain);

typedef struct CSzFolderIncrementalDecoder CSzFolderIncrementalDecoder;

CSzFolderIncrementalDecoder* SzFolderDecoder_Create(ISzAllocPtr allocMain);
void SzFolderDecoder_Destroy(CSzFolderIncrementalDecoder *dec, ISzAllocPtr allocMain);
void SzFolderDecoder_Reset(CSzFolderIncrementalDecoder *dec, ISzAllocPtr allocMain);

SRes SzAr_DecodeFolderUpTo(
    CSzFolderIncrementalDecoder *dec,
    const CSzAr *p,
    UInt32 folderIndex,
    ILookInStreamPtr inStream,
    UInt64 startPos,
    Byte *outBuffer,
    size_t totalUnpackSize,
    size_t targetUnpackSize,
    ISzAllocPtr allocMain,
    ISzAllocPtr allocTemp);

typedef struct
{
  CSzAr db;

  UInt64 startPosAfterHeader;
  UInt64 dataPos;
  
  UInt32 NumFiles;

  UInt64 *UnpackPositions;  // NumFiles + 1
  // Byte *IsEmptyFiles;
  Byte *IsDirs;
  CSzBitUi32s CRCs;

  CSzBitUi32s Attribs;
  // CSzBitUi32s Parents;
  CSzBitUi64s MTime;
  CSzBitUi64s CTime;

  UInt32 *FolderToFile;   // NumFolders + 1
  UInt32 *FileToFolder;   // NumFiles

  size_t *FileNameOffsets; /* in 2-byte steps */
  Byte *FileNames;  /* UTF-16-LE */
} CSzArEx;

#define SzArEx_IsDir(p, i) (SzBitArray_Check((p)->IsDirs, i))

#define SzArEx_GetFileSize(p, i) ((p)->UnpackPositions[(i) + 1] - (p)->UnpackPositions[i])

void SzArEx_Init(CSzArEx *p);
void SzArEx_Free(CSzArEx *p, ISzAllocPtr alloc);
UInt64 SzArEx_GetFolderStreamPos(const CSzArEx *p, UInt32 folderIndex, UInt32 indexInFolder);
int SzArEx_GetFolderFullPackSize(const CSzArEx *p, UInt32 folderIndex, UInt64 *resSize);

size_t SzArEx_GetFileNameUtf16(const CSzArEx *p, size_t fileIndex, UInt16 *dest);

SRes SzArEx_Extract(
    const CSzArEx *db,
    ILookInStreamPtr inStream,
    UInt32 fileIndex,         /* index of file */
    UInt32 *blockIndex,       /* index of solid block */
    Byte **outBuffer,         /* pointer to pointer to output buffer (allocated with allocMain) */
    size_t *outBufferSize,    /* buffer size for output buffer */
    size_t *offset,           /* offset of stream for required file in *outBuffer */
    size_t *outSizeProcessed, /* size of file in *outBuffer */
    ISzAllocPtr allocMain,
    ISzAllocPtr allocTemp);

SRes SzArEx_ExtractIncremental(
    const CSzArEx *db,
    ILookInStreamPtr inStream,
    UInt32 fileIndex,
    UInt32 *blockIndex,
    Byte **outBuffer,
    size_t *outBufferSize,
    size_t *offset,
    size_t *outSizeProcessed,
    CSzFolderIncrementalDecoder *incDec,
    ISzAllocPtr allocMain,
    ISzAllocPtr allocTemp);

SRes SzArEx_Open(CSzArEx *p, ILookInStreamPtr inStream,
    ISzAllocPtr allocMain, ISzAllocPtr allocTemp);

EXTERN_C_END

#endif
