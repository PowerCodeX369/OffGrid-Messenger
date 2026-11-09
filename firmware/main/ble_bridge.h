#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define OFFGRID_BLE_LINE_MAX 512
#define OFFGRID_BLE_FRAME_MAX 180
#define OFFGRID_BLE_COMMAND_TEXT 0
#define OFFGRID_BLE_COMMAND_BINARY 1
typedef struct {
    uint16_t token;   /* binary request correlation; 0 for ASCII */
    uint16_t length;  /* binary payload size; 0 for ASCII */
    uint8_t kind;
    union { char text[OFFGRID_BLE_LINE_MAX]; uint8_t frame[OFFGRID_BLE_FRAME_MAX]; } data;
} offgrid_ble_command_t;
void offgrid_ble_init(const uint8_t mac[6], const uint8_t key[32], bool present);
void offgrid_ble_publish(const char *line);
bool offgrid_ble_receive_command(offgrid_ble_command_t *out);
void offgrid_ble_set_key(const uint8_t key[32], bool present);
