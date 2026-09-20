#ifndef WATCHPICTURE_SHA256_KDF_H
#define WATCHPICTURE_SHA256_KDF_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint32_t state[8];
    uint64_t count;
    uint8_t buffer[64];
} SHA256_CTX;

void sha256_init(SHA256_CTX *ctx);
void sha256_update(SHA256_CTX *ctx, const uint8_t *data, size_t len);
void sha256_final(SHA256_CTX *ctx, uint8_t hash[32]);

/**
 * 7z PBKDF2 key derivation using SHA-256.
 * Continuously feeds salt + password + 8-byte counter into the SHA-256 stream
 * for (1 << numCyclesPower) iterations and outputs the final 32-byte AES key.
 */
void sha256_7z_derive_key(
    const uint8_t *pwd,
    size_t pwd_len,
    const uint8_t *salt,
    size_t salt_len,
    int numCyclesPower,
    uint8_t out_key[32]
);

#ifdef __cplusplus
}
#endif

#endif // WATCHPICTURE_SHA256_KDF_H
