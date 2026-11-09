#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <string.h>
/* Phase 2.3: binary GATT write, one complete OGC radio fragment per write.
 * B7 47 01 | uint16 token little-endian | 1..180 encrypted data bytes.
 * The header is routing metadata, NOT an authentication scheme. The BLE link
 * must already be authenticated with the unchanged challenge/HMAC flow. */
#define OFFGRID_BIN_MAGIC0 0xB7u
#define OFFGRID_BIN_MAGIC1 0x47u
#define OFFGRID_BIN_VERSION 1u
#define OFFGRID_BIN_HEAD 5u
#define OFFGRID_BIN_MAX_FRAME 180u
#define OFFGRID_BIN_MAX_WRITE (OFFGRID_BIN_HEAD+OFFGRID_BIN_MAX_FRAME)
static inline bool offgrid_bin_decode(const uint8_t *p, size_t n,
                                      uint16_t *token, uint8_t out[OFFGRID_BIN_MAX_FRAME],
                                      uint16_t *payload_len) {
    if (!p || !token || !out || !payload_len ||
        n<=OFFGRID_BIN_HEAD || n>OFFGRID_BIN_MAX_WRITE ||
        p[0]!=OFFGRID_BIN_MAGIC0 || p[1]!=OFFGRID_BIN_MAGIC1 ||
        p[2]!=OFFGRID_BIN_VERSION) return false;
    uint16_t t=(uint16_t)(p[3] | ((uint16_t)p[4]<<8));
    if (!t) return false; /* 0 is reserved: never a valid request ID */
    *token=t;*payload_len=(uint16_t)(n-OFFGRID_BIN_HEAD);
    memcpy(out,p+OFFGRID_BIN_HEAD,*payload_len);
    return true;
}
