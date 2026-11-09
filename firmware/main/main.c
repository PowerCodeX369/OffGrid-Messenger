/* OffGrid Messenger Phase 1.4.11 experimental LR, ESP-IDF >= 5.2 / ESP32-C3.
 * Experimental extension of the frozen, measured-working 1.4.10.2 USB IRQ baseline.
 * AES-256-GCM runs in the BROWSER. NVS key management unchanged.
 * 1 Mbps broadcast HELLO/recovery; peer-aware 1/6/12 Mbps unicast for data.
 * Browser protocol, app ACK, retry IDs, USB READY, and task watchdog fix preserved.
 * Revert to 1.4.10.2 firmware immediately if this branch underperforms.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include "ble_bridge.h"
#include <stddef.h>
#include <inttypes.h>
#include <stdbool.h>
#include <ctype.h>
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
#include "esp_random.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_idf_version.h"
#include "link_rate_policy.h"
#if CONFIG_OFFGRID_USB_IRQ_RX
#include "driver/usb_serial_jtag.h"
/* IDF 5.2 exposes the VFS helper through esp_vfs_dev.h; newer releases
 * provide the dedicated public driver/usb_serial_jtag_vfs.h header. */
#if __has_include("driver/usb_serial_jtag_vfs.h")
#include "driver/usb_serial_jtag_vfs.h"
#define OFFGRID_USB_VFS_USE_DRIVER() usb_serial_jtag_vfs_use_driver()
#else
#include "esp_vfs_dev.h"
#define OFFGRID_USB_VFS_USE_DRIVER() esp_vfs_usb_serial_jtag_use_driver()
#endif
#endif
#if ESP_IDF_VERSION < ESP_IDF_VERSION_VAL(5,2,0)
#error "Adaptive PHY requires ESP-IDF 5.2 or newer"
#endif

#define CHANNEL 6
#define MAX_TEXT 180
#define MAX_PENDING 8
#define RX_HISTORY 24
#define MAX_ATTEMPTS 7
#define RETRY_MS 850
#define SEND_GAP_MS 8 /* less than old 65ms; only one browser fragment in flight */
#define PROTOCOL_MAGIC 0x4F474D32u /* OGM2; intentionally incompatible with Phase 1 */
#define PROTOCOL_VERSION 2
#define TYPE_TEXT 1
#define TYPE_RECEIPT 2
#define TYPE_READ 3
#define TYPE_HELLO 4 /* broadcast discovery/keepalive: unchanged radio 1 Mbps */
#define TYPE_BENCH_DATA 5 /* diagnostic only, never a chat message */
#define TYPE_BENCH_ACK 6 /* ESP32-to-ESP32 ACK, bypasses browsers */
#define TYPE_BATCH_RECEIPT 7 /* selective ACK list; optional photo-only extension */
#define SACK_MAX_IDS 6 /* capped below MAX_PENDING, and well within 250 byte ESPNOW v1 frame */
#define BENCH_PAYLOAD_BYTES 160
#define BENCH_MAX_PACKETS 128
#define BENCH_TIMEOUT_MS 1000
#define HELLO_MS 1800
#define LR_PROBE_MS 2600
#define LR_PROBE_DELAY_MS 5000
#define LR_HELLO_ID 0x4C523235u /* LR25, proprietary probe flag */
#define LR_PERSISTED_PEER_KEY "peer_mac"
#define PEER_TIMEOUT_MS 12000
#define PHY_DWELL_MS 4200
#define PHY_HOLD_MS 10000
#define EVENT_QUEUE_LEN 32
#define SERIAL_LINE 512
#define LINK_REPORT_MS 2500
#define READY_BEACON_MS 2500

static const uint8_t BROADCAST[6] = {255,255,255,255,255,255};
static uint8_t own_mac[6];
static uint32_t boot_session;
static uint32_t next_id = 1;
static QueueHandle_t event_queue;

/* Identical wire structure on all ESP32-C3 boards. Explicit length validation. */
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint8_t version;
    uint8_t type;
    uint8_t sender[6];
    uint32_t session;
    uint32_t id;
    uint16_t length;
    uint8_t text[MAX_TEXT];
} frame_t;

typedef enum { EV_RX = 1, EV_SEND, EV_APPACK, EV_STATUS, EV_READ, EV_RATE, EV_BENCH_START, EV_APPACK_BATCH } event_type_t;
typedef struct {
    event_type_t type;
    uint8_t mac[6];
    int8_t rssi;
    uint8_t rate_mode;
    uint32_t session;
    uint32_t id;
    uint16_t length;
    uint8_t text[MAX_TEXT];
    frame_t frame;
} event_t;
typedef struct {
    bool active;
    frame_t frame;
    int attempts;
    int64_t last_send_ms;
} outgoing_t;
typedef struct {
    bool active;
    bool app_acked;
    uint8_t mac[6];
    uint32_t session;
    uint32_t id;
    int64_t last_forward_ms;
} received_t;
static outgoing_t pending[MAX_PENDING];
static received_t history[RX_HISTORY];
static size_t history_next;
static int64_t last_radio_ms;
/* Diagnostics never affect the radio routing or rate. */
static uint8_t last_remote_mac[6];
static bool have_remote=false;
static int32_t remote_rssi=-127;
static uint32_t tx_ok=0,tx_fail=0,rx_ok=0,delivery_count=0,retry_count=0,failure_count=0;
/* New state is isolated from original ACK / encryption / USB protocol. */
static uint8_t peer_mac[6];
static bool peer_known=false;
static int64_t peer_last_seen_ms=0, last_hello_ms=0, last_phy_change_ms=0;
static int64_t last_lr_probe_ms=0;
static bool lr_supported=false;
static bool peer_persisted=false;
static uint8_t saved_peer_mac[6];
static uint32_t lr_probe_tx=0,lr_probe_rx=0,lr_fallbacks=0;
static uint8_t consecutive_no_ack=0;
static int64_t rate_hold_until_ms=0, rate_error_until_ms=0;
/* 1, 6, 12 are Mbps; 50=0.50 Mbps LR, 25=0.25 Mbps LR.
   This keeps the proven numeric short-range modes unchanged. */
static uint8_t phy_mbps=1;
static uint8_t link_mode=OG_RATE_AUTO;
static uint32_t hello_rx=0, hello_tx=0, valid_rssi_samples=0;
static uint32_t phy_changes=0, rate_errors=0;
static bool phy_configured=false;
static int64_t last_report_ms=0;
static int64_t last_ready_beacon_ms=0;
/* Diagnostics run only on the dispatcher/serial tasks. No Wi-Fi callback work.
 * They are disabled while normal messages are pending. */
typedef struct {
    bool active, awaiting;
    uint32_t token, count, index, ok, lost, retries;
    uint32_t sum_rtt_ms;
    uint8_t tries;
    int64_t start_ms, packet_started_ms;
} radio_bench_t;
static radio_bench_t radio_bench;
typedef struct {
    bool active;
    uint32_t token, count, bytes, expected_seq;
    int64_t started_ms;
} usb_input_bench_t;
static usb_input_bench_t usb_input_bench;
static uint8_t pairing_key[32];
static bool pairing_key_present=false;
/* These flags affect only USB receive. They do not change packet formats,
 * radio control, NVS, browser cryptography or the watchdog dispatcher. */
#if CONFIG_OFFGRID_USB_IRQ_RX
static bool usb_irq_rx_active=false;
static void setup_usb_irq_rx(void) {
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 4096,
        .tx_buffer_size = 4096,
    };
    esp_err_t err = usb_serial_jtag_driver_install(&cfg);
    /* IDF 6.x console initialization can have already installed the SAME
     * USB Serial/JTAG ISR driver before app_main. In that case the ESP-IDF
     * driver returns ESP_ERR_INVALID_STATE (driver already installed).
     * Do not misclassify that as a reason to fall back to slow getchar().
     * Reuse its existing RX ring buffer; never install a second driver. */
    if (err == ESP_OK || err == ESP_ERR_INVALID_STATE) {
        OFFGRID_USB_VFS_USE_DRIVER();
        usb_irq_rx_active=true;
        printf("USBMODE|IRQ|SRC=%s\n", err == ESP_OK ? "APP" : "CONSOLE");
    } else {
        /* Preserve the last working nonblocking fallback if real driver
         * initialization fails for resource or configuration reasons. */
        printf("USBMODE|LEGACY|DRIVER_INIT=%s\n", esp_err_to_name(err));
    }
}
#endif
/* Read complete FIFO/ISR batches into a caller-owned buffer; process them
 * through the unchanged line parser one byte at a time. When the driver
 * cannot initialize, use the last measured-working nonblocking stdio path. */
static int serial_receive_batch(uint8_t *dst, size_t capacity) {
#if CONFIG_OFFGRID_USB_IRQ_RX
    if (usb_irq_rx_active) {
        return usb_serial_jtag_read_bytes(dst, (uint32_t)capacity,
                                          pdMS_TO_TICKS(100) + 1);
    }
#endif
    (void)capacity;
    int ch = getchar();
    if (ch == EOF) {
        clearerr(stdin);
        vTaskDelay(1);
        return 0;
    }
    dst[0] = (uint8_t)ch;
    return 1;
}
/* The previously discovered peer can be reached with LR unicast after restart.
 * This is a transport hint, NOT a cryptographic identity: NVS pairing keys remain
 * separate and the receiving browser authenticates application data. */
static bool read_saved_peer(void) {
    nvs_handle_t h;
    if(nvs_open("offgrid", NVS_READONLY, &h)!=ESP_OK)return false;
    size_t n=sizeof(saved_peer_mac);
    esp_err_t e=nvs_get_blob(h,LR_PERSISTED_PEER_KEY,saved_peer_mac,&n);
    nvs_close(h);
    if(e!=ESP_OK||n!=6)return false;
    if(!memcmp(saved_peer_mac,BROADCAST,6)||!(saved_peer_mac[0]|saved_peer_mac[1]|saved_peer_mac[2]|saved_peer_mac[3]|saved_peer_mac[4]|saved_peer_mac[5]))return false;
    return true;
}
static bool save_peer_once(const uint8_t *mac) {
    if(peer_persisted)return memcmp(saved_peer_mac,mac,6)==0;
    nvs_handle_t h;
    if(nvs_open("offgrid", NVS_READWRITE, &h)!=ESP_OK)return false;
    esp_err_t e=nvs_set_blob(h,LR_PERSISTED_PEER_KEY,mac,6);
    if(e==ESP_OK)e=nvs_commit(h);
    nvs_close(h);
    if(e!=ESP_OK)return false;
    memcpy(saved_peer_mac,mac,6);peer_persisted=true;return true;
}
static const char *phy_label(void) {
    if(phy_mbps==25)return "0.25";
    if(phy_mbps==50)return "0.5";
    if(phy_mbps==6)return "6";
    if(phy_mbps==12)return "12";
    return "1";
}
static const char *phy_mode_label(void) {
    if(phy_mbps==25)return "LR250";
    if(phy_mbps==50)return "LR500";
    return "NORMAL";
}
static bool key_from_nvs(void){
    nvs_handle_t h;
    if(nvs_open("offgrid",NVS_READONLY,&h)!=ESP_OK)return false;
    size_t size=sizeof(pairing_key);
    esp_err_t e=nvs_get_blob(h,"pairkey",pairing_key,&size);
    nvs_close(h);
    return e==ESP_OK&&size==sizeof(pairing_key);
}
static bool key_to_nvs(const uint8_t *raw){
    nvs_handle_t h;
    if(nvs_open("offgrid",NVS_READWRITE,&h)!=ESP_OK)return false;
    esp_err_t e=nvs_set_blob(h,"pairkey",raw,32);
    if(e==ESP_OK)e=nvs_commit(h);
    nvs_close(h);
    if(e!=ESP_OK)return false;
    memcpy(pairing_key,raw,32);
    pairing_key_present=true;offgrid_ble_set_key(pairing_key,true);
    return true;
}
static void ble_printf(const char *fmt,...){
 char line[620];va_list args;va_start(args,fmt);int n=vsnprintf(line,sizeof(line),fmt,args);va_end(args);
 if(n<0||(size_t)n>=sizeof(line))return;
 fwrite(line,1,n,stdout);offgrid_ble_publish(line);
}
static void print_key(void){
    if(!pairing_key_present){puts("KEYEMPTY");return;}
    char line[74];
    const char *digits="0123456789ABCDEF";
    memcpy(line,"KEYDATA|",8);
    for(int i=0;i<32;i++){
        line[8+i*2]=digits[pairing_key[i]>>4];
        line[8+i*2+1]=digits[pairing_key[i]&15];
    }
    line[72]='\0';
    puts(line);
    memset(line,0,sizeof(line));
}


static int64_t now_ms(void) { return esp_timer_get_time() / 1000; }
static void print_mac(const uint8_t *m) {
    printf("%02X:%02X:%02X:%02X:%02X:%02X",m[0],m[1],m[2],m[3],m[4],m[5]);
}
/* Emit the entire HEX-bearing serial record in one stdio operation.
 * Prior revisions used hundreds of unbuffered putchar() calls per 160-byte
 * packet, an expensive choice for the ESP32-C3 USB Serial/JTAG console.
 * Keep exactly the same newline-delimited browser protocol.
 */
static void emit_hex_record(const char *prefix, const uint8_t *payload, size_t length) {
    static const char digits[] = "0123456789ABCDEF";
    char line[128 + MAX_TEXT * 2 + 2];
    size_t start = strlen(prefix);
    if (length > MAX_TEXT || start + 2 * length + 1 >= sizeof(line)) {
        puts("ERROR|SERIAL_RECORD_OVERFLOW");
        return;
    }
    memcpy(line, prefix, start);
    for (size_t i = 0; i < length; ++i) {
        line[start + 2 * i] = digits[payload[i] >> 4];
        line[start + 2 * i + 1] = digits[payload[i] & 0x0f];
    }
    const size_t bytes = start + 2 * length + 1;
    line[bytes - 1] = '\n';line[bytes]=0;
    (void)fwrite(line, 1, bytes, stdout);offgrid_ble_publish(line);
}
static const char *link_mode_name(void) {
    return link_mode==OG_RATE_LR250?"LR250":link_mode==OG_RATE_LR500?"LR500":link_mode==OG_RATE_RANGE?"RANGE":link_mode==OG_RATE_SPEED?"SPEED":"AUTO";
}
static void emit_link(void){
    char peer[20];
    if(have_remote){
        snprintf(peer,sizeof(peer),"%02X:%02X:%02X:%02X:%02X:%02X",
            last_remote_mac[0],last_remote_mac[1],last_remote_mac[2],
            last_remote_mac[3],last_remote_mac[4],last_remote_mac[5]);
    }else{
        snprintf(peer,sizeof(peer),"BROADCAST");
    }
    ble_printf("LINK|%s|RSSI=%ld|PHY=%s|MODE=%s|OK=%" PRIu32
           "|RETRY=%" PRIu32 "|FAIL=%" PRIu32 "|TXOK=%" PRIu32
           "|TXFAIL=%" PRIu32 "|RX=%" PRIu32 "|HELLO=%" PRIu32 "|CHANGES=%" PRIu32
           "|LR=%u|LRPROBE=%" PRIu32 "|LRRX=%" PRIu32 "|LRFALLBACK=%" PRIu32 "|PHYMODE=%s\n",
           peer,(long)remote_rssi,peer_known?phy_label():"1",link_mode_name(),
           delivery_count,retry_count,failure_count,tx_ok,tx_fail,rx_ok,hello_rx,phy_changes,
           (unsigned)lr_supported,lr_probe_tx,lr_probe_rx,lr_fallbacks,phy_mode_label());
}
static bool parse_mac(const char *s, uint8_t *m) {
    unsigned a[6]; int end = 0;
    if (sscanf(s,"%2x:%2x:%2x:%2x:%2x:%2x%n",&a[0],&a[1],&a[2],&a[3],&a[4],&a[5],&end)!=6 || s[end]!='\0') return false;
    for(int i=0;i<6;i++)m[i]=(uint8_t)a[i];
    return true;
}
static int nibble(char c) {
    if(c>='0'&&c<='9')return c-'0';
    if(c>='A'&&c<='F')return c-'A'+10;
    if(c>='a'&&c<='f')return c-'a'+10;
    return -1;
}
static bool read_hex(const char *s,uint8_t *dst,uint16_t *len) {
    size_t n=strlen(s);
    if(n==0 || n>MAX_TEXT*2 || (n&1))return false;
    for(size_t i=0;i<n/2;i++) {
        int a=nibble(s[i*2]),b=nibble(s[i*2+1]);
        if(a<0||b<0)return false;
        dst[i]=(uint8_t)((a<<4)|b);
    }
    *len=(uint16_t)(n/2);
    return true;
}
/* Announce READY periodically: the native USB serial port often opens after
 * the boot-time READY was printed. A repeated beacon also supports a new
 * browser after the previous one disconnects, without rebooting the ESP32.
 * IMPORTANT: single printf to avoid splitting the READY frame over lines. */
static void show_ready(const char *source) {
    ble_printf("READY|%02X:%02X:%02X:%02X:%02X:%02X|CHANNEL=%d|SESSION=%" PRIu32
           "|PROTO=2|CAP=180|STABLE=1|NVSKEY=1|ADAPTIVEPHY=1|DIAG=1|SACK=1|LR=%u|FROM=%s\n",
           own_mac[0],own_mac[1],own_mac[2],own_mac[3],own_mac[4],own_mac[5],
           CHANNEL,boot_session,(unsigned)lr_supported,source);
}
static void on_receive(const esp_now_recv_info_t *info,const uint8_t *data,int len) {
    const size_t base=offsetof(frame_t,text);
    if(!info||!info->src_addr||!data||len<(int)base||len>(int)sizeof(frame_t))return;
    event_t ev={0};ev.type=EV_RX;
    memcpy(ev.mac,info->src_addr,6);
    ev.rssi=info->rx_ctrl ? info->rx_ctrl->rssi : -127;
    memcpy(&ev.frame,data,(size_t)len);
    frame_t *f=&ev.frame;
    if(f->magic!=PROTOCOL_MAGIC||f->version!=PROTOCOL_VERSION)return;
    if(f->length>MAX_TEXT||(size_t)len!=base+f->length)return;
    if(f->type!=TYPE_TEXT&&f->type!=TYPE_RECEIPT&&f->type!=TYPE_READ&&f->type!=TYPE_HELLO&&f->type!=TYPE_BENCH_DATA&&f->type!=TYPE_BENCH_ACK&&f->type!=TYPE_BATCH_RECEIPT)return;
    if((f->type==TYPE_RECEIPT||f->type==TYPE_READ||f->type==TYPE_HELLO||f->type==TYPE_BENCH_ACK)&&f->length!=0)return;
    if(f->type==TYPE_BENCH_DATA&&f->length!=BENCH_PAYLOAD_BYTES)return;
    if(f->type==TYPE_BATCH_RECEIPT &&
       (f->length<5 || f->length>1+4*SACK_MAX_IDS || f->text[0]<1 ||
        f->text[0]>SACK_MAX_IDS || f->length!=1+4*f->text[0]))return;
    if(memcmp(f->sender,ev.mac,6)!=0||memcmp(f->sender,own_mac,6)==0)return;
    (void)xQueueSend(event_queue,&ev,0); /* Wi-Fi callback must never block */
}
static bool ensure_peer(const uint8_t *mac) {
    if(esp_now_is_peer_exist(mac))return true;
    esp_now_peer_info_t peer={0};
    memcpy(peer.peer_addr,mac,6);peer.channel=CHANNEL;peer.ifidx=WIFI_IF_STA;peer.encrypt=false;
    esp_err_t e=esp_now_add_peer(&peer);
    return e==ESP_OK||e==ESP_ERR_ESPNOW_EXIST;
}
static esp_err_t send_frame(const uint8_t *dest, const frame_t *f) {
    int64_t pause=SEND_GAP_MS-(now_ms()-last_radio_ms);
    if(pause>0)vTaskDelay(pdMS_TO_TICKS(pause)+1);
    esp_err_t err=esp_now_send(dest,(const uint8_t*)f,offsetof(frame_t,text)+f->length);
    last_radio_ms=now_ms();
    if(err==ESP_OK)tx_ok++;else tx_fail++;
    return err;
}

/* Called ONLY on dispatcher task. Never perform rate changes in a Wi-Fi callback. */
static bool set_peer_phy(uint8_t rate) {
    if(!peer_known || (rate!=1 && rate!=6 && rate!=12 && rate!=50 && rate!=25))return false;
    if((rate==25 || rate==50) && !lr_supported)return false;
    if(phy_configured && phy_mbps==rate)return true;
    if(now_ms()<rate_error_until_ms)return false;
    esp_now_rate_config_t cfg={0};
    cfg.phymode=(rate==25 || rate==50)?WIFI_PHY_MODE_LR:
                (rate==1?WIFI_PHY_MODE_11B:WIFI_PHY_MODE_11G);
    cfg.rate=(rate==25)?WIFI_PHY_RATE_LORA_250K:
             (rate==50)?WIFI_PHY_RATE_LORA_500K:
             (rate==1)?WIFI_PHY_RATE_1M_L:
             (rate==6?WIFI_PHY_RATE_6M:WIFI_PHY_RATE_12M);
    esp_err_t err=esp_now_set_peer_rate_config(peer_mac,&cfg);
    if(err!=ESP_OK){
        rate_errors++;
        rate_error_until_ms=now_ms()+5000;
        printf("RATE_ERROR|%s|%s\n",rate==25?"0.25":rate==50?"0.5":rate==6?"6":rate==12?"12":"1",esp_err_to_name(err));
        return false;
    }
    phy_configured=true;phy_mbps=rate;
    last_phy_change_ms=now_ms();phy_changes++;
    ble_printf("RATE|%s|%s\n",phy_label(),link_mode_name());
    return true;
}
static bool any_pending(void) {
    for(int i=0;i<MAX_PENDING;i++)if(pending[i].active)return true;
    return false;
}
/* A successful 1 Mbps broadcast discovery frame is our independent RSSI sample.
 * A peer can appear even when there are no chat messages yet. */
static bool learn_or_observe_peer(const uint8_t *mac, int8_t rssi) {
    if(!peer_known){
        if(!ensure_peer(mac))return false;
        memcpy(peer_mac,mac,6);peer_known=true;
        memcpy(last_remote_mac,mac,6);have_remote=true;
        phy_configured=false;phy_mbps=1;
        remote_rssi=-127;valid_rssi_samples=0;
        rate_hold_until_ms=0;last_phy_change_ms=now_ms();
        /* Keep first-time nearby discovery at the original 1-Mbps rate.
         * An explicit LR recovery probe will select 250 Kbps before replying. */
        (void)set_peer_phy(1);
        if(!save_peer_once(mac) && peer_persisted && memcmp(saved_peer_mac,mac,6)!=0)
            puts("PEER_WARNING|NEW_MAC_NOT_PERSISTED");
        printf("PEER|");print_mac(peer_mac);putchar('\n');
    }
    if(memcmp(mac,peer_mac,6)!=0)return false;
    peer_last_seen_ms=now_ms();
    if(rssi>=-105 && rssi<=-5){
        remote_rssi=(remote_rssi==-127)?rssi:(remote_rssi*7+rssi*3)/10;
        if(valid_rssi_samples<1000000)valid_rssi_samples++;
    }
    return true;
}
/* Rate fallback follows the measured packet delivery result rather than
 * attempting to infer distance from an RSSI number alone. A weak link is
 * progressively stepped down to LR and never forcibly reset to 1 Mbps. */
static void downgrade_on_missing_ack(void) {
    rate_hold_until_ms=now_ms()+PHY_HOLD_MS;
    if(!peer_known)return;
    uint8_t next=og_lr_fallback(phy_mbps,lr_supported);
    if(next!=phy_mbps){
        if(next==50||next==25)lr_fallbacks++;
        (void)set_peer_phy(next);
    }
}
static uint8_t target_phy_for_link(void) {
    if(link_mode==OG_RATE_LR250)return 25;
    if(link_mode==OG_RATE_LR500)return 50;
    if(link_mode==OG_RATE_RANGE)return 1;
    if(remote_rssi==-127 || valid_rssi_samples<3)return phy_mbps;
    return og_lr_target(phy_mbps,remote_rssi,link_mode);
}
static void adjust_phy(void) {
    if(!peer_known)return;
    const int64_t t=now_ms();
    if(t-peer_last_seen_ms>PEER_TIMEOUT_MS){
        /* Do NOT forget the MAC: at distances beyond normal 1 Mbps we need
         * LR unicast to a known peer for reconnect and ACK recovery. */
        rate_hold_until_ms=t+PHY_HOLD_MS;
        if(lr_supported && phy_mbps!=25)(void)set_peer_phy(25);
        return;
    }
    uint8_t desired=target_phy_for_link();
    if(desired==phy_mbps)return;
    const bool upgrading=og_lr_rank(desired)>og_lr_rank(phy_mbps);
    if(t<rate_hold_until_ms && upgrading)return;
    /* Increasing rate requires stable samples, dwell time and empty queue. */
    if(upgrading && (t-last_phy_change_ms<PHY_DWELL_MS || any_pending()))return;
    (void)set_peer_phy(desired);
}
static void send_hello(void) {
    frame_t hello={0};
    hello.magic=PROTOCOL_MAGIC;hello.version=PROTOCOL_VERSION;
    hello.type=TYPE_HELLO;memcpy(hello.sender,own_mac,6);
    hello.session=boot_session;
    /* 1 Mbps discovery remains available for first pairing nearby. */
    if(send_frame(BROADCAST,&hello)==ESP_OK)hello_tx++;
}
static void send_lr_probe(void) {
    if(!lr_supported || !peer_known ||
       (now_ms()-peer_last_seen_ms<LR_PROBE_DELAY_MS && link_mode!=OG_RATE_LR250 && link_mode!=OG_RATE_LR500))return;
    if(now_ms()-last_lr_probe_ms<LR_PROBE_MS)return;
    last_lr_probe_ms=now_ms();
    /* Only set the LR rate when the link is actually stale; manual LR modes
     * were already selected by RATE. Do not change the rate immediately
     * after sending, because the radio transmission is asynchronous. */
    if(link_mode==OG_RATE_LR250)(void)set_peer_phy(25);
    else if(link_mode==OG_RATE_LR500)(void)set_peer_phy(50);
    else if(phy_mbps!=25)(void)set_peer_phy(25);
    frame_t hello={0};hello.magic=PROTOCOL_MAGIC;hello.version=PROTOCOL_VERSION;
    hello.type=TYPE_HELLO;memcpy(hello.sender,own_mac,6);
    hello.session=boot_session;hello.id=LR_HELLO_ID;
    if(send_frame(peer_mac,&hello)==ESP_OK)lr_probe_tx++;
}

/* Radio benchmark uses independent packet types and MCU-level acknowledgments.
 * No encryption key, message ID, browser ACK, or chat history is involved. */
static void radio_bench_finish(void) {
    int64_t elapsed=now_ms()-radio_bench.start_ms;
    if(elapsed<1)elapsed=1;
    printf("RBENCH_DONE|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%s|%ld\n",
        radio_bench.token,radio_bench.count,radio_bench.ok,radio_bench.lost,
        radio_bench.retries,radio_bench.ok*BENCH_PAYLOAD_BYTES,
        (uint32_t)elapsed,(uint32_t)(radio_bench.ok ? radio_bench.sum_rtt_ms / radio_bench.ok : 0),
        peer_known?phy_label():"1",(long)remote_rssi);
    radio_bench.active=false;radio_bench.awaiting=false;
}
static void radio_bench_drive(void) {
    if(!radio_bench.active)return;
    if(radio_bench.index>=radio_bench.count){radio_bench_finish();return;}
    const int64_t now=now_ms();
    if(radio_bench.awaiting && now-radio_bench.packet_started_ms<BENCH_TIMEOUT_MS)return;
    if(radio_bench.awaiting){
        if(radio_bench.tries>=3){
            radio_bench.lost++;radio_bench.index++;radio_bench.awaiting=false;
            radio_bench.tries=0;
            return;
        }
        radio_bench.retries++;
        downgrade_on_missing_ack();
    }
    frame_t f={0};
    f.magic=PROTOCOL_MAGIC;f.version=PROTOCOL_VERSION;
    f.type=TYPE_BENCH_DATA;
    memcpy(f.sender,own_mac,6);
    f.session=radio_bench.token;f.id=radio_bench.index+1;
    f.length=BENCH_PAYLOAD_BYTES;
    for(int i=0;i<BENCH_PAYLOAD_BYTES;i++)f.text[i]=(uint8_t)(i+radio_bench.index);
    /* Use same unicast/rate decision as normal data. After a failed attempt,
       recover with the original broadcast path. */
    const uint8_t *dest=(peer_known && radio_bench.tries==0)?peer_mac:BROADCAST;
    esp_err_t e=send_frame(dest,&f);
    radio_bench.packet_started_ms=now_ms();radio_bench.awaiting=true;
    radio_bench.tries++;
    if(e!=ESP_OK){
        radio_bench.packet_started_ms=now_ms()-BENCH_TIMEOUT_MS;
    }
}
static void usb_output_benchmark(uint32_t token,uint32_t count) {
    uint8_t sample[BENCH_PAYLOAD_BYTES];
    for(int i=0;i<BENCH_PAYLOAD_BYTES;i++)sample[i]=(uint8_t)(i*37+17);
    int64_t started=now_ms();
    for(uint32_t i=0;i<count;i++){
        char prefix[64];
        snprintf(prefix,sizeof(prefix),"USBOUT|%" PRIu32 "|%" PRIu32 "|",token,i);
        emit_hex_record(prefix,sample,sizeof(sample));
        if((i&7)==7)vTaskDelay(1); /* never starve CPU0 idle */
    }
    printf("USBOUT_DONE|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "\n",
           token,count,count*BENCH_PAYLOAD_BYTES,(uint32_t)(now_ms()-started));
}
static received_t *find_history(const uint8_t *mac,uint32_t session,uint32_t id) {
    for(int i=0;i<RX_HISTORY;i++)if(history[i].active&&history[i].session==session&&history[i].id==id&&memcmp(history[i].mac,mac,6)==0)return &history[i];
    return NULL;
}
static void receipt(const received_t *r) {
    frame_t ack={0};ack.magic=PROTOCOL_MAGIC;ack.version=PROTOCOL_VERSION;
    ack.type=TYPE_RECEIPT;memcpy(ack.sender,own_mac,6);
    ack.session=r->session;ack.id=r->id;
    /* At LR distance, 1-Mbps broadcast confirmations cannot reach the peer.
     * Use learned peer unicast at its negotiated PHY instead. */
    (void)send_frame(peer_known?r->mac:BROADCAST,&ack);
}
static void display_rx(const frame_t *f,const uint8_t *mac) {
    char prefix[128];
    snprintf(prefix, sizeof(prefix),
        "RX|%02X:%02X:%02X:%02X:%02X:%02X|%" PRIu32 "|%" PRIu32 "|",
        mac[0],mac[1],mac[2],mac[3],mac[4],mac[5],f->session,f->id);
    emit_hex_record(prefix,f->text,f->length);
}
/* Big-endian 32-bit IDs, deterministic encoding on both ESP32s. */
static uint32_t sack_read32(const uint8_t *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static void sack_write32(uint8_t *p, uint32_t id) {
    p[0]=(uint8_t)(id>>24);p[1]=(uint8_t)(id>>16);
    p[2]=(uint8_t)(id>>8);p[3]=(uint8_t)id;
}
/* Keep this extension independent of the proven single APPACK path. */
static void send_batch_receipt(const event_t *e) {
    if(e->length<5 || e->length>1+4*SACK_MAX_IDS)return;
    frame_t f={0}; f.magic=PROTOCOL_MAGIC;f.version=PROTOCOL_VERSION;
    f.type=TYPE_BATCH_RECEIPT;memcpy(f.sender,own_mac,6);
    f.session=e->session;
    int count=0;
    for(unsigned i=0;i<e->text[0];i++){
        const uint32_t id=sack_read32(&e->text[1+i*4]);
        received_t *r=find_history(e->mac,e->session,id);
        if(!r)continue; /* never acknowledge a frame not received by this ESP32 */
        r->app_acked=true;
        sack_write32(&f.text[1+count*4],id);
        count++;
    }
    if(!count)return;
    f.text[0]=(uint8_t)count;f.length=(uint16_t)(1+4*count);
    /* Batch receipts must also work at LR rates. */
    (void)send_frame(peer_known?e->mac:BROADCAST,&f);
}
static void process_event(const event_t *e) {
    if(e->type==EV_STATUS){show_ready("STATUS");emit_link();return;}
    if(e->type==EV_BENCH_START){
        if(radio_bench.active||any_pending()){
            printf("RBENCH_ERROR|%" PRIu32 "|BUSY\n",e->session);return;
        }
        if(e->id<1||e->id>BENCH_MAX_PACKETS){
            printf("RBENCH_ERROR|%" PRIu32 "|COUNT\n",e->session);return;
        }
        if(!peer_known){
            printf("RBENCH_ERROR|%" PRIu32 "|NO_PEER\n",e->session);return;
        }
        memset(&radio_bench,0,sizeof(radio_bench));
        radio_bench.active=true;radio_bench.token=e->session;
        radio_bench.count=e->id;radio_bench.start_ms=now_ms();
        printf("RBENCH_START|%" PRIu32 "|%" PRIu32 "|%u\n",
            radio_bench.token,radio_bench.count,(unsigned)phy_mbps);
        return;
    }
    if(e->type==EV_RATE){
        if((e->rate_mode==OG_RATE_LR500||e->rate_mode==OG_RATE_LR250)&&!lr_supported){
            puts("ERROR|LR_NOT_SUPPORTED_BY_WIFI_DRIVER");return;
        }
        link_mode=e->rate_mode;
        rate_hold_until_ms=0;
        last_phy_change_ms=now_ms()-PHY_DWELL_MS;
        adjust_phy();
        if(!peer_known)puts("LRINFO|WAITING_FOR_PEER_DISCOVERY");
        emit_link();return;
    }
    if(e->type==EV_SEND) {
        if(radio_bench.active){puts("ERROR|BENCH_BUSY");return;}
        outgoing_t *slot=NULL;
        for(int i=0;i<MAX_PENDING;i++)if(!pending[i].active){slot=&pending[i];break;}
        if(!slot){puts("ERROR|QUEUE_FULL");return;}
        memset(slot,0,sizeof(*slot));slot->active=true;
        frame_t *f=&slot->frame;
        f->magic=PROTOCOL_MAGIC;f->version=PROTOCOL_VERSION;f->type=TYPE_TEXT;
        memcpy(f->sender,own_mac,6);f->session=boot_session;f->id=next_id++;
        f->length=e->length;memcpy(f->text,e->text,e->length);
        char prefix[128];
        snprintf(prefix,sizeof(prefix),"QUEUED|%" PRIu32 "|%" PRIu32 "|",f->session,f->id);
        emit_hex_record(prefix,f->text,f->length);
        return;
    }
    if(e->type==EV_READ) {
        /* Read receipts are separate from delivery ACKs, directed to sender node. */
        if(!ensure_peer(e->mac)){puts("ERROR|READ_PEER");return;}
        frame_t read_frame={0};
        read_frame.magic=PROTOCOL_MAGIC;read_frame.version=PROTOCOL_VERSION;
        read_frame.type=TYPE_READ;memcpy(read_frame.sender,own_mac,6);
        read_frame.session=e->session;read_frame.id=e->id;
        for(int n=0;n<3;n++) {
            if(send_frame(peer_known?e->mac:BROADCAST,&read_frame)!=ESP_OK)puts("ERROR|READ_SEND");
            vTaskDelay(pdMS_TO_TICKS(35));
        }
        return;
    }
    if(e->type==EV_APPACK_BATCH) {send_batch_receipt(e);return;}
    if(e->type==EV_APPACK) {
        received_t *r=find_history(e->mac,e->session,e->id);
        if(!r){puts("ERROR|UNKNOWN_APPACK");return;}
        r->app_acked=true;
        receipt(r);
        return;
    }
    if(e->type!=EV_RX)return;
    const frame_t *f=&e->frame;
    if(!learn_or_observe_peer(e->mac,e->rssi))return;
    if(f->type==TYPE_HELLO){
        hello_rx++;
        if(f->id==LR_HELLO_ID){
            lr_probe_rx++;
            if(link_mode==OG_RATE_AUTO && lr_supported){
                /* An LR recovery probe needs an LR reply, not a 1-Mbps
                 * broadcast that may be below the distant peer's sensitivity. */
                rate_hold_until_ms=now_ms()+PHY_HOLD_MS;
                (void)set_peer_phy(25);
            }
            if(lr_supported){
                frame_t reply={0};
                reply.magic=PROTOCOL_MAGIC;reply.version=PROTOCOL_VERSION;
                reply.type=TYPE_HELLO;memcpy(reply.sender,own_mac,6);
                reply.session=boot_session;reply.id=LR_HELLO_ID+1;
                (void)send_frame(e->mac,&reply);
            }
        }
        return;
    }
    if(f->type==TYPE_BENCH_DATA){
        /* ACK at the remote ESP32, without USB or browser involvement. */
        frame_t ack={0};
        ack.magic=PROTOCOL_MAGIC;ack.version=PROTOCOL_VERSION;
        ack.type=TYPE_BENCH_ACK;
        memcpy(ack.sender,own_mac,6);
        ack.session=f->session;ack.id=f->id;
        (void)send_frame(peer_known?e->mac:BROADCAST,&ack);
        return;
    }
    if(f->type==TYPE_BENCH_ACK){
        if(radio_bench.active&&radio_bench.awaiting&&
           f->session==radio_bench.token&&f->id==radio_bench.index+1){
            uint32_t rtt=(uint32_t)(now_ms()-radio_bench.packet_started_ms);
            radio_bench.ok++;radio_bench.sum_rtt_ms+=rtt;
            radio_bench.index++;radio_bench.awaiting=false;
            radio_bench.tries=0;
        }
        return;
    }
    rx_ok++;
    if(f->type==TYPE_READ) {
        /* Sender browser decides whether this ID belongs to its outgoing chat. */
        ble_printf("READ|%" PRIu32 "|%" PRIu32 "\n",f->session,f->id);
        return;
    }
    if(f->type==TYPE_BATCH_RECEIPT) {
        for(unsigned j=0;j<f->text[0];j++){
            const uint32_t ack_id=sack_read32(&f->text[1+j*4]);
            for(int i=0;i<MAX_PENDING;i++){
                outgoing_t *o=&pending[i];
                if(o->active&&o->frame.session==f->session&&o->frame.id==ack_id){
                    ble_printf("DELIVERED|%" PRIu32 "|%" PRIu32 "\n",f->session,ack_id);
                    o->active=false;delivery_count++;break;
                }
            }
        }
        return;
    }
    if(f->type==TYPE_RECEIPT) {
        for(int i=0;i<MAX_PENDING;i++) {
            outgoing_t *o=&pending[i];
            if(o->active&&o->frame.session==f->session&&o->frame.id==f->id) {
                ble_printf("DELIVERED|%" PRIu32 "|%" PRIu32 "\n",f->session,f->id);
                o->active=false;delivery_count++;break;
            }
        }
        return;
    }
    received_t *r=find_history(e->mac,f->session,f->id);
    if(r) {
        if(r->app_acked)receipt(r);
        else if(now_ms()-r->last_forward_ms>400) {display_rx(f,e->mac);r->last_forward_ms=now_ms();}
        return;
    }
    r=&history[history_next++%RX_HISTORY];memset(r,0,sizeof(*r));
    r->active=true;memcpy(r->mac,e->mac,6);r->session=f->session;r->id=f->id;
    r->last_forward_ms=now_ms();
    display_rx(f,e->mac);
    /* No receipt until receiving browser sends APPACK! */
}
static void dispatcher(void *arg) {
    event_t e;
    while(true) {
        /* FreeRTOS can tick at 100 Hz (10 ms/tick), so pdMS_TO_TICKS(5)
         * alone can truncate to ZERO and spin forever at task priority 5.
         * Adding one tick guarantees a genuine blocking opportunity. */
        if(xQueueReceive(event_queue,&e,pdMS_TO_TICKS(5) + 1)==pdTRUE)process_event(&e);
        int64_t t=now_ms();
        if(t-last_report_ms>=LINK_REPORT_MS){last_report_ms=t;emit_link();}
        if(t-last_hello_ms>=HELLO_MS){last_hello_ms=t;send_hello();}
        adjust_phy();
        send_lr_probe();
        radio_bench_drive();
        /* USB handshake resilience: no dependency on STATUS arriving intact. */
        if(t-last_ready_beacon_ms>=READY_BEACON_MS){
            last_ready_beacon_ms=t;
            show_ready("BEACON");
        }
        for(int i=0;i<MAX_PENDING;i++) {
            outgoing_t *o=&pending[i];if(!o->active)continue;
            if(o->attempts>0 && t-o->last_send_ms<RETRY_MS)continue;
            if(o->attempts>=MAX_ATTEMPTS) {
                ble_printf("FAILED|%" PRIu32 "|%" PRIu32 "|NO_APP_RECEIPT\n",o->frame.session,o->frame.id);
                o->active=false;failure_count++;downgrade_on_missing_ack();continue;
            }
            if(o->attempts>=1){retry_count++;downgrade_on_missing_ack();}
            /* Never switch an LR recovery retry to 1 Mbps broadcast just
             * because earlier ACKs were lost. Persist the known peer MAC. */
            const uint8_t *dest=peer_known?peer_mac:BROADCAST;
            esp_err_t err=send_frame(dest,&o->frame);
            o->last_send_ms=now_ms();o->attempts++;
            if(err==ESP_OK) {
                if(o->attempts==1)ble_printf("SENT|%" PRIu32 "|%" PRIu32 "\n",o->frame.session,o->frame.id);
                else ble_printf("RETRY|%" PRIu32 "|%" PRIu32 "|%d\n",o->frame.session,o->frame.id,o->attempts);
            } else ble_printf("RADIO_ERROR|%" PRIu32 "|%" PRIu32 "|%s\n",o->frame.session,o->frame.id,esp_err_to_name(err));
            break; /* one data packet per iteration to avoid bursts */
        }
        /* Also yield when the queue stays busy, so the CPU0 IDLE task can
         * execute and the Task Watchdog does not repeatedly trigger. */
        vTaskDelay(1);
    }
}
/* Parse no more than 6 noncontiguous original radio IDs in one serial command.
   Works across any interleaved radio traffic; no assumption that IDs are sequential. */
static bool parse_batch_ack(char *line,event_t *e) {
    char *save=NULL;
    char *mac=strtok_r(line," ",&save);
    char *session=strtok_r(NULL," ",&save);
    if(!mac||!session||!parse_mac(mac,e->mac))return false;
    for(const char *x=session;*x;x++)if(!isdigit((unsigned char)*x))return false;
    unsigned long long sess=strtoull(session,NULL,10);
    if(sess>UINT32_MAX)return false;
    e->session=(uint32_t)sess;e->type=EV_APPACK_BATCH;
    uint8_t count=0;
    char *token=NULL;
    while((token=strtok_r(NULL," ",&save))!=NULL){
        if(count>=SACK_MAX_IDS||!token[0])return false;
        for(const char *x=token;*x;x++)if(!isdigit((unsigned char)*x))return false;
        unsigned long long id=strtoull(token,NULL,10);
        if(id>UINT32_MAX)return false;
        sack_write32(&e->text[1+count*4],(uint32_t)id);
        count++;
    }
    if(count==0)return false;
    e->text[0]=count;e->length=(uint16_t)(1+4*count);
    return true;
}
/* Authenticated BLE uses a separate command consumer. USB parser and the
 * original ESP-NOW radio dispatcher are kept unchanged. */
static void ble_commands(void *arg){
 (void)arg;char line[SERIAL_LINE];
 for(;;){
  if(!offgrid_ble_receive_line(line,sizeof(line))){/* A single tick avoids 20-30ms inbox polling latency on 100Hz FreeRTOS. */
      vTaskDelay(1);continue;}
  event_t e={0};bool send=false;
  if(!strcmp(line,"STATUS")){e.type=EV_STATUS;send=true;}
  else if(!strncmp(line,"MSGHEX ",7)){e.type=EV_SEND;if(read_hex(line+7,e.text,&e.length))send=true;else offgrid_ble_publish("ERROR|BAD_HEX");}
  else if(!strncmp(line,"APPACKB ",8)){if(parse_batch_ack(line+8,&e))send=true;else offgrid_ble_publish("ERROR|BAD_BATCH_ACK");}
  else if(!strncmp(line,"APPACK ",7)||!strncmp(line,"READ ",5)){
   bool isread=line[0]=='R';char macstr[32],extra;unsigned long ses,id;
   if(sscanf(line+(isread?5:7),"%31s %lu %lu %c",macstr,&ses,&id,&extra)==3&&
      ses<=UINT32_MAX&&id<=UINT32_MAX&&parse_mac(macstr,e.mac)){
      e.type=isread?EV_READ:EV_APPACK;e.session=ses;e.id=id;send=true;
   }else offgrid_ble_publish("ERROR|BAD_ACK");
  }
  if(send&&xQueueSend(event_queue,&e,pdMS_TO_TICKS(60)+1)!=pdTRUE)offgrid_ble_publish("ERROR|RADIO_BUSY");
 }
}
static void serial_task(void *arg) {
    char line[SERIAL_LINE];size_t used=0;
    uint8_t rx_batch[256];
    puts("HELP|MSGHEX, STATUS, APPACK, APPACKB, READ, KEYGET, KEYSET, RATE AUTO/RANGE/SPEED/LR500/LR250, USBOUT, USBIN_BEGIN/USBIN/USBIN_END, RBENCH");
    while (true) {
        int count=serial_receive_batch(rx_batch,sizeof(rx_batch));
        if (count<=0)continue;
        for (int pos=0;pos<count;pos++) {
            int ch=rx_batch[pos];
            if(ch=='\r')continue;
        if(ch=='\n') {
            line[used]=0;event_t e={0};bool send=false;
            if(strncmp(line,"USBOUT ",7)==0){
                unsigned long token=0,count=0;char extra;
                if(sscanf(line+7,"%lu %lu %c",&token,&count,&extra)==2&&
                   token<=UINT32_MAX&&count>=1&&count<=128 && !radio_bench.active)
                    usb_output_benchmark((uint32_t)token,(uint32_t)count);
                else puts("ERROR|BAD_USBOUT");
            }
            else if(strncmp(line,"USBIN_BEGIN ",12)==0){
                unsigned long token=0;char extra;
                if(sscanf(line+12,"%lu %c",&token,&extra)==1&&token<=UINT32_MAX){
                    memset(&usb_input_bench,0,sizeof(usb_input_bench));
                    usb_input_bench.active=true;usb_input_bench.token=(uint32_t)token;
                    usb_input_bench.started_ms=now_ms();
                }else puts("ERROR|BAD_USBIN_BEGIN");
            }
            else if(strncmp(line,"USBIN ",6)==0){
                unsigned long token=0,seq=0;char hex[MAX_TEXT*2+2]={0},extra;
                uint8_t data[MAX_TEXT];uint16_t n=0;
                if(sscanf(line+6,"%lu %lu %361s %c",&token,&seq,hex,&extra)==3&&
                   usb_input_bench.active&&token==usb_input_bench.token&&
                   seq==usb_input_bench.expected_seq &&
                   read_hex(hex,data,&n)&&n==BENCH_PAYLOAD_BYTES){
                    usb_input_bench.bytes+=n;
                    usb_input_bench.count++;
                    usb_input_bench.expected_seq++;
                }else puts("ERROR|BAD_USBIN_PACKET");
            }
            else if(strncmp(line,"USBIN_END ",10)==0){
                unsigned long token=0;char extra;
                if(sscanf(line+10,"%lu %c",&token,&extra)==1&&
                   usb_input_bench.active&&token==usb_input_bench.token){
                   printf("USBIN_DONE|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "|%" PRIu32 "\n",
                     usb_input_bench.token,usb_input_bench.count,usb_input_bench.bytes,
                     (uint32_t)(now_ms()-usb_input_bench.started_ms));
                   usb_input_bench.active=false;
                }else puts("ERROR|BAD_USBIN_END");
            }
            else if(strncmp(line,"RBENCH ",7)==0){
                unsigned long token=0,count=0;char extra;
                if(sscanf(line+7,"%lu %lu %c",&token,&count,&extra)==2&&
                   token<=UINT32_MAX&&count>0&&count<=BENCH_MAX_PACKETS){
                    e.type=EV_BENCH_START;e.session=(uint32_t)token;
                    e.id=(uint32_t)count;send=true;
                }else puts("ERROR|BAD_RBENCH");
            }
            else if(strcmp(line,"KEYGET")==0){print_key();}
            else if(strcmp(line,"KEYSTATUS")==0){puts(pairing_key_present?"KEYSTATUS|PRESENT":"KEYSTATUS|EMPTY");}
            else if(strncmp(line,"KEYSET ",7)==0){
                uint8_t raw[32];uint16_t n=0;
                if(strlen(line+7)!=64||!read_hex(line+7,raw,&n)||n!=32)puts("KEYERROR|INVALID_HEX");
                else if(key_to_nvs(raw))puts("KEYSTORED");
                else puts("KEYERROR|NVS_WRITE_FAILED");
                memset(raw,0,sizeof(raw));
            }
            else if(strncmp(line,"RATE ",5)==0){
                if(strcmp(line+5,"AUTO")==0||strcmp(line+5,"RANGE")==0||strcmp(line+5,"SPEED")==0||
                   strcmp(line+5,"LR500")==0||strcmp(line+5,"LR250")==0){
                    e.type=EV_RATE;
                    e.rate_mode=!strcmp(line+5,"LR250")?OG_RATE_LR250:
                        !strcmp(line+5,"LR500")?OG_RATE_LR500:
                        !strcmp(line+5,"RANGE")?OG_RATE_RANGE:
                        (!strcmp(line+5,"SPEED")?OG_RATE_SPEED:OG_RATE_AUTO);
                    send=true;
                }else puts("ERROR|BAD_RATE_MODE");
            }
            else if(strncmp(line,"MSGHEX ",7)==0) {
                e.type=EV_SEND;
                if(read_hex(line+7,e.text,&e.length))send=true;
                else puts("ERROR|INVALID_HEX_OR_LENGTH");
            }else if(strncmp(line,"MSG ",4)==0&&line[4]) {
                size_t n=strlen(line+4);
                if(n<=MAX_TEXT){e.type=EV_SEND;e.length=n;memcpy(e.text,line+4,n);send=true;}
                else puts("ERROR|MESSAGE_TOO_LONG");
            }else if(strncmp(line,"APPACKB ",8)==0) {
                if(parse_batch_ack(line+8,&e))send=true;
                else puts("ERROR|BAD_APPACKB");
            }else if(strncmp(line,"APPACK ",7)==0) {
                char mac_str[32],extra;
                unsigned long session,id;
                if(sscanf(line+7,"%31s %lu %lu %c",mac_str,&session,&id,&extra)==3&&
                   session<=UINT32_MAX&&id<=UINT32_MAX&&parse_mac(mac_str,e.mac)) {
                    e.type=EV_APPACK;e.session=(uint32_t)session;e.id=(uint32_t)id;send=true;
                }else puts("ERROR|BAD_APPACK");
            }else if(strncmp(line,"READ ",5)==0) {
                char mac_str[32],extra;unsigned long session,id;
                if(sscanf(line+5,"%31s %lu %lu %c",mac_str,&session,&id,&extra)==3&&
                   session<=UINT32_MAX&&id<=UINT32_MAX&&parse_mac(mac_str,e.mac)) {
                    e.type=EV_READ;e.session=(uint32_t)session;e.id=(uint32_t)id;send=true;
                }else puts("ERROR|BAD_READ");
            }else if(strcmp(line,"STATUS")==0) {e.type=EV_STATUS;send=true;}
            else if(strcmp(line,"HELP")==0)puts("HELP|MSGHEX <UTF8-HEX>, MSG <text>, STATUS, APPACK <MAC> <SESSION> <ID>, READ <MAC> <SESSION> <ID>");
            else if(used)puts("ERROR|UNKNOWN_COMMAND");
            if(send&&!xQueueSend(event_queue,&e,pdMS_TO_TICKS(100)))puts("ERROR|EVENT_QUEUE_FULL");
            used=0;
        }else if(used<sizeof(line)-1)line[used++]=(char)ch;
        else {used=0;puts("ERROR|LINE_TOO_LONG");}
        } /* every byte in this USB receive batch */
    }
}
void app_main(void) {
    /* Line buffering batches complete protocol records but flushes on newline.
     * This avoids byte-by-byte USB VFS writes while retaining immediate READY. */
    setvbuf(stdout,NULL,_IOLBF,0);
#if CONFIG_OFFGRID_USB_IRQ_RX
    setup_usb_irq_rx();
#else
    puts("USBMODE|LEGACY|BUILD_SETTING");
#endif
    puts("BOOT|OffGrid Messenger Phase 1.4.11 experimental LR / USB IRQ / NVS / ACK");
    esp_err_t e=nvs_flash_init();
    if(e==ESP_ERR_NVS_NO_FREE_PAGES||e==ESP_ERR_NVS_NEW_VERSION_FOUND){ESP_ERROR_CHECK(nvs_flash_erase());e=nvs_flash_init();}
    ESP_ERROR_CHECK(e);
    pairing_key_present=key_from_nvs();
    peer_persisted=read_saved_peer();
    ESP_ERROR_CHECK(esp_netif_init());ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg=WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_channel(CHANNEL,WIFI_SECOND_CHAN_NONE));
    /* Official ESP-IDF ESP-NOW LR example enables both standard B/G/N and
     * Espressif LR to preserve close-range interoperability. */
    esp_err_t lr_err=esp_wifi_set_protocol(WIFI_IF_STA,
        WIFI_PROTOCOL_11B | WIFI_PROTOCOL_11G | WIFI_PROTOCOL_11N | WIFI_PROTOCOL_LR);
    if(lr_err==ESP_OK){lr_supported=true;puts("LRMODE|ENABLED|BGNLR");}
    else{lr_supported=false;printf("LRMODE|UNAVAILABLE|%s\n",esp_err_to_name(lr_err));}
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_read_mac(own_mac,ESP_MAC_WIFI_STA));
    boot_session=esp_random();if(!boot_session)boot_session=1;
    event_queue=xQueueCreate(EVENT_QUEUE_LEN,sizeof(event_t));if(!event_queue)abort();
    ESP_ERROR_CHECK(esp_now_init());
    ESP_ERROR_CHECK(esp_now_register_recv_cb(on_receive));
    if(!ensure_peer(BROADCAST))abort();
    if(peer_persisted && memcmp(saved_peer_mac,own_mac,6)!=0){
        if(ensure_peer(saved_peer_mac)){
            memcpy(peer_mac,saved_peer_mac,6);peer_known=true;
            memcpy(last_remote_mac,saved_peer_mac,6);have_remote=true;
            peer_last_seen_ms=now_ms()-PEER_TIMEOUT_MS;
            (void)set_peer_phy(lr_supported?25:1);
            puts("LRPEER|RESTORED_FROM_NVS");
        }
    }
    offgrid_ble_init(own_mac,pairing_key,pairing_key_present);
    show_ready("BOOT");
    /* Make resource failures explicit instead of silently losing USB input. */
    if(xTaskCreate(dispatcher,"dispatch",6144,NULL,5,NULL)!=pdPASS){
        puts("FATAL|DISPATCH_TASK_CREATION_FAILED");abort();
    }
    if(xTaskCreate(ble_commands,"ble_commands",4096,NULL,4,NULL)!=pdPASS){puts("FATAL|BLE_TASK");abort();}
    if(xTaskCreate(serial_task,"serial",4096,NULL,4,NULL)!=pdPASS){
        puts("FATAL|SERIAL_TASK_CREATION_FAILED");abort();
    }
}
