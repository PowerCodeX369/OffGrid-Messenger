#pragma once
#include "stub_idf.h"
typedef struct {
    int rx_buffer_size;
    int tx_buffer_size;
} usb_serial_jtag_driver_config_t;
esp_err_t usb_serial_jtag_driver_install(usb_serial_jtag_driver_config_t *config);
int usb_serial_jtag_read_bytes(void *buf, uint32_t length, uint32_t ticks_to_wait);
