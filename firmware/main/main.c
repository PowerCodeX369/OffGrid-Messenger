/* OffGrid Messenger – Phase 1 (ESP32-C3, ESP-IDF 5.2–5.5)
 * Identical firmware for both boards: no MAC address configuration needed.
 * ESP-NOW broadcast transport for *TEST TEXT ONLY* (unencrypted).
 * USB Serial/JTAG terminal commands: MSG <text>, PING, HELP.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>
#include <inttypes.h>
#include <stdbool.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_now.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "nvs_flash.h"

#define RADIO_CHANNEL 6
#define TEXT_CAPACITY 180
#define RX_QUEUE_DEPTH 12
#define PACKET_MAGIC 0x4F474D31u /* OGM1 */
#define PACKET_VERSION 1
#define PACKET_TYPE_TEXT 1
#define PACKET_TYPE_PING 2
#define PACKET_TYPE_PONG 3
#define LINE_CAPACITY 192

static const char *TAG = "offgrid";
static const uint8_t broadcast_mac[6] = {0xff,0xff,0xff,0xff,0xff,0xff};
static uint8_t own_mac[6];
static uint32_t next_msg_id = 1;
static QueueHandle_t rx_queue;

/* Packed wire protocol; all ESP32-C3 chips use the same endianness. */
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint8_t version;
    uint8_t type;
    uint8_t sender[6];
    uint32_t msg_id;
    uint16_t text_len;
    char text[TEXT_CAPACITY];
} packet_t;

typedef struct {
    uint8_t source_mac[6];
    packet_t packet;
    int length;
} rx_event_t;

static void print_mac(const uint8_t *mac) {
    printf("%02X:%02X:%02X:%02X:%02X:%02X", mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
}

/* Minimal receive callback: do NOT do slow I/O in the Wi-Fi callback. */
static void on_receive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
    const size_t header_len = offsetof(packet_t, text);
    if (!info || !info->src_addr || !data || len < (int)header_len || len > (int)sizeof(packet_t)) return;
    rx_event_t event = {0};
    memcpy(event.source_mac, info->src_addr, 6);
    memcpy(&event.packet, data, (size_t)len);
    event.length = len;
    if (event.packet.magic != PACKET_MAGIC || event.packet.version != PACKET_VERSION) return;
    if (event.packet.text_len > TEXT_CAPACITY ||
        (size_t)len != header_len + event.packet.text_len) return;
    if (memcmp(event.packet.sender, own_mac, 6) == 0) return;
    (void)xQueueSend(rx_queue, &event, 0);
}

static void send_packet(uint8_t type, const char *text) {
    packet_t packet = {0};
    size_t len = text ? strlen(text) : 0;
    if (len > TEXT_CAPACITY) {
        printf("ERROR|Message too long (max %d bytes)\n", TEXT_CAPACITY);
        return;
    }
    packet.magic = PACKET_MAGIC;
    packet.version = PACKET_VERSION;
    packet.type = type;
    memcpy(packet.sender, own_mac, 6);
    packet.msg_id = next_msg_id++;
    packet.text_len = (uint16_t)len;
    if (len) memcpy(packet.text, text, len);

    esp_err_t err = esp_now_send(broadcast_mac, (const uint8_t *)&packet,
                                  offsetof(packet_t, text) + len);
    if (err == ESP_OK) {
        /* Sent means accepted for radio transmission, NOT delivered. */
        printf("TX|%" PRIu32 "|%s\n", packet.msg_id,
               type == PACKET_TYPE_TEXT ? "TEXT" : "CONTROL");
    } else {
        printf("ERROR|ESP-NOW send failed: %s\n", esp_err_to_name(err));
    }
}

static void receiver_task(void *arg) {
    rx_event_t event;
    while (true) {
        if (xQueueReceive(rx_queue, &event, portMAX_DELAY) != pdTRUE) continue;
        packet_t *packet = &event.packet;
        if (packet->type == PACKET_TYPE_TEXT) {
            printf("RX|");
            print_mac(event.source_mac);
            printf("|%.*s\n", (int)packet->text_len, packet->text);
        } else if (packet->type == PACKET_TYPE_PING) {
            printf("PEER|");
            print_mac(event.source_mac);
            printf("|PING\n");
            send_packet(PACKET_TYPE_PONG, "");
        } else if (packet->type == PACKET_TYPE_PONG) {
            printf("PEER|");
            print_mac(event.source_mac);
            printf("|PONG\n");
        }
    }
}

static void serial_task(void *arg) {
    char line[LINE_CAPACITY];
    size_t used = 0;
    puts("HELP|Type MSG Hello from A, PING, or HELP");
    while (true) {
        int ch = getchar();
        if (ch == EOF) {
            clearerr(stdin);
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        if (ch == '\r') continue;
        if (ch == '\n') {
            line[used] = '\0';
            if (strncmp(line, "MSG ", 4) == 0 && line[4]) {
                send_packet(PACKET_TYPE_TEXT, line + 4);
            } else if (strcmp(line, "PING") == 0) {
                send_packet(PACKET_TYPE_PING, "");
            } else if (strcmp(line, "HELP") == 0) {
                puts("HELP|MSG <text> (up to 180 UTF-8 bytes), PING, HELP");
            } else if (used) {
                puts("ERROR|Unknown command; try HELP");
            }
            used = 0;
        } else if (used < sizeof(line)-1) {
            line[used++] = (char)ch;
        } else {
            used = 0;
            puts("ERROR|Line too long; discarded");
        }
    }
}

void app_main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    puts("BOOT|OffGrid Messenger Phase 1");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_channel(RADIO_CHANNEL, WIFI_SECOND_CHAN_NONE));
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_read_mac(own_mac, ESP_MAC_WIFI_STA));

    rx_queue = xQueueCreate(RX_QUEUE_DEPTH, sizeof(rx_event_t));
    if (!rx_queue) abort();
    ESP_ERROR_CHECK(esp_now_init());
    ESP_ERROR_CHECK(esp_now_register_recv_cb(on_receive));

    esp_now_peer_info_t broadcast_peer = {0};
    memcpy(broadcast_peer.peer_addr, broadcast_mac, sizeof(broadcast_mac));
    broadcast_peer.channel = RADIO_CHANNEL;
    broadcast_peer.ifidx = WIFI_IF_STA;
    broadcast_peer.encrypt = false; /* Explicitly unencrypted Phase 1 demo */
    ESP_ERROR_CHECK(esp_now_add_peer(&broadcast_peer));

    printf("READY|");
    print_mac(own_mac);
    printf("|CHANNEL=%d\n", RADIO_CHANNEL);
    ESP_LOGI(TAG, "Flash identical firmware to both boards");
    xTaskCreate(receiver_task, "rx", 4096, NULL, 5, NULL);
    xTaskCreate(serial_task, "serial", 4096, NULL, 4, NULL);
}
