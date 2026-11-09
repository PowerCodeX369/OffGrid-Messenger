#pragma once
#include <stdint.h>
#include <stddef.h>
struct os_mbuf {uint16_t len;};
#define OS_MBUF_PKTLEN(m) ((m)->len)
struct os_mbuf *ble_hs_mbuf_from_flat(const void *,uint16_t);
int ble_hs_mbuf_to_flat(struct os_mbuf *, void *, uint16_t, uint16_t *);
