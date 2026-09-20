#include "sha256_kdf.h"
#include <string.h>
#include <stdlib.h>

#define ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))
#define CH(x, y, z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x, y, z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTR(x, 2) ^ ROTR(x, 13) ^ ROTR(x, 22))
#define EP1(x) (ROTR(x, 6) ^ ROTR(x, 11) ^ ROTR(x, 25))
#define SIG0(x) (ROTR(x, 7) ^ ROTR(x, 18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x, 17) ^ ROTR(x, 19) ^ ((x) >> 10))

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static void sha256_transform(SHA256_CTX *ctx, const uint8_t data[64]) {
    uint32_t m[64];
    for (int i = 0; i < 16; i++) {
        m[i] = ((uint32_t)data[i * 4] << 24) |
               ((uint32_t)data[i * 4 + 1] << 16) |
               ((uint32_t)data[i * 4 + 2] << 8) |
               ((uint32_t)data[i * 4 + 3]);
    }
    for (int i = 16; i < 64; i++) {
        m[i] = SIG1(m[i - 2]) + m[i - 7] + SIG0(m[i - 15]) + m[i - 16];
    }

    uint32_t a = ctx->state[0];
    uint32_t b = ctx->state[1];
    uint32_t c = ctx->state[2];
    uint32_t d = ctx->state[3];
    uint32_t e = ctx->state[4];
    uint32_t f = ctx->state[5];
    uint32_t g = ctx->state[6];
    uint32_t h = ctx->state[7];

    for (int i = 0; i < 64; i++) {
        uint32_t t1 = h + EP1(e) + CH(e, f, g) + K[i] + m[i];
        uint32_t t2 = EP0(a) + MAJ(a, b, c);
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

void sha256_init(SHA256_CTX *ctx) {
    ctx->state[0] = 0x6a09e667;
    ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372;
    ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f;
    ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab;
    ctx->state[7] = 0x5be0cd19;
    ctx->count = 0;
}

void sha256_update(SHA256_CTX *ctx, const uint8_t *data, size_t len) {
    size_t buf_idx = (size_t)(ctx->count & 0x3F);
    ctx->count += len;

    if (buf_idx > 0) {
        size_t needed = 64 - buf_idx;
        if (len < needed) {
            memcpy(&ctx->buffer[buf_idx], data, len);
            return;
        }
        memcpy(&ctx->buffer[buf_idx], data, needed);
        sha256_transform(ctx, ctx->buffer);
        data += needed;
        len -= needed;
    }

    while (len >= 64) {
        sha256_transform(ctx, data);
        data += 64;
        len -= 64;
    }

    if (len > 0) {
        memcpy(ctx->buffer, data, len);
    }
}

void sha256_final(SHA256_CTX *ctx, uint8_t hash[32]) {
    size_t buf_idx = (size_t)(ctx->count & 0x3F);
    ctx->buffer[buf_idx++] = 0x80;

    if (buf_idx > 56) {
        memset(&ctx->buffer[buf_idx], 0, 64 - buf_idx);
        sha256_transform(ctx, ctx->buffer);
        buf_idx = 0;
    }

    memset(&ctx->buffer[buf_idx], 0, 56 - buf_idx);
    uint64_t bit_len = ctx->count * 8;
    for (int i = 0; i < 8; i++) {
        ctx->buffer[63 - i] = (uint8_t)(bit_len >> (i * 8));
    }
    sha256_transform(ctx, ctx->buffer);

    for (int i = 0; i < 8; i++) {
        hash[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        hash[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        hash[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        hash[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

void sha256_7z_derive_key(
    const uint8_t *pwd,
    size_t pwd_len,
    const uint8_t *salt,
    size_t salt_len,
    int numCyclesPower,
    uint8_t out_key[32]
) {
    if (numCyclesPower == 63) {
        memset(out_key, 0, 32);
        size_t s_len = salt_len < 32 ? salt_len : 32;
        if (s_len > 0) memcpy(out_key, salt, s_len);
        size_t p_len = (32 - s_len) < pwd_len ? (32 - s_len) : pwd_len;
        if (p_len > 0) memcpy(out_key + s_len, pwd, p_len);
        return;
    }

    SHA256_CTX ctx;
    sha256_init(&ctx);

    size_t chunk_len = salt_len + pwd_len + 8;
    uint8_t *chunk = (uint8_t *)malloc(chunk_len);
    if (!chunk) {
        // Fallback without combined buffer
        uint8_t counter[8] = {0};
        uint64_t cycles = 1ULL << numCyclesPower;
        for (uint64_t i = 0; i < cycles; i++) {
            if (salt_len > 0) sha256_update(&ctx, salt, salt_len);
            if (pwd_len > 0) sha256_update(&ctx, pwd, pwd_len);
            sha256_update(&ctx, counter, 8);
            for (int j = 0; j < 8; j++) {
                counter[j]++;
                if (counter[j] != 0) break;
            }
        }
        sha256_final(&ctx, out_key);
        return;
    }

    if (salt_len > 0) memcpy(chunk, salt, salt_len);
    if (pwd_len > 0) memcpy(chunk + salt_len, pwd, pwd_len);
    uint8_t *cnt_ptr = chunk + salt_len + pwd_len;
    memset(cnt_ptr, 0, 8);

    uint64_t cycles = 1ULL << numCyclesPower;
    for (uint64_t i = 0; i < cycles; i++) {
        sha256_update(&ctx, chunk, chunk_len);
        for (int j = 0; j < 8; j++) {
            cnt_ptr[j]++;
            if (cnt_ptr[j] != 0) break;
        }
    }

    free(chunk);
    sha256_final(&ctx, out_key);
}
