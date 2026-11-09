/* ESP-IDF 6.x: Mbed TLS v4 uses PSA Crypto for HMAC-SHA256.
 * BLE authentication continues to use HMAC-SHA256(key, "OffGridBLE2" || nonce).
 * This helper imports an in-memory, temporary PSA key and destroys it on exit.
 * It never sends the pairing key over BLE.
 */
#ifndef OFFGRID_BLE_AUTH_PSA_H
#define OFFGRID_BLE_AUTH_PSA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "psa/crypto.h"

static inline bool offgrid_ble_hmac_sha256(const uint8_t key[32],
                                           const uint8_t *message,
                                           size_t message_length,
                                           uint8_t mac[32])
{
    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_HMAC);
    psa_set_key_bits(&attrs, 256);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_SIGN_MESSAGE);
    psa_set_key_algorithm(&attrs, PSA_ALG_HMAC(PSA_ALG_SHA_256));

    psa_key_id_t key_id = 0;
    psa_status_t status = psa_import_key(&attrs, key, 32, &key_id);
    psa_reset_key_attributes(&attrs);
    if (status != PSA_SUCCESS) return false;

    size_t mac_length = 0;
    status = psa_mac_compute(key_id, PSA_ALG_HMAC(PSA_ALG_SHA_256),
                             message, message_length, mac, 32, &mac_length);
    psa_status_t cleanup_status = psa_destroy_key(key_id);
    return status == PSA_SUCCESS && mac_length == 32 && cleanup_status == PSA_SUCCESS;
}

#endif /* OFFGRID_BLE_AUTH_PSA_H */
