/* Host-only PSA compatibility shim for test_ble_hmac_psa.c.
 * This file is NOT part of the ESP-IDF component. */
#ifndef PSA_CRYPTO_H
#define PSA_CRYPTO_H
#include <stddef.h>
#include <stdint.h>
typedef int psa_status_t;
typedef uint32_t psa_key_id_t;
typedef uint32_t psa_algorithm_t;
typedef struct {uint32_t type, bits, usage, algorithm;} psa_key_attributes_t;
#define PSA_KEY_ATTRIBUTES_INIT {0,0,0,0}
#define PSA_SUCCESS 0
#define PSA_ERROR_GENERIC_ERROR (-132)
#define PSA_KEY_TYPE_HMAC 0x1100
#define PSA_KEY_USAGE_SIGN_MESSAGE 0x400
#define PSA_ALG_SHA_256 0x02000009
#define PSA_ALG_HMAC(alg) (0x03800000u | ((alg) & 0xffu))
void psa_set_key_type(psa_key_attributes_t *a,uint32_t v);
void psa_set_key_bits(psa_key_attributes_t *a,uint32_t v);
void psa_set_key_usage_flags(psa_key_attributes_t *a,uint32_t v);
void psa_set_key_algorithm(psa_key_attributes_t *a,uint32_t v);
void psa_reset_key_attributes(psa_key_attributes_t *a);
psa_status_t psa_crypto_init(void);
psa_status_t psa_import_key(const psa_key_attributes_t *a,const uint8_t *k,size_t n,psa_key_id_t *out);
psa_status_t psa_mac_compute(psa_key_id_t id,psa_algorithm_t alg,const uint8_t *msg,size_t msglen,uint8_t *mac,size_t maxout,size_t *outlen);
psa_status_t psa_destroy_key(psa_key_id_t id);
#endif
