#pragma once
#include <stdint.h>
typedef struct { uint8_t value[16]; } ble_uuid_t;
typedef struct { ble_uuid_t u; } ble_uuid128_t;
#define BLE_UUID128_INIT(...) { { {0} } }
