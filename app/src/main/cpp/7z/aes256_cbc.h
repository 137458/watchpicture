#ifndef WATCHPICTURE_AES256_CBC_H
#define WATCHPICTURE_AES256_CBC_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AES256_KEY_SIZE 32
#define AES256_BLOCK_SIZE 16

typedef struct {
    uint32_t round_keys[60]; // 15 round keys of 4 32-bit words
    uint8_t dec_round_keys[15][16]; // Pre-expanded round keys for ARMv8 AES instructions
    uint8_t iv[16];
} aes256_cbc_ctx;

void aes256_cbc_init(aes256_cbc_ctx *ctx, const uint8_t key[32], const uint8_t iv[16]);
void aes256_cbc_decrypt(aes256_cbc_ctx *ctx, const uint8_t *src, uint8_t *dst, size_t len);

#ifdef __cplusplus
}
#endif

#endif // WATCHPICTURE_AES256_CBC_H
