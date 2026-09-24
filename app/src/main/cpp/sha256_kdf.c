#include "sha256_kdf.h"
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1 << 6)
#endif
#endif

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

#if defined(__aarch64__)
static int g_has_hw_sha2 = -1;

static inline int has_hw_sha2(void) {
    int v = __atomic_load_n(&g_has_hw_sha2, __ATOMIC_RELAXED);
    if (v >= 0) return v;
    unsigned long hwcap = getauxval(AT_HWCAP);
    v = (hwcap & HWCAP_SHA2) ? 1 : 0;
    __atomic_store_n(&g_has_hw_sha2, v, __ATOMIC_RELAXED);
    return v;
}

__attribute__((target("crypto")))
static void sha256_transform_arm64(SHA256_CTX *ctx, const uint8_t *data, size_t num_blocks) {
    uint32x4_t state0 = vld1q_u32(&ctx->state[0]);
    uint32x4_t state1 = vld1q_u32(&ctx->state[4]);

    while (num_blocks-- > 0) {
        uint32x4_t abcd_save = state0;
        uint32x4_t efgh_save = state1;

        uint32x4_t s0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 0)));
        uint32x4_t s1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16)));
        uint32x4_t s2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 32)));
        uint32x4_t s3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 48)));
        data += 64;

        uint32x4_t tmp, abcd_prev;

        #define SHA256_ROUND4(w0, w1, w2, w3, idx) \
            tmp = vaddq_u32(w0, vld1q_u32(&K[idx])); \
            abcd_prev = state0; \
            state0 = vsha256hq_u32(state0, state1, tmp); \
            state1 = vsha256h2q_u32(state1, abcd_prev, tmp); \
            w0 = vsha256su1q_u32(vsha256su0q_u32(w0, w1), w2, w3);

        #define SHA256_ROUND4_LAST(w0, idx) \
            tmp = vaddq_u32(w0, vld1q_u32(&K[idx])); \
            abcd_prev = state0; \
            state0 = vsha256hq_u32(state0, state1, tmp); \
            state1 = vsha256h2q_u32(state1, abcd_prev, tmp);

        SHA256_ROUND4(s0, s1, s2, s3, 0)
        SHA256_ROUND4(s1, s2, s3, s0, 4)
        SHA256_ROUND4(s2, s3, s0, s1, 8)
        SHA256_ROUND4(s3, s0, s1, s2, 12)
        SHA256_ROUND4(s0, s1, s2, s3, 16)
        SHA256_ROUND4(s1, s2, s3, s0, 20)
        SHA256_ROUND4(s2, s3, s0, s1, 24)
        SHA256_ROUND4(s3, s0, s1, s2, 28)
        SHA256_ROUND4(s0, s1, s2, s3, 32)
        SHA256_ROUND4(s1, s2, s3, s0, 36)
        SHA256_ROUND4(s2, s3, s0, s1, 40)
        SHA256_ROUND4(s3, s0, s1, s2, 44)
        SHA256_ROUND4_LAST(s0, 48)
        SHA256_ROUND4_LAST(s1, 52)
        SHA256_ROUND4_LAST(s2, 56)
        SHA256_ROUND4_LAST(s3, 60)

        #undef SHA256_ROUND4
        #undef SHA256_ROUND4_LAST

        state0 = vaddq_u32(state0, abcd_save);
        state1 = vaddq_u32(state1, efgh_save);
    }

    vst1q_u32(&ctx->state[0], state0);
    vst1q_u32(&ctx->state[4], state1);
}
#endif

static void sha256_transform_sw(SHA256_CTX *ctx, const uint8_t data[64]) {
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

static inline void sha256_transform_blocks(SHA256_CTX *ctx, const uint8_t *data, size_t num_blocks) {
#if defined(__aarch64__)
    if (has_hw_sha2()) {
        sha256_transform_arm64(ctx, data, num_blocks);
        return;
    }
#endif
    while (num_blocks-- > 0) {
        sha256_transform_sw(ctx, data);
        data += 64;
    }
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
        sha256_transform_blocks(ctx, ctx->buffer, 1);
        data += needed;
        len -= needed;
    }

    if (len >= 64) {
        size_t blocks = len / 64;
        sha256_transform_blocks(ctx, data, blocks);
        data += blocks * 64;
        len -= blocks * 64;
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
        sha256_transform_blocks(ctx, ctx->buffer, 1);
        buf_idx = 0;
    }

    memset(&ctx->buffer[buf_idx], 0, 56 - buf_idx);
    uint64_t bit_len = ctx->count * 8;
    for (int i = 0; i < 8; i++) {
        ctx->buffer[63 - i] = (uint8_t)(bit_len >> (i * 8));
    }
    sha256_transform_blocks(ctx, ctx->buffer, 1);

    for (int i = 0; i < 8; i++) {
        hash[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        hash[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        hash[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        hash[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

#define KDF_CACHE_CAPACITY 64
#define KDF_MAX_PWD_LEN 256
#define KDF_MAX_SALT_LEN 32

typedef struct {
    int valid;
    size_t pwd_len;
    size_t salt_len;
    int num_cycles_power;
    uint8_t pwd[KDF_MAX_PWD_LEN];
    uint8_t salt[KDF_MAX_SALT_LEN];
    uint8_t key[32];
} KdfCacheEntry;

static KdfCacheEntry g_kdf_cache[KDF_CACHE_CAPACITY];
static int g_kdf_cache_next = 0;
static pthread_mutex_t g_kdf_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

static int kdf_cache_lookup(
    const uint8_t *pwd,
    size_t pwd_len,
    const uint8_t *salt,
    size_t salt_len,
    int numCyclesPower,
    uint8_t out_key[32]
) {
    if (pwd_len > KDF_MAX_PWD_LEN || salt_len > KDF_MAX_SALT_LEN) return 0;
    int found = 0;
    pthread_mutex_lock(&g_kdf_cache_mutex);
    for (int i = 0; i < KDF_CACHE_CAPACITY; i++) {
        const KdfCacheEntry *e = &g_kdf_cache[i];
        if (e->valid &&
            e->num_cycles_power == numCyclesPower &&
            e->pwd_len == pwd_len &&
            e->salt_len == salt_len &&
            (pwd_len == 0 || memcmp(e->pwd, pwd, pwd_len) == 0) &&
            (salt_len == 0 || memcmp(e->salt, salt, salt_len) == 0)) {
            memcpy(out_key, e->key, 32);
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_kdf_cache_mutex);
    return found;
}

static void kdf_cache_store(
    const uint8_t *pwd,
    size_t pwd_len,
    const uint8_t *salt,
    size_t salt_len,
    int numCyclesPower,
    const uint8_t key[32]
) {
    if (pwd_len > KDF_MAX_PWD_LEN || salt_len > KDF_MAX_SALT_LEN) return;
    pthread_mutex_lock(&g_kdf_cache_mutex);
    KdfCacheEntry *e = &g_kdf_cache[g_kdf_cache_next];
    g_kdf_cache_next = (g_kdf_cache_next + 1) % KDF_CACHE_CAPACITY;
    e->valid = 1;
    e->pwd_len = pwd_len;
    e->salt_len = salt_len;
    e->num_cycles_power = numCyclesPower;
    if (pwd_len > 0) memcpy(e->pwd, pwd, pwd_len);
    if (salt_len > 0) memcpy(e->salt, salt, salt_len);
    memcpy(e->key, key, 32);
    pthread_mutex_unlock(&g_kdf_cache_mutex);
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

    if (kdf_cache_lookup(pwd, pwd_len, salt, salt_len, numCyclesPower, out_key)) {
        return;
    }

    SHA256_CTX ctx;
    sha256_init(&ctx);

    size_t chunk_len = salt_len + pwd_len + 8;
    // Batch multiple cycles into a larger buffer (e.g. 64 cycles at a time) so sha256_update
    // processes multi-block bursts directly in sha256_transform_arm64 without per-cycle memcpy overhead.
    const size_t batch_cycles = 64;
    const size_t batch_buf_size = chunk_len * batch_cycles;
    uint8_t *batch_buf = (uint8_t *)malloc(batch_buf_size);
    if (!batch_buf) {
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
        kdf_cache_store(pwd, pwd_len, salt, salt_len, numCyclesPower, out_key);
        return;
    }

    for (size_t b = 0; b < batch_cycles; b++) {
        uint8_t *slot = batch_buf + b * chunk_len;
        if (salt_len > 0) memcpy(slot, salt, salt_len);
        if (pwd_len > 0) memcpy(slot + salt_len, pwd, pwd_len);
        memset(slot + salt_len + pwd_len, 0, 8);
    }

    uint64_t total_cycles = 1ULL << numCyclesPower;
    uint64_t i = 0;
    while (i + batch_cycles <= total_cycles) {
        for (size_t b = 0; b < batch_cycles; b++) {
            uint64_t c = i + b;
            uint8_t *cnt_ptr = batch_buf + b * chunk_len + salt_len + pwd_len;
            memcpy(cnt_ptr, &c, 8); // Android is little-endian (matches 7z 64-bit LE counter)
        }
        sha256_update(&ctx, batch_buf, batch_buf_size);
        i += batch_cycles;
    }

    for (; i < total_cycles; i++) {
        uint8_t *slot = batch_buf;
        memcpy(slot + salt_len + pwd_len, &i, 8);
        sha256_update(&ctx, slot, chunk_len);
    }

    free(batch_buf);
    sha256_final(&ctx, out_key);
    kdf_cache_store(pwd, pwd_len, salt, salt_len, numCyclesPower, out_key);
}
