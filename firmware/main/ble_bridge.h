#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
void offgrid_ble_init(const uint8_t mac[6], const uint8_t key[32], bool present);
void offgrid_ble_publish(const char *line);
bool offgrid_ble_receive_line(char *out, size_t capacity);
void offgrid_ble_set_key(const uint8_t key[32], bool present);
