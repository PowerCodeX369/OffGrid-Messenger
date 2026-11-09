/* OffGrid Messenger Phase 1.2: ESP32-C3 ESP-IDF 5.x
 * Same firmware on both boards. Educational unencrypted transport.
 * USB browser acknowledgment determines DELIVERED; not merely radio send success.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
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

#define CHANNEL 6
#define MAX_TEXT 180
#define MAX_PENDING 8
#define RX_HISTORY 24
#define MAX_ATTEMPTS 7
#define RETRY_MS 850
#define SEND_GAP_MS 65
#define PROTOCOL_MAGIC 0x4F474D32u /* OGM2; intentionally incompatible with Phase 1 */
#define PROTOCOL_VERSION 2
#define TYPE_TEXT 1
#define TYPE_RECEIPT 2
#define TYPE_READ 3
#define EVENT_QUEUE_LEN 32
#define SERIAL_LINE 400

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

typedef enum { EV_RX = 1, EV_SEND, EV_APPACK, EV_STATUS, EV_READ } event_type_t;
typedef struct {
    event_type_t type;
    uint8_t mac[6];
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

static int64_t now_ms(void) { return esp_timer_get_time() / 1000; }
static void print_mac(const uint8_t *m) {
    printf("%02X:%02X:%02X:%02X:%02X:%02X",m[0],m[1],m[2],m[3],m[4],m[5]);
}
static void print_hex(const uint8_t *p, size_t n) {
    static const char h[]="0123456789ABCDEF";
    for (size_t i=0;i<n;i++) { putchar(h[p[i]>>4]); putchar(h[p[i]&15]); }
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
static void show_ready(void) {
    printf("READY|");print_mac(own_mac);
    printf("|CHANNEL=%d|SESSION=%" PRIu32 "|PROTO=2\n",CHANNEL,boot_session);
}
static void on_receive(const esp_now_recv_info_t *info,const uint8_t *data,int len) {
    const size_t base=offsetof(frame_t,text);
    if(!info||!info->src_addr||!data||len<(int)base||len>(int)sizeof(frame_t))return;
    event_t ev={0};ev.type=EV_RX;
    memcpy(ev.mac,info->src_addr,6);
    memcpy(&ev.frame,data,(size_t)len);
    frame_t *f=&ev.frame;
    if(f->magic!=PROTOCOL_MAGIC||f->version!=PROTOCOL_VERSION)return;
    if(f->length>MAX_TEXT||(size_t)len!=base+f->length)return;
    if(f->type!=TYPE_TEXT&&f->type!=TYPE_RECEIPT&&f->type!=TYPE_READ)return;
    if((f->type==TYPE_RECEIPT||f->type==TYPE_READ)&&f->length!=0)return;
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
    return err;
}
static received_t *find_history(const uint8_t *mac,uint32_t session,uint32_t id) {
    for(int i=0;i<RX_HISTORY;i++)if(history[i].active&&history[i].session==session&&history[i].id==id&&memcmp(history[i].mac,mac,6)==0)return &history[i];
    return NULL;
}
static void receipt(const received_t *r) {
    if(!ensure_peer(r->mac))return;
    frame_t ack={0};ack.magic=PROTOCOL_MAGIC;ack.version=PROTOCOL_VERSION;
    ack.type=TYPE_RECEIPT;memcpy(ack.sender,own_mac,6);
    ack.session=r->session;ack.id=r->id;
    (void)send_frame(r->mac,&ack); /* sender retries if receipt is lost */
}
static void display_rx(const frame_t *f,const uint8_t *mac) {
    printf("RX|");print_mac(mac);
    printf("|%" PRIu32 "|%" PRIu32 "|",f->session,f->id);
    print_hex(f->text,f->length);putchar('\n');
}
static void process_event(const event_t *e) {
    if(e->type==EV_STATUS){show_ready();return;}
    if(e->type==EV_SEND) {
        outgoing_t *slot=NULL;
        for(int i=0;i<MAX_PENDING;i++)if(!pending[i].active){slot=&pending[i];break;}
        if(!slot){puts("ERROR|QUEUE_FULL");return;}
        memset(slot,0,sizeof(*slot));slot->active=true;
        frame_t *f=&slot->frame;
        f->magic=PROTOCOL_MAGIC;f->version=PROTOCOL_VERSION;f->type=TYPE_TEXT;
        memcpy(f->sender,own_mac,6);f->session=boot_session;f->id=next_id++;
        f->length=e->length;memcpy(f->text,e->text,e->length);
        printf("QUEUED|%" PRIu32 "|%" PRIu32 "|",f->session,f->id);
        print_hex(f->text,f->length);putchar('\n');
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
            if(send_frame(e->mac,&read_frame)!=ESP_OK)puts("ERROR|READ_SEND");
            vTaskDelay(pdMS_TO_TICKS(35));
        }
        return;
    }
    if(e->type==EV_APPACK) {
        received_t *r=find_history(e->mac,e->session,e->id);
        if(!r){puts("ERROR|UNKNOWN_APPACK");return;}
        r->app_acked=true;
        receipt(r);
        return;
    }
    if(e->type!=EV_RX)return;
    const frame_t *f=&e->frame;
    if(f->type==TYPE_READ) {
        /* Sender browser decides whether this ID belongs to its outgoing chat. */
        printf("READ|%" PRIu32 "|%" PRIu32 "\n",f->session,f->id);
        return;
    }
    if(f->type==TYPE_RECEIPT) {
        for(int i=0;i<MAX_PENDING;i++) {
            outgoing_t *o=&pending[i];
            if(o->active&&o->frame.session==f->session&&o->frame.id==f->id) {
                printf("DELIVERED|%" PRIu32 "|%" PRIu32 "\n",f->session,f->id);
                o->active=false;break;
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
        if(xQueueReceive(event_queue,&e,pdMS_TO_TICKS(30))==pdTRUE)process_event(&e);
        int64_t t=now_ms();
        for(int i=0;i<MAX_PENDING;i++) {
            outgoing_t *o=&pending[i];if(!o->active)continue;
            if(o->attempts>0 && t-o->last_send_ms<RETRY_MS)continue;
            if(o->attempts>=MAX_ATTEMPTS) {
                printf("FAILED|%" PRIu32 "|%" PRIu32 "|NO_APP_RECEIPT\n",o->frame.session,o->frame.id);
                o->active=false;continue;
            }
            esp_err_t err=send_frame(BROADCAST,&o->frame);
            o->last_send_ms=now_ms();o->attempts++;
            if(err==ESP_OK) {
                if(o->attempts==1)printf("SENT|%" PRIu32 "|%" PRIu32 "\n",o->frame.session,o->frame.id);
                else printf("RETRY|%" PRIu32 "|%" PRIu32 "|%d\n",o->frame.session,o->frame.id,o->attempts);
            } else printf("RADIO_ERROR|%" PRIu32 "|%" PRIu32 "|%s\n",o->frame.session,o->frame.id,esp_err_to_name(err));
            break; /* one data packet per iteration to avoid bursts */
        }
    }
}
static void serial_task(void *arg) {
    char line[SERIAL_LINE];size_t used=0;
    puts("HELP|MSGHEX <UTF8-HEX>, STATUS, HELP, APPACK <MAC> <SESSION> <ID>, READ <MAC> <SESSION> <ID>");
    while(true) {
        int ch=getchar();
        if(ch==EOF){clearerr(stdin);vTaskDelay(pdMS_TO_TICKS(20));continue;}
        if(ch=='\r')continue;
        if(ch=='\n') {
            line[used]=0;event_t e={0};bool send=false;
            if(strncmp(line,"MSGHEX ",7)==0) {
                e.type=EV_SEND;
                if(read_hex(line+7,e.text,&e.length))send=true;
                else puts("ERROR|INVALID_HEX_OR_LENGTH");
            }else if(strncmp(line,"MSG ",4)==0&&line[4]) {
                size_t n=strlen(line+4);
                if(n<=MAX_TEXT){e.type=EV_SEND;e.length=n;memcpy(e.text,line+4,n);send=true;}
                else puts("ERROR|MESSAGE_TOO_LONG");
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
    }
}
void app_main(void) {
    setvbuf(stdout,NULL,_IONBF,0);
    puts("BOOT|OffGrid Messenger Phase 1.2 / protocol 2");
    esp_err_t e=nvs_flash_init();
    if(e==ESP_ERR_NVS_NO_FREE_PAGES||e==ESP_ERR_NVS_NEW_VERSION_FOUND){ESP_ERROR_CHECK(nvs_flash_erase());e=nvs_flash_init();}
    ESP_ERROR_CHECK(e);
    ESP_ERROR_CHECK(esp_netif_init());ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg=WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_channel(CHANNEL,WIFI_SECOND_CHAN_NONE));
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_read_mac(own_mac,ESP_MAC_WIFI_STA));
    boot_session=esp_random();if(!boot_session)boot_session=1;
    event_queue=xQueueCreate(EVENT_QUEUE_LEN,sizeof(event_t));if(!event_queue)abort();
    ESP_ERROR_CHECK(esp_now_init());
    ESP_ERROR_CHECK(esp_now_register_recv_cb(on_receive));
    if(!ensure_peer(BROADCAST))abort();
    show_ready();
    xTaskCreate(dispatcher,"dispatch",6144,NULL,5,NULL);
    xTaskCreate(serial_task,"serial",4096,NULL,4,NULL);
}
