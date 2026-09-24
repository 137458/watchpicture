/* 7zDec.c -- Decoding from 7z folder
: Igor Pavlov : Public domain */

#include "Precomp.h"

#include <string.h>

/* #define Z7_PPMD_SUPPORT */

#include "7z.h"
#include "7zCrc.h"

#include "Bcj2.h"
#include "Bra.h"
#include "CpuArch.h"
#include "Delta.h"
#include "LzmaDec.h"
#include "Lzma2Dec.h"
#ifdef Z7_PPMD_SUPPORT
#include "Ppmd7.h"
#endif

#include "aes256_cbc.h"
#include "../sha256_kdf.h"

#define k_Copy 0
#ifndef Z7_NO_METHOD_LZMA2
#define k_LZMA2 0x21
#endif
#define k_LZMA  0x30101
#define k_BCJ2  0x303011B
#define k_AES   0x06F10701

#if !defined(Z7_NO_METHODS_FILTERS)
#define Z7_USE_BRANCH_FILTER
#endif

#if !defined(Z7_NO_METHODS_FILTERS) || \
     defined(Z7_USE_NATIVE_BRANCH_FILTER) && defined(MY_CPU_ARM64)
#define Z7_USE_FILTER_ARM64
#ifndef Z7_USE_BRANCH_FILTER
#define Z7_USE_BRANCH_FILTER
#endif
#define k_ARM64 0xa
#endif

#if !defined(Z7_NO_METHODS_FILTERS) || \
     defined(Z7_USE_NATIVE_BRANCH_FILTER) && defined(MY_CPU_ARMT)
#define Z7_USE_FILTER_ARMT
#ifndef Z7_USE_BRANCH_FILTER
#define Z7_USE_BRANCH_FILTER
#endif
#define k_ARMT  0x3030701
#endif

#ifndef Z7_NO_METHODS_FILTERS
#define k_Delta 3
#define k_RISCV 0xb
#define k_BCJ   0x3030103
#define k_PPC   0x3030205
#define k_IA64  0x3030401
#define k_ARM   0x3030501
#define k_SPARC 0x3030805
#endif

#ifdef Z7_PPMD_SUPPORT

#define k_PPMD 0x30401

typedef struct
{
  IByteIn vt;
  const Byte *cur;
  const Byte *end;
  const Byte *begin;
  UInt64 processed;
  BoolInt extra;
  SRes res;
  ILookInStreamPtr inStream;
} CByteInToLook;

static Byte ReadByte(IByteInPtr pp)
{
  Z7_CONTAINER_FROM_VTBL_TO_DECL_VAR_pp_vt_p(CByteInToLook)
  if (p->cur != p->end)
    return *p->cur++;
  if (p->res == SZ_OK)
  {
    size_t size = (size_t)(p->cur - p->begin);
    p->processed += size;
    p->res = ILookInStream_Skip(p->inStream, size);
    size = (1 << 25);
    p->res = ILookInStream_Look(p->inStream, (const void **)&p->begin, &size);
    p->cur = p->begin;
    p->end = p->begin + size;
    if (size != 0)
      return *p->cur++;
  }
  p->extra = True;
  return 0;
}

static SRes SzDecodePpmd(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain, BoolInt allowPadding)
{
  CPpmd7 *ppmd;
  SRes res;
  unsigned order;
  UInt32 memSize;

  if (propsSize != 5)
    return SZ_ERROR_UNSUPPORTED;
  order = props[0];
  memSize = GetUi32(props + 1);
  if (order < PPMD7_MIN_ORDER ||
      order > PPMD7_MAX_ORDER ||
      memSize < PPMD7_MIN_MEM_SIZE ||
      memSize > PPMD7_MAX_MEM_SIZE)
    return SZ_ERROR_UNSUPPORTED;
  if ((ppmd = (CPpmd7 *)ISzAlloc_Alloc(allocMain, sizeof(CPpmd7))) == NULL)
    return SZ_ERROR_MEM;
  Ppmd7_Construct(ppmd);
  res = SZ_ERROR_MEM;
  if (Ppmd7_Alloc(ppmd, memSize, allocMain))
  {
    CByteInToLook s;
    s.vt.Read = ReadByte;
    s.inStream = inStream;
    s.begin = s.end = s.cur = NULL;
    s.extra = False;
    s.res = SZ_OK;
    s.processed = 0;

    Ppmd7_Init(ppmd, order);
    ppmd->rc.dec.Stream = &s.vt;
    res = SZ_ERROR_DATA;
    if (Ppmd7z_RangeDec_Init(&ppmd->rc.dec) && !s.extra)
    {
      Byte *buf = outBuffer;
      const Byte *lim = buf + outSize;
      for (; buf != lim; buf++)
      {
        int sym = Ppmd7z_DecodeSymbol(ppmd);
        if (s.extra || sym < 0)
          break;
        *buf = (Byte)sym;
      }
      if (buf == lim)
        if (Ppmd7z_RangeDec_IsFinishedOK(&ppmd->rc.dec)
            // || (Ppmd7z_DecodeSymbol(&ppmd) == PPMD7_SYM_END && Ppmd7z_RangeDec_IsFinishedOK(&ppmd.rc.dec))
            )
          res = SZ_OK;
    }
    if (s.extra)
      res = (s.res != SZ_OK ? s.res : SZ_ERROR_DATA);
    else if (allowPadding ? (inSize - (s.processed + (size_t)(s.cur - s.begin)) >= 16) : (s.processed + (size_t)(s.cur - s.begin) != inSize))
      res = SZ_ERROR_DATA;
    Ppmd7_Free(ppmd, allocMain);
  }
  ISzAlloc_Free(allocMain, ppmd);
  return res;
}

#endif


static SRes SzDecodeLzma(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain, BoolInt allowPadding)
{
  CLzmaDec state;
  SRes res = SZ_OK;

  LzmaDec_CONSTRUCT(&state)
  RINOK(LzmaDec_AllocateProbs(&state, props, propsSize, allocMain))
  state.dic = outBuffer;
  state.dicBufSize = outSize;
  LzmaDec_Init(&state);

  for (;;)
  {
    const void *inBuf = NULL;
    size_t lookahead = (1 << 18);
    if (lookahead > inSize)
      lookahead = (size_t)inSize;
    res = ILookInStream_Look(inStream, &inBuf, &lookahead);
    if (res != SZ_OK)
      break;

    {
      SizeT inProcessed = (SizeT)lookahead, dicPos = state.dicPos;
      ELzmaStatus status;
      res = LzmaDec_DecodeToDic(&state, outSize, (const Byte *)inBuf, &inProcessed, LZMA_FINISH_END, &status);
      lookahead -= inProcessed;
      inSize -= inProcessed;
      if (res != SZ_OK)
        break;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (outSize != state.dicPos || (allowPadding ? inSize >= 16 : inSize != 0))
          res = SZ_ERROR_DATA;
        break;
      }

      if (outSize == state.dicPos && (allowPadding ? inSize < 16 : inSize == 0) && status == LZMA_STATUS_MAYBE_FINISHED_WITHOUT_MARK)
        break;

      if (inProcessed == 0 && dicPos == state.dicPos)
      {
        res = SZ_ERROR_DATA;
        break;
      }

      res = ILookInStream_Skip(inStream, inProcessed);
      if (res != SZ_OK)
        break;
    }
  }

  LzmaDec_FreeProbs(&state, allocMain);
  return res;
}


#ifndef Z7_NO_METHOD_LZMA2

static SRes SzDecodeLzma2(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain, BoolInt allowPadding)
{
  CLzma2Dec state;
  SRes res = SZ_OK;

  Lzma2Dec_CONSTRUCT(&state)
  if (propsSize != 1)
    return SZ_ERROR_DATA;
  RINOK(Lzma2Dec_AllocateProbs(&state, props[0], allocMain))
  state.decoder.dic = outBuffer;
  state.decoder.dicBufSize = outSize;
  Lzma2Dec_Init(&state);

  for (;;)
  {
    const void *inBuf = NULL;
    size_t lookahead = (1 << 18);
    if (lookahead > inSize)
      lookahead = (size_t)inSize;
    res = ILookInStream_Look(inStream, &inBuf, &lookahead);
    if (res != SZ_OK)
      break;

    {
      SizeT inProcessed = (SizeT)lookahead, dicPos = state.decoder.dicPos;
      ELzmaStatus status;
      res = Lzma2Dec_DecodeToDic(&state, outSize, (const Byte *)inBuf, &inProcessed, LZMA_FINISH_END, &status);
      lookahead -= inProcessed;
      inSize -= inProcessed;
      if (res != SZ_OK)
        break;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (outSize != state.decoder.dicPos || (allowPadding ? inSize >= 16 : inSize != 0))
          res = SZ_ERROR_DATA;
        break;
      }

      if (outSize == state.decoder.dicPos && (allowPadding ? inSize < 16 : inSize == 0) && status == LZMA_STATUS_MAYBE_FINISHED_WITHOUT_MARK)
        break;

      if (inProcessed == 0 && dicPos == state.decoder.dicPos)
      {
        res = SZ_ERROR_DATA;
        break;
      }

      res = ILookInStream_Skip(inStream, inProcessed);
      if (res != SZ_OK)
        break;
    }
  }

  Lzma2Dec_FreeProbs(&state, allocMain);
  return res;
}

#endif


static SRes SzDecodeCopy(UInt64 inSize, ILookInStreamPtr inStream, Byte *outBuffer)
{
  while (inSize > 0)
  {
    const void *inBuf;
    size_t curSize = (1 << 18);
    if (curSize > inSize)
      curSize = (size_t)inSize;
    RINOK(ILookInStream_Look(inStream, &inBuf, &curSize))
    if (curSize == 0)
      return SZ_ERROR_INPUT_EOF;
    memcpy(outBuffer, inBuf, curSize);
    outBuffer += curSize;
    inSize -= curSize;
    RINOK(ILookInStream_Skip(inStream, curSize))
  }
  return SZ_OK;
}

static BoolInt IS_MAIN_METHOD(UInt32 m)
{
  switch (m)
  {
    case k_Copy:
    case k_LZMA:
  #ifndef Z7_NO_METHOD_LZMA2
    case k_LZMA2:
  #endif
  #ifdef Z7_PPMD_SUPPORT
    case k_PPMD:
  #endif
      return True;
    default:
      return False;
  }
}

static BoolInt IS_SUPPORTED_CODER(const CSzCoderInfo *c)
{
  return
      c->NumStreams == 1
      /* && c->MethodID <= (UInt32)0xFFFFFFFF */
      && IS_MAIN_METHOD((UInt32)c->MethodID);
}

#define IS_BCJ2(c) ((c)->MethodID == k_BCJ2 && (c)->NumStreams == 4)

#define kAesStreamBufSize (1 << 16) // 64 KB, 16-byte aligned

typedef struct
{
  ILookInStream vt;
  ILookInStreamPtr realStream;
  aes256_cbc_ctx aesCtx;
  UInt64 inSize;          // Total packed (encrypted) size
  UInt64 inProcessed;     // Bytes read from realStream so far
  size_t bufPos;          // Current offset in buf
  size_t bufSize;         // Number of decrypted bytes available in buf
  Byte buf[kAesStreamBufSize];
} CAesLookInStream;

static SRes AesLookInStream_Look(ILookInStreamPtr pp, const void **buf, size_t *size)
{
  CAesLookInStream *p = (CAesLookInStream *)(void *)pp;
  size_t avail = p->bufSize - p->bufPos;
  if (avail == 0)
  {
    p->bufPos = 0;
    p->bufSize = 0;
    if (p->inProcessed < p->inSize)
    {
      size_t toRead = kAesStreamBufSize;
      UInt64 rem = p->inSize - p->inProcessed;
      if (toRead > rem)
        toRead = (size_t)rem;

      if (toRead >= 16)
        toRead = (toRead / 16) * 16;

      if (toRead > 0)
      {
        RINOK(LookInStream_Read2(p->realStream, p->buf, toRead, SZ_ERROR_INPUT_EOF));
        aes256_cbc_decrypt(&p->aesCtx, p->buf, p->buf, toRead);
        p->bufSize = toRead;
        p->inProcessed += toRead;
        avail = toRead;
      }
    }
  }

  if (*size > avail)
    *size = avail;
  *buf = p->buf + p->bufPos;
  return SZ_OK;
}

static SRes AesLookInStream_Skip(ILookInStreamPtr pp, size_t offset)
{
  CAesLookInStream *p = (CAesLookInStream *)(void *)pp;
  if (p->bufPos + offset > p->bufSize)
    return SZ_ERROR_FAIL;
  p->bufPos += offset;
  return SZ_OK;
}

static SRes AesLookInStream_Read(ILookInStreamPtr pp, void *buf, size_t *size)
{
  return LookInStream_LookRead(pp, buf, size);
}

static SRes AesLookInStream_Seek(ILookInStreamPtr pp, Int64 *pos, ESzSeek origin)
{
  CAesLookInStream *p = (CAesLookInStream *)(void *)pp;
  if (origin == SZ_SEEK_CUR && *pos == 0)
  {
    *pos = (Int64)(p->inProcessed - (p->bufSize - p->bufPos));
    return SZ_OK;
  }
  return SZ_ERROR_UNSUPPORTED;
}

static void AesLookInStream_Init(CAesLookInStream *p, ILookInStreamPtr realStream, UInt64 inSize, const Byte key[32], const Byte iv[16])
{
  p->vt.Look = AesLookInStream_Look;
  p->vt.Skip = AesLookInStream_Skip;
  p->vt.Read = AesLookInStream_Read;
  p->vt.Seek = AesLookInStream_Seek;
  p->realStream = realStream;
  p->inSize = inSize;
  p->inProcessed = 0;
  p->bufPos = 0;
  p->bufSize = 0;
  aes256_cbc_init(&p->aesCtx, key, iv);
}

static int FindAesCoder(const CSzFolder *f)
{
  UInt32 i;
  for (i = 0; i < f->NumCoders; i++)
  {
    if (f->Coders[i].MethodID == k_AES)
      return (int)i;
  }
  return -1;
}

static SRes GetAesKeyAndIv(
    CSzAr *ar,
    const CSzCoderInfo *coder,
    const Byte *propsData,
    Byte key[32],
    Byte iv[16]
) {
  if (coder->PropsSize < 1)
    return SZ_ERROR_UNSUPPORTED;

  const Byte *props = propsData + coder->PropsOffset;
  Byte b0 = props[0];
  unsigned numCyclesPower = b0 & 0x3F;
  unsigned saltSize = 0;
  unsigned ivSize = 0;

  if (coder->PropsSize > 1)
  {
    Byte b1 = props[1];
    saltSize = ((b0 >> 7) & 1) + (b1 >> 4);
    ivSize = ((b0 >> 6) & 1) + (b1 & 0x0F);
    if (coder->PropsSize < 2 + saltSize + ivSize)
      return SZ_ERROR_UNSUPPORTED;
  }
  else
  {
    saltSize = (b0 >> 7) & 1;
    ivSize = (b0 >> 6) & 1;
    if (coder->PropsSize < 1 + saltSize + ivSize)
      return SZ_ERROR_UNSUPPORTED;
  }

  const Byte *salt = props + (coder->PropsSize > 1 ? 2 : 1);
  const Byte *ivData = salt + saltSize;

  memset(iv, 0, 16);
  if (ivSize > 16) ivSize = 16;
  if (ivSize > 0)
    memcpy(iv, ivData, ivSize);

  if (!ar || !ar->passwordBytes || ar->passwordLen == 0)
  {
    return SZ_ERROR_UNSUPPORTED;
  }

  // Check key cache
  if (ar->isKeyValid &&
      ar->cachedSaltLen == saltSize &&
      ar->cachedNumCyclesPower == (int)numCyclesPower &&
      (saltSize == 0 || memcmp(ar->cachedSalt, salt, saltSize) == 0))
  {
    memcpy(key, ar->cachedKey, 32);
    return SZ_OK;
  }

  sha256_7z_derive_key(
      ar->passwordBytes,
      ar->passwordLen,
      salt,
      saltSize,
      (int)numCyclesPower,
      key
  );

  if (saltSize <= 16)
  {
    if (saltSize > 0)
      memcpy(ar->cachedSalt, salt, saltSize);
    ar->cachedSaltLen = saltSize;
    ar->cachedNumCyclesPower = (int)numCyclesPower;
    memcpy(ar->cachedKey, key, 32);
    ar->isKeyValid = True;
  }

  return SZ_OK;
}

static SRes CheckSupportedFolder(const CSzFolder *f)
{
  int aesIndex = FindAesCoder(f);
  if (aesIndex >= 0)
  {
    const CSzCoderInfo *aesCoder = &f->Coders[aesIndex];
    if (aesCoder->NumStreams != 1)
      return SZ_ERROR_UNSUPPORTED;

    if (f->NumCoders == 1)
    {
      if (f->NumPackStreams != 1 || f->PackStreams[0] != 0 || f->NumBonds != 0)
        return SZ_ERROR_UNSUPPORTED;
      return SZ_OK;
    }

    if (f->NumCoders == 2)
    {
      int mainIndex = (aesIndex == 0) ? 1 : 0;
      const CSzCoderInfo *mainCoder = &f->Coders[mainIndex];
      if (!IS_SUPPORTED_CODER(mainCoder))
        return SZ_ERROR_UNSUPPORTED;
      if (f->NumPackStreams != 1 || f->NumBonds != 1)
        return SZ_ERROR_UNSUPPORTED;
      if (f->PackStreams[0] != (UInt32)aesIndex)
        return SZ_ERROR_UNSUPPORTED;
      if (f->Bonds[0].InIndex != (UInt32)mainIndex || f->Bonds[0].OutIndex != (UInt32)aesIndex)
        return SZ_ERROR_UNSUPPORTED;
      return SZ_OK;
    }

    #if defined(Z7_USE_BRANCH_FILTER)
    if (f->NumCoders == 3)
    {
      int mainIndex = -1;
      int filterIndex = -1;
      UInt32 i;
      for (i = 0; i < 3; i++)
      {
        if ((int)i == aesIndex) continue;
        if (IS_MAIN_METHOD((UInt32)f->Coders[i].MethodID))
          mainIndex = (int)i;
        else
          filterIndex = (int)i;
      }
      if (mainIndex < 0 || filterIndex < 0)
        return SZ_ERROR_UNSUPPORTED;
      if (f->Coders[mainIndex].NumStreams != 1 || f->Coders[filterIndex].NumStreams != 1)
        return SZ_ERROR_UNSUPPORTED;
      if (f->NumPackStreams != 1 || f->NumBonds != 2)
        return SZ_ERROR_UNSUPPORTED;
      if (f->PackStreams[0] != (UInt32)aesIndex)
        return SZ_ERROR_UNSUPPORTED;
      switch ((UInt32)f->Coders[filterIndex].MethodID)
      {
      #if !defined(Z7_NO_METHODS_FILTERS)
        case k_Delta:
        case k_BCJ:
        case k_PPC:
        case k_IA64:
        case k_SPARC:
        case k_ARM:
        case k_RISCV:
      #endif
      #ifdef Z7_USE_FILTER_ARM64
        case k_ARM64:
      #endif
      #ifdef Z7_USE_FILTER_ARMT
        case k_ARMT:
      #endif
          break;
        default:
          return SZ_ERROR_UNSUPPORTED;
      }
      return SZ_OK;
    }
    #endif

    return SZ_ERROR_UNSUPPORTED;
  }

  if (f->NumCoders < 1 || f->NumCoders > 4)
    return SZ_ERROR_UNSUPPORTED;
  if (!IS_SUPPORTED_CODER(&f->Coders[0]))
    return SZ_ERROR_UNSUPPORTED;
  if (f->NumCoders == 1)
  {
    if (f->NumPackStreams != 1 || f->PackStreams[0] != 0 || f->NumBonds != 0)
      return SZ_ERROR_UNSUPPORTED;
    return SZ_OK;
  }
  
  
  #if defined(Z7_USE_BRANCH_FILTER)

  if (f->NumCoders == 2)
  {
    const CSzCoderInfo *c = &f->Coders[1];
    if (
        /* c->MethodID > (UInt32)0xFFFFFFFF || */
        c->NumStreams != 1
        || f->NumPackStreams != 1
        || f->PackStreams[0] != 0
        || f->NumBonds != 1
        || f->Bonds[0].InIndex != 1
        || f->Bonds[0].OutIndex != 0)
      return SZ_ERROR_UNSUPPORTED;
    switch ((UInt32)c->MethodID)
    {
    #if !defined(Z7_NO_METHODS_FILTERS)
      case k_Delta:
      case k_BCJ:
      case k_PPC:
      case k_IA64:
      case k_SPARC:
      case k_ARM:
      case k_RISCV:
    #endif
    #ifdef Z7_USE_FILTER_ARM64
      case k_ARM64:
    #endif
    #ifdef Z7_USE_FILTER_ARMT
      case k_ARMT:
    #endif
        break;
      default:
        return SZ_ERROR_UNSUPPORTED;
    }
    return SZ_OK;
  }

  #endif

  
  if (f->NumCoders == 4)
  {
    if (!IS_SUPPORTED_CODER(&f->Coders[1])
        || !IS_SUPPORTED_CODER(&f->Coders[2])
        || !IS_BCJ2(&f->Coders[3]))
      return SZ_ERROR_UNSUPPORTED;
    if (f->NumPackStreams != 4
        || f->PackStreams[0] != 2
        || f->PackStreams[1] != 6
        || f->PackStreams[2] != 1
        || f->PackStreams[3] != 0
        || f->NumBonds != 3
        || f->Bonds[0].InIndex != 5 || f->Bonds[0].OutIndex != 0
        || f->Bonds[1].InIndex != 4 || f->Bonds[1].OutIndex != 1
        || f->Bonds[2].InIndex != 3 || f->Bonds[2].OutIndex != 2)
      return SZ_ERROR_UNSUPPORTED;
    return SZ_OK;
  }
  
  return SZ_ERROR_UNSUPPORTED;
}






static SRes SzFolder_Decode2(
    const CSzAr *p,
    const CSzFolder *folder,
    const Byte *propsData,
    const UInt64 *unpackSizes,
    const UInt64 *packPositions,
    ILookInStreamPtr inStream, UInt64 startPos,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain,
    Byte *tempBuf[])
{
  UInt32 ci;
  SizeT tempSizes[3] = { 0, 0, 0};
  SizeT tempSize3 = 0;
  Byte *tempBuf3 = 0;

  RINOK(CheckSupportedFolder(folder))

  {
    int aesIndex = FindAesCoder(folder);
    if (aesIndex >= 0)
    {
      const CSzCoderInfo *aesCoder = &folder->Coders[aesIndex];
      Byte key[32];
      Byte iv[16];

      RINOK(GetAesKeyAndIv((CSzAr *)p, aesCoder, propsData, key, iv))

      UInt32 si = 0;
      UInt64 offset = packPositions[si];
      UInt64 inSize = packPositions[(size_t)si + 1] - offset;
      RINOK(LookInStream_SeekTo(inStream, startPos + offset))

      CAesLookInStream *aesStream = (CAesLookInStream *)ISzAlloc_Alloc(allocMain, sizeof(CAesLookInStream));
      if (!aesStream)
        return SZ_ERROR_MEM;

      AesLookInStream_Init(aesStream, inStream, inSize, key, iv);

      SRes decodeRes = SZ_OK;

      if (folder->NumCoders == 1)
      {
        if (inSize < outSize)
          decodeRes = SZ_ERROR_DATA;
        else
          decodeRes = SzDecodeCopy(outSize, &aesStream->vt, outBuffer);
      }
      else
      {
        int mainIndex = -1;
        int filterIndex = -1;
        UInt32 i;
        for (i = 0; i < folder->NumCoders; i++)
        {
          if ((int)i == aesIndex) continue;
          if (IS_MAIN_METHOD((UInt32)folder->Coders[i].MethodID))
            mainIndex = (int)i;
          else
            filterIndex = (int)i;
        }

        if (mainIndex < 0)
        {
          ISzAlloc_Free(allocMain, aesStream);
          return SZ_ERROR_UNSUPPORTED;
        }

        const CSzCoderInfo *mainCoder = &folder->Coders[mainIndex];

        if (mainCoder->MethodID == k_Copy)
        {
          decodeRes = SzDecodeCopy(outSize, &aesStream->vt, outBuffer);
        }
        else if (mainCoder->MethodID == k_LZMA)
        {
          decodeRes = SzDecodeLzma(propsData + mainCoder->PropsOffset, mainCoder->PropsSize, inSize, &aesStream->vt, outBuffer, outSize, allocMain, True);
        }
      #ifndef Z7_NO_METHOD_LZMA2
        else if (mainCoder->MethodID == k_LZMA2)
        {
          decodeRes = SzDecodeLzma2(propsData + mainCoder->PropsOffset, mainCoder->PropsSize, inSize, &aesStream->vt, outBuffer, outSize, allocMain, True);
        }
      #endif
      #ifdef Z7_PPMD_SUPPORT
        else if (mainCoder->MethodID == k_PPMD)
        {
          decodeRes = SzDecodePpmd(propsData + mainCoder->PropsOffset, mainCoder->PropsSize, inSize, &aesStream->vt, outBuffer, outSize, allocMain, True);
        }
      #endif
        else
        {
          decodeRes = SZ_ERROR_UNSUPPORTED;
        }

        if (decodeRes == SZ_OK && filterIndex >= 0)
        {
          const CSzCoderInfo *fCoder = &folder->Coders[filterIndex];
          #if defined(Z7_USE_BRANCH_FILTER)
          #if !defined(Z7_NO_METHODS_FILTERS)
          if (fCoder->MethodID == k_Delta)
          {
            if (fCoder->PropsSize != 1)
              decodeRes = SZ_ERROR_UNSUPPORTED;
            else
            {
              Byte state[DELTA_STATE_SIZE];
              Delta_Init(state);
              Delta_Decode(state, (unsigned)(propsData[fCoder->PropsOffset]) + 1, outBuffer, outSize);
            }
          }
          #endif
          #ifdef Z7_USE_FILTER_ARM64
          else if (fCoder->MethodID == k_ARM64)
          {
            UInt32 pc = 0;
            if (fCoder->PropsSize == 4)
            {
              pc = GetUi32(propsData + fCoder->PropsOffset);
              if (pc & 3) decodeRes = SZ_ERROR_UNSUPPORTED;
            }
            else if (fCoder->PropsSize != 0) decodeRes = SZ_ERROR_UNSUPPORTED;
            if (decodeRes == SZ_OK)
              z7_BranchConv_ARM64_Dec(outBuffer, outSize, pc);
          }
          #endif
          #if !defined(Z7_NO_METHODS_FILTERS)
          else if (fCoder->MethodID == k_BCJ)
          {
            UInt32 state = Z7_BRANCH_CONV_ST_X86_STATE_INIT_VAL;
            z7_BranchConvSt_X86_Dec(outBuffer, outSize, 0, &state);
          }
          #endif
          #endif
        }
      }

      ISzAlloc_Free(allocMain, aesStream);
      return decodeRes;
    }
  }

  for (ci = 0; ci < folder->NumCoders; ci++)
  {
    const CSzCoderInfo *coder = &folder->Coders[ci];

    if (IS_MAIN_METHOD((UInt32)coder->MethodID))
    {
      UInt32 si = 0;
      UInt64 offset;
      UInt64 inSize;
      Byte *outBufCur = outBuffer;
      SizeT outSizeCur = outSize;
      if (folder->NumCoders == 4)
      {
        const UInt32 indices[] = { 3, 2, 0 };
        const UInt64 unpackSize = unpackSizes[ci];
        si = indices[ci];
        if (ci < 2)
        {
          Byte *temp;
          outSizeCur = (SizeT)unpackSize;
          if (outSizeCur != unpackSize)
            return SZ_ERROR_MEM;
          temp = (Byte *)ISzAlloc_Alloc(allocMain, outSizeCur);
          if (!temp && outSizeCur != 0)
            return SZ_ERROR_MEM;
          outBufCur = tempBuf[1 - ci] = temp;
          tempSizes[1 - ci] = outSizeCur;
        }
        else if (ci == 2)
        {
          if (unpackSize > outSize) /* check it */
            return SZ_ERROR_PARAM;
          tempBuf3 = outBufCur = outBuffer + (outSize - (size_t)unpackSize);
          tempSize3 = outSizeCur = (SizeT)unpackSize;
        }
        else
          return SZ_ERROR_UNSUPPORTED;
      }
      offset = packPositions[si];
      inSize = packPositions[(size_t)si + 1] - offset;
      RINOK(LookInStream_SeekTo(inStream, startPos + offset))

      if (coder->MethodID == k_Copy)
      {
        if (inSize != outSizeCur) /* check it */
          return SZ_ERROR_DATA;
        RINOK(SzDecodeCopy(inSize, inStream, outBufCur))
      }
      else if (coder->MethodID == k_LZMA)
      {
        RINOK(SzDecodeLzma(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain, False))
      }
    #ifndef Z7_NO_METHOD_LZMA2
      else if (coder->MethodID == k_LZMA2)
      {
        RINOK(SzDecodeLzma2(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain, False))
      }
    #endif
    #ifdef Z7_PPMD_SUPPORT
      else if (coder->MethodID == k_PPMD)
      {
        RINOK(SzDecodePpmd(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain, False))
      }
    #endif
      else
        return SZ_ERROR_UNSUPPORTED;
    }
    else if (coder->MethodID == k_BCJ2)
    {
      const UInt64 offset = packPositions[1];
      const UInt64 s3Size = packPositions[2] - offset;
      
      if (ci != 3)
        return SZ_ERROR_UNSUPPORTED;
      
      tempSizes[2] = (SizeT)s3Size;
      if (tempSizes[2] != s3Size)
        return SZ_ERROR_MEM;
      tempBuf[2] = (Byte *)ISzAlloc_Alloc(allocMain, tempSizes[2]);
      if (!tempBuf[2] && tempSizes[2] != 0)
        return SZ_ERROR_MEM;
      
      RINOK(LookInStream_SeekTo(inStream, startPos + offset))
      RINOK(SzDecodeCopy(s3Size, inStream, tempBuf[2]))

      if ((tempSizes[0] & 3) != 0 ||
          (tempSizes[1] & 3) != 0 ||
          tempSize3 + tempSizes[0] + tempSizes[1] != outSize)
        return SZ_ERROR_DATA;

      {
        CBcj2Dec p;
        
        p.bufs[0] = tempBuf3;   p.lims[0] = tempBuf3 + tempSize3;
        p.bufs[1] = tempBuf[0]; p.lims[1] = tempBuf[0] + tempSizes[0];
        p.bufs[2] = tempBuf[1]; p.lims[2] = tempBuf[1] + tempSizes[1];
        p.bufs[3] = tempBuf[2]; p.lims[3] = tempBuf[2] + tempSizes[2];
        
        p.dest = outBuffer;
        p.destLim = outBuffer + outSize;
        
        Bcj2Dec_Init(&p);
        RINOK(Bcj2Dec_Decode(&p))

        {
          unsigned i;
          for (i = 0; i < 4; i++)
            if (p.bufs[i] != p.lims[i])
              return SZ_ERROR_DATA;
          if (p.dest != p.destLim || !Bcj2Dec_IsMaybeFinished(&p))
            return SZ_ERROR_DATA;
        }
      }
    }
#if defined(Z7_USE_BRANCH_FILTER)
    else if (ci == 1)
    {
#if !defined(Z7_NO_METHODS_FILTERS)
      if (coder->MethodID == k_Delta)
      {
        if (coder->PropsSize != 1)
          return SZ_ERROR_UNSUPPORTED;
        {
          Byte state[DELTA_STATE_SIZE];
          Delta_Init(state);
          Delta_Decode(state, (unsigned)(propsData[coder->PropsOffset]) + 1, outBuffer, outSize);
        }
        continue;
      }
#endif
     
#ifdef Z7_USE_FILTER_ARM64
      if (coder->MethodID == k_ARM64)
      {
        UInt32 pc = 0;
        if (coder->PropsSize == 4)
        {
          pc = GetUi32(propsData + coder->PropsOffset);
          if (pc & 3)
            return SZ_ERROR_UNSUPPORTED;
        }
        else if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
        z7_BranchConv_ARM64_Dec(outBuffer, outSize, pc);
        continue;
      }
#endif

#if !defined(Z7_NO_METHODS_FILTERS)
      if (coder->MethodID == k_RISCV)
      {
        UInt32 pc = 0;
        if (coder->PropsSize == 4)
        {
          pc = GetUi32(propsData + coder->PropsOffset);
          if (pc & 1)
            return SZ_ERROR_UNSUPPORTED;
        }
        else if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
        z7_BranchConv_RISCV_Dec(outBuffer, outSize, pc);
        continue;
      }
#endif
      
#if !defined(Z7_NO_METHODS_FILTERS) || defined(Z7_USE_FILTER_ARMT)
      {
        if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
       #define CASE_BRA_CONV(isa) case k_ ## isa: Z7_BRANCH_CONV_DEC(isa)(outBuffer, outSize, 0); break; // pc = 0;
        switch (coder->MethodID)
        {
         #if !defined(Z7_NO_METHODS_FILTERS)
          case k_BCJ:
          {
            UInt32 state = Z7_BRANCH_CONV_ST_X86_STATE_INIT_VAL;
            z7_BranchConvSt_X86_Dec(outBuffer, outSize, 0, &state); // pc = 0
            break;
          }
          case k_PPC: Z7_BRANCH_CONV_DEC_2(BranchConv_PPC)(outBuffer, outSize, 0); break; // pc = 0;
          // CASE_BRA_CONV(PPC)
          CASE_BRA_CONV(IA64)
          CASE_BRA_CONV(SPARC)
          CASE_BRA_CONV(ARM)
         #endif
         #if !defined(Z7_NO_METHODS_FILTERS) || defined(Z7_USE_FILTER_ARMT)
          CASE_BRA_CONV(ARMT)
         #endif
          default:
            return SZ_ERROR_UNSUPPORTED;
        }
        continue;
      }
#endif
    } // (c == 1)
#endif // Z7_USE_BRANCH_FILTER
    else
      return SZ_ERROR_UNSUPPORTED;
  }

  return SZ_OK;
}


SRes SzAr_DecodeFolder(const CSzAr *p, UInt32 folderIndex,
    ILookInStreamPtr inStream, UInt64 startPos,
    Byte *outBuffer, size_t outSize,
    ISzAllocPtr allocMain)
{
  SRes res;
  CSzFolder folder;
  CSzData sd;
  
  const Byte *data = p->CodersData + p->FoCodersOffsets[folderIndex];
  sd.Data = data;
  sd.Size = p->FoCodersOffsets[(size_t)folderIndex + 1] - p->FoCodersOffsets[folderIndex];
  
  res = SzGetNextFolderItem(&folder, &sd);
  
  if (res != SZ_OK)
    return res;

  if (sd.Size != 0
      || folder.UnpackStream != p->FoToMainUnpackSizeIndex[folderIndex]
      || outSize != SzAr_GetFolderUnpackSize(p, folderIndex))
    return SZ_ERROR_FAIL;
  {
    unsigned i;
    Byte *tempBuf[3] = { 0, 0, 0};

    res = SzFolder_Decode2(p, &folder, data,
        &p->CoderUnpackSizes[p->FoToCoderUnpackSizes[folderIndex]],
        p->PackPositions + p->FoStartPackStreamIndex[folderIndex],
        inStream, startPos,
        outBuffer, (SizeT)outSize, allocMain, tempBuf);
    
    for (i = 0; i < 3; i++)
      ISzAlloc_Free(allocMain, tempBuf[i]);

    if (res == SZ_OK)
      if (SzBitWithVals_Check(&p->FolderCRCs, folderIndex))
        if (CrcCalc(outBuffer, outSize) != p->FolderCRCs.Vals[folderIndex])
          res = SZ_ERROR_CRC;

    return res;
  }
}

struct CSzFolderIncrementalDecoder
{
  BoolInt active;
  BoolInt crcChecked;
  UInt32 folderIndex;
  UInt32 mainMethod;
  BoolInt hasAes;
  CAesLookInStream *aesStream;
  ILookInStreamPtr activeStream;
  UInt64 remainingInSize;
  BoolInt allowPadding;
  BoolInt lzmaAllocated;
  BoolInt lzma2Allocated;
  CLzmaDec lzmaState;
  CLzma2Dec lzma2State;
  Byte *outBuffer;
  size_t totalUnpackSize;
  size_t decodedSize;
};

CSzFolderIncrementalDecoder* SzFolderDecoder_Create(ISzAllocPtr allocMain)
{
  CSzFolderIncrementalDecoder *dec = (CSzFolderIncrementalDecoder *)ISzAlloc_Alloc(allocMain, sizeof(CSzFolderIncrementalDecoder));
  if (!dec) return NULL;
  memset(dec, 0, sizeof(CSzFolderIncrementalDecoder));
  dec->folderIndex = (UInt32)-1;
  LzmaDec_CONSTRUCT(&dec->lzmaState);
  Lzma2Dec_CONSTRUCT(&dec->lzma2State);
  return dec;
}

void SzFolderDecoder_Reset(CSzFolderIncrementalDecoder *dec, ISzAllocPtr allocMain)
{
  if (!dec) return;
  if (dec->lzmaAllocated)
  {
    LzmaDec_FreeProbs(&dec->lzmaState, allocMain);
    dec->lzmaAllocated = False;
  }
  if (dec->lzma2Allocated)
  {
    Lzma2Dec_FreeProbs(&dec->lzma2State, allocMain);
    dec->lzma2Allocated = False;
  }
  if (dec->aesStream)
  {
    ISzAlloc_Free(allocMain, dec->aesStream);
    dec->aesStream = NULL;
  }
  dec->active = False;
  dec->crcChecked = False;
  dec->folderIndex = (UInt32)-1;
  dec->activeStream = NULL;
  dec->remainingInSize = 0;
  dec->outBuffer = NULL;
  dec->totalUnpackSize = 0;
  dec->decodedSize = 0;
}

void SzFolderDecoder_Destroy(CSzFolderIncrementalDecoder *dec, ISzAllocPtr allocMain)
{
  if (!dec) return;
  SzFolderDecoder_Reset(dec, allocMain);
  ISzAlloc_Free(allocMain, dec);
}

static SRes SzFolderDecoder_StepTo(CSzFolderIncrementalDecoder *dec, size_t targetSize)
{
  if (targetSize > dec->totalUnpackSize)
    targetSize = dec->totalUnpackSize;
  if (dec->decodedSize >= targetSize)
    return SZ_OK;

  if (dec->mainMethod == k_Copy)
  {
    while (dec->decodedSize < targetSize)
    {
      const void *inBuf = NULL;
      size_t curSize = targetSize - dec->decodedSize;
      if (curSize > (1 << 18))
        curSize = (1 << 18);
      if (curSize > dec->remainingInSize)
        curSize = (size_t)dec->remainingInSize;
      RINOK(ILookInStream_Look(dec->activeStream, &inBuf, &curSize));
      if (curSize == 0)
        return SZ_ERROR_INPUT_EOF;
      memcpy(dec->outBuffer + dec->decodedSize, inBuf, curSize);
      dec->decodedSize += curSize;
      dec->remainingInSize -= curSize;
      RINOK(ILookInStream_Skip(dec->activeStream, curSize));
    }
    return SZ_OK;
  }

  if (dec->mainMethod == k_LZMA)
  {
    ELzmaFinishMode finishMode = (targetSize == dec->totalUnpackSize) ? LZMA_FINISH_END : LZMA_FINISH_ANY;
    for (;;)
    {
      if (dec->lzmaState.dicPos >= targetSize)
      {
        dec->decodedSize = dec->lzmaState.dicPos;
        break;
      }
      const void *inBuf = NULL;
      size_t lookahead = (1 << 18);
      if (lookahead > dec->remainingInSize)
        lookahead = (size_t)dec->remainingInSize;
      SRes res = ILookInStream_Look(dec->activeStream, &inBuf, &lookahead);
      if (res != SZ_OK)
        return res;

      SizeT inProcessed = (SizeT)lookahead;
      SizeT dicPosBefore = dec->lzmaState.dicPos;
      ELzmaStatus status;
      res = LzmaDec_DecodeToDic(&dec->lzmaState, targetSize, (const Byte *)inBuf, &inProcessed, finishMode, &status);
      dec->remainingInSize -= inProcessed;
      if (res != SZ_OK)
        return res;

      res = ILookInStream_Skip(dec->activeStream, inProcessed);
      if (res != SZ_OK)
        return res;

      dec->decodedSize = dec->lzmaState.dicPos;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (dec->decodedSize < targetSize)
          return SZ_ERROR_DATA;
        break;
      }
      if (dec->decodedSize >= targetSize)
        break;
      if (inProcessed == 0 && dicPosBefore == dec->decodedSize)
        return SZ_ERROR_DATA;
    }
    return SZ_OK;
  }

#ifndef Z7_NO_METHOD_LZMA2
  if (dec->mainMethod == k_LZMA2)
  {
    ELzmaFinishMode finishMode = (targetSize == dec->totalUnpackSize) ? LZMA_FINISH_END : LZMA_FINISH_ANY;
    for (;;)
    {
      if (dec->lzma2State.decoder.dicPos >= targetSize)
      {
        dec->decodedSize = dec->lzma2State.decoder.dicPos;
        break;
      }
      const void *inBuf = NULL;
      size_t lookahead = (1 << 18);
      if (lookahead > dec->remainingInSize)
        lookahead = (size_t)dec->remainingInSize;
      SRes res = ILookInStream_Look(dec->activeStream, &inBuf, &lookahead);
      if (res != SZ_OK)
        return res;

      SizeT inProcessed = (SizeT)lookahead;
      SizeT dicPosBefore = dec->lzma2State.decoder.dicPos;
      ELzmaStatus status;
      res = Lzma2Dec_DecodeToDic(&dec->lzma2State, targetSize, (const Byte *)inBuf, &inProcessed, finishMode, &status);
      dec->remainingInSize -= inProcessed;
      if (res != SZ_OK)
        return res;

      res = ILookInStream_Skip(dec->activeStream, inProcessed);
      if (res != SZ_OK)
        return res;

      dec->decodedSize = dec->lzma2State.decoder.dicPos;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (dec->decodedSize < targetSize)
          return SZ_ERROR_DATA;
        break;
      }
      if (dec->decodedSize >= targetSize)
        break;
      if (inProcessed == 0 && dicPosBefore == dec->decodedSize)
        return SZ_ERROR_DATA;
    }
    return SZ_OK;
  }
#endif

  return SZ_ERROR_UNSUPPORTED;
}

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
    ISzAllocPtr allocTemp)
{
  if (!dec)
  {
    return SzAr_DecodeFolder(p, folderIndex, inStream, startPos, outBuffer, totalUnpackSize, allocTemp);
  }

  if (targetUnpackSize > totalUnpackSize)
    targetUnpackSize = totalUnpackSize;

  if (!dec->active || dec->folderIndex != folderIndex || dec->outBuffer != outBuffer || dec->totalUnpackSize != totalUnpackSize)
  {
    SzFolderDecoder_Reset(dec, allocMain);

    CSzFolder folder;
    CSzData sd;
    const Byte *data = p->CodersData + p->FoCodersOffsets[folderIndex];
    sd.Data = data;
    sd.Size = p->FoCodersOffsets[(size_t)folderIndex + 1] - p->FoCodersOffsets[folderIndex];

    RINOK(SzGetNextFolderItem(&folder, &sd));
    if (sd.Size != 0
        || folder.UnpackStream != p->FoToMainUnpackSizeIndex[folderIndex]
        || totalUnpackSize != SzAr_GetFolderUnpackSize(p, folderIndex))
      return SZ_ERROR_FAIL;

    RINOK(CheckSupportedFolder(&folder));

    int aesIndex = FindAesCoder(&folder);
    int mainIndex = -1;
    BoolInt canStreamIncrementally = False;

    if (aesIndex >= 0)
    {
      if (folder.NumCoders == 1)
      {
        canStreamIncrementally = True;
        mainIndex = -1; // Copy directly from AES stream
      }
      else if (folder.NumCoders == 2)
      {
        mainIndex = (aesIndex == 0) ? 1 : 0;
        UInt32 m = (UInt32)folder.Coders[mainIndex].MethodID;
        if (m == k_Copy || m == k_LZMA
        #ifndef Z7_NO_METHOD_LZMA2
            || m == k_LZMA2
        #endif
        )
        {
          canStreamIncrementally = True;
        }
      }
    }
    else if (folder.NumCoders == 1)
    {
      mainIndex = 0;
      UInt32 m = (UInt32)folder.Coders[0].MethodID;
      if (m == k_Copy || m == k_LZMA
      #ifndef Z7_NO_METHOD_LZMA2
          || m == k_LZMA2
      #endif
      )
      {
        canStreamIncrementally = True;
      }
    }

    if (!canStreamIncrementally)
    {
      SRes fullRes = SzAr_DecodeFolder(p, folderIndex, inStream, startPos, outBuffer, totalUnpackSize, allocTemp);
      if (fullRes == SZ_OK)
      {
        dec->active = True;
        dec->crcChecked = True;
        dec->folderIndex = folderIndex;
        dec->outBuffer = outBuffer;
        dec->totalUnpackSize = totalUnpackSize;
        dec->decodedSize = totalUnpackSize;
      }
      return fullRes;
    }

    const UInt64 *packPositions = p->PackPositions + p->FoStartPackStreamIndex[folderIndex];
    UInt64 offset = packPositions[0];
    UInt64 inSize = packPositions[1] - offset;
    RINOK(LookInStream_SeekTo(inStream, startPos + offset));

    if (aesIndex >= 0)
    {
      const CSzCoderInfo *aesCoder = &folder.Coders[aesIndex];
      Byte key[32];
      Byte iv[16];
      RINOK(GetAesKeyAndIv((CSzAr *)p, aesCoder, data, key, iv));

      dec->aesStream = (CAesLookInStream *)ISzAlloc_Alloc(allocMain, sizeof(CAesLookInStream));
      if (!dec->aesStream)
        return SZ_ERROR_MEM;
      AesLookInStream_Init(dec->aesStream, inStream, inSize, key, iv);
      dec->activeStream = &dec->aesStream->vt;
      dec->hasAes = True;
      dec->allowPadding = True;
    }
    else
    {
      dec->activeStream = inStream;
      dec->hasAes = False;
      dec->allowPadding = False;
    }

    if (mainIndex < 0 || folder.Coders[mainIndex].MethodID == k_Copy)
    {
      dec->mainMethod = k_Copy;
    }
    else if (folder.Coders[mainIndex].MethodID == k_LZMA)
    {
      const CSzCoderInfo *mainCoder = &folder.Coders[mainIndex];
      SRes allocRes = LzmaDec_AllocateProbs(&dec->lzmaState, data + mainCoder->PropsOffset, mainCoder->PropsSize, allocMain);
      if (allocRes != SZ_OK)
      {
        SzFolderDecoder_Reset(dec, allocMain);
        return allocRes;
      }
      dec->lzmaAllocated = True;
      dec->lzmaState.dic = outBuffer;
      dec->lzmaState.dicBufSize = totalUnpackSize;
      LzmaDec_Init(&dec->lzmaState);
      dec->mainMethod = k_LZMA;
    }
#ifndef Z7_NO_METHOD_LZMA2
    else if (folder.Coders[mainIndex].MethodID == k_LZMA2)
    {
      const CSzCoderInfo *mainCoder = &folder.Coders[mainIndex];
      if (mainCoder->PropsSize != 1)
      {
        SzFolderDecoder_Reset(dec, allocMain);
        return SZ_ERROR_DATA;
      }
      SRes allocRes = Lzma2Dec_AllocateProbs(&dec->lzma2State, data[mainCoder->PropsOffset], allocMain);
      if (allocRes != SZ_OK)
      {
        SzFolderDecoder_Reset(dec, allocMain);
        return allocRes;
      }
      dec->lzma2Allocated = True;
      dec->lzma2State.decoder.dic = outBuffer;
      dec->lzma2State.decoder.dicBufSize = totalUnpackSize;
      Lzma2Dec_Init(&dec->lzma2State);
      dec->mainMethod = k_LZMA2;
    }
#endif

    dec->active = True;
    dec->crcChecked = False;
    dec->folderIndex = folderIndex;
    dec->remainingInSize = inSize;
    dec->outBuffer = outBuffer;
    dec->totalUnpackSize = totalUnpackSize;
    dec->decodedSize = 0;
  }

  SRes stepRes = SzFolderDecoder_StepTo(dec, targetUnpackSize);
  if (stepRes != SZ_OK)
  {
    SzFolderDecoder_Reset(dec, allocMain);
    return stepRes;
  }

  if (dec->decodedSize == dec->totalUnpackSize && !dec->crcChecked)
  {
    dec->crcChecked = True;
    if (SzBitWithVals_Check(&p->FolderCRCs, folderIndex))
    {
      if (CrcCalc(outBuffer, totalUnpackSize) != p->FolderCRCs.Vals[folderIndex])
      {
        SzFolderDecoder_Reset(dec, allocMain);
        return SZ_ERROR_CRC;
      }
    }
  }

  return SZ_OK;
}
