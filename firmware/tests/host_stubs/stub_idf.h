#ifndef HOST_IDF_STUB
#define HOST_IDF_STUB
const char *esp_err_to_name(int err);
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
typedef int esp_err_t;
typedef void *QueueHandle_t;
typedef unsigned int nvs_handle_t;
typedef struct {int reserved;} wifi_init_config_t;
typedef struct {uint8_t peer_addr[6];int channel;int ifidx;bool encrypt;} esp_now_peer_info_t;
typedef struct {int phymode;int rate;} esp_now_rate_config_t;
typedef struct {int8_t rssi;} host_rxctrl_t;
typedef struct {const uint8_t *src_addr;const host_rxctrl_t *rx_ctrl;} esp_now_recv_info_t;
#define WIFI_INIT_CONFIG_DEFAULT() ((wifi_init_config_t){0})
#define ESP_IDF_VERSION 50400
#define ESP_IDF_VERSION_VAL(a,b,c) ((a)*10000+(b)*100+(c))
#define ESP_OK 0
#define ESP_ERR_INVALID_STATE 4 /* ESP-IDF defines this in esp_err.h; host-only stub */
#define ESP_ERR_NVS_NO_FREE_PAGES 1
#define ESP_ERR_NVS_NEW_VERSION_FOUND 2
#define ESP_ERR_ESPNOW_EXIST 3
#define NVS_READONLY 0
#define NVS_READWRITE 1
#define WIFI_IF_STA 0
#define WIFI_MODE_STA 1
#define WIFI_STORAGE_RAM 0
#define WIFI_SECOND_CHAN_NONE 0
#define WIFI_PS_NONE 0
#define ESP_MAC_WIFI_STA 0
#define WIFI_PHY_MODE_11B 0
#define WIFI_PHY_MODE_11G 1
#define WIFI_PHY_MODE_LR 2
#define WIFI_PHY_RATE_LORA_250K 0x29
#define WIFI_PHY_RATE_LORA_500K 0x2A
#define WIFI_PROTOCOL_11B 1
#define WIFI_PROTOCOL_11G 2
#define WIFI_PROTOCOL_11N 4
#define WIFI_PROTOCOL_LR 8
#define WIFI_PHY_RATE_1M_L 0
#define WIFI_PHY_RATE_6M 6
#define WIFI_PHY_RATE_12M 12
#define pdTRUE 1
#define pdPASS 1
#define pdMS_TO_TICKS(x) (x)
#define ESP_ERROR_CHECK(x) ((void)(x))
#endif
