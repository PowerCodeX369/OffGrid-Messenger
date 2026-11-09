/* NimBLE authenticated GATT transport added to unchanged OGM2 radio.
 * Application content remains end-to-end AES-256-GCM (OGM4), and the NVS
 * secret is never returned over BLE. Physical USB retains its commands. */
#include "ble_bridge.h"
#include <string.h>
#include <stdio.h>
#include "esp_err.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "os/os_mbuf.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "ble_auth_psa.h"  /* PSA HMAC-SHA256, for Mbed TLS 4 in ESP-IDF 6.1. */
#define MAX_LINE 512
static const ble_uuid128_t svc=BLE_UUID128_INIT(0x01,0x0b,0x03,0x26,0x80,0x4e,0x41,0xb6,0xba,0x48,0xd5,0x7b,0x04,0xd9,0x22,0x7b);
static const ble_uuid128_t write_uuid=BLE_UUID128_INIT(0x02,0x0b,0x03,0x26,0x80,0x4e,0x41,0xb6,0xba,0x48,0xd5,0x7b,0x04,0xd9,0x22,0x7b);
static const ble_uuid128_t notify_uuid=BLE_UUID128_INIT(0x03,0x0b,0x03,0x26,0x80,0x4e,0x41,0xb6,0xba,0x48,0xd5,0x7b,0x04,0xd9,0x22,0x7b);
static uint16_t notify_handle=0,conn_handle=BLE_HS_CONN_HANDLE_NONE,att_mtu=23;
static uint8_t addr_type,key_buf[32],nonce[16];
static bool subscribed=false,auth=false,key_present=false;
static int failures=0;
static char name[24],partial[MAX_LINE];static size_t partial_n=0;
typedef struct{char text[MAX_LINE];} line_t;
static QueueHandle_t inbox=NULL,outbox=NULL;
static const char DOMAIN[]="OffGridBLE2";
static void challenge_new(void);
static void ascii_hex(const uint8_t *src,size_t len,char *out){
 static const char *abc="0123456789ABCDEF";
 for(size_t i=0;i<len;i++){out[2*i]=abc[src[i]>>4];out[2*i+1]=abc[src[i]&15];}out[2*len]=0;
}
static int half_hex(char c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return -1;}
static bool read_proof(const char *s,uint8_t result[32]){
 if(strlen(s)!=64)return false;
 for(int i=0;i<32;i++){int a=half_hex(s[i*2]),b=half_hex(s[i*2+1]);if(a<0||b<0)return false;result[i]=(uint8_t)((a<<4)|b);}
 return true;
}
static void push(const char *s,bool secured){
 if(!outbox||!subscribed||(secured&&!auth))return;
 size_t n=strnlen(s,MAX_LINE-2);if(n==0||n>=MAX_LINE-2)return;
 line_t line={0};memcpy(line.text,s,n);
 if(line.text[n-1]!='\n'){line.text[n++]='\n';line.text[n]=0;}
 (void)xQueueSend(outbox,&line,0);
}
void offgrid_ble_publish(const char *s){push(s,true);}
void offgrid_ble_set_key(const uint8_t key[32],bool present){
 auth=false;key_present=present;memset(key_buf,0,32);
 if(present)memcpy(key_buf,key,32);
 if(subscribed)challenge_new();
}
static void challenge_new(void){
 esp_fill_random(nonce,16);failures=0;auth=false;partial_n=0;
 char proof[33],line[48];ascii_hex(nonce,16,proof);snprintf(line,sizeof(line),"CHAL|%s",proof);push(line,false);
}
static bool allow(const char *s){
 return !strcmp(s,"STATUS")||!strncmp(s,"MSGHEX ",7)||!strncmp(s,"APPACK ",7)||!strncmp(s,"APPACKB ",8)||!strncmp(s,"READ ",5);
}
static void complete_line(char *line){
 if(!strcmp(line,"AUTH?")){challenge_new();return;}
 if(!strncmp(line,"AUTH|",5)){
  if(!key_present){push("AUTH_ERR|NO_KEY",false);return;}
  uint8_t provided[32]={0},expected[32]={0};
  uint8_t data[sizeof(DOMAIN)-1+16];
  memcpy(data,DOMAIN,sizeof(DOMAIN)-1);memcpy(data+sizeof(DOMAIN)-1,nonce,16);
  bool valid = read_proof(line + 5, provided) &&
               offgrid_ble_hmac_sha256(key_buf, data, sizeof(data), expected);
  unsigned diff=0;for(int i=0;i<32;i++)diff|=(unsigned)(provided[i]^expected[i]);
  memset(provided,0,32);memset(expected,0,32);
  if(valid&&diff==0){auth=true;failures=0;push("AUTH_OK",false);}
  else {push("AUTH_ERR|BAD_PROOF",false);if(++failures>=3)ble_gap_terminate(conn_handle,0x13);}
  return;
 }
 if(!auth){push("AUTH_REQUIRED",false);return;}
 if(!allow(line)){push("ERROR|BLE_DENIED",false);return;}
 line_t item={0};strncpy(item.text,line,MAX_LINE-1);
 if(xQueueSend(inbox,&item,0)!=pdTRUE)push("ERROR|BLE_BACKPRESSURE",false);
}
static int access_cb(uint16_t conn,uint16_t handle,struct ble_gatt_access_ctxt *ctx,void *arg){
 (void)handle;(void)arg;
 if(ctx->op!=BLE_GATT_ACCESS_OP_WRITE_CHR)return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
 if(conn!=conn_handle)return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
 uint16_t n=OS_MBUF_PKTLEN(ctx->om);if(n<1||n>220)return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
 uint8_t data[220];uint16_t copied=0;
 if(ble_hs_mbuf_to_flat(ctx->om,data,sizeof(data),&copied)!=0)return BLE_ATT_ERR_UNLIKELY;
 for(unsigned i=0;i<copied;i++){
  unsigned char ch=data[i];if(ch=='\r')continue;
  if(ch=='\n'){partial[partial_n]=0;if(partial_n)complete_line(partial);partial_n=0;}
  else if(ch>=32&&ch<=126&&partial_n<MAX_LINE-2)partial[partial_n++]=(char)ch;
  else{partial_n=0;return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;}
 }
 return 0;
}
static int notify_access(uint16_t c,uint16_t a,struct ble_gatt_access_ctxt *ctx,void *arg){(void)c;(void)a;(void)ctx;(void)arg;return BLE_ATT_ERR_READ_NOT_PERMITTED;}
static const struct ble_gatt_svc_def definitions[]={
 {.type=BLE_GATT_SVC_TYPE_PRIMARY,.uuid=&svc.u,.characteristics=(struct ble_gatt_chr_def[]){
  {.uuid=&write_uuid.u,.access_cb=access_cb,.flags=BLE_GATT_CHR_F_WRITE},
  {.uuid=&notify_uuid.u,.access_cb=notify_access,.val_handle=&notify_handle,.flags=BLE_GATT_CHR_F_NOTIFY},{0}}},{0}};
static void advertise(void);
static int gap_cb(struct ble_gap_event *event,void *arg){
 (void)arg;
 switch(event->type){
  case BLE_GAP_EVENT_CONNECT: {
   if (event->connect.status == 0) {
    conn_handle = event->connect.conn_handle;
    att_mtu = 23;
    subscribed = false;
    auth = false;
    partial_n = 0;
   } else {
    advertise();
   }
   break;
  }
  case BLE_GAP_EVENT_DISCONNECT: {
   conn_handle = BLE_HS_CONN_HANDLE_NONE;
   subscribed = false;
   auth = false;
   partial_n = 0;
   advertise();
   break;
  }
  case BLE_GAP_EVENT_ADV_COMPLETE:
   advertise();
   break;
  case BLE_GAP_EVENT_SUBSCRIBE: {
   if (event->subscribe.attr_handle == notify_handle) {
    subscribed = (event->subscribe.cur_notify != 0);
    if (subscribed) challenge_new();
   }
   break;
  }
  case BLE_GAP_EVENT_MTU:
   att_mtu = event->mtu.value;
   break;
  default:break;
 }
 return 0;
}
static void advertise(void){
 struct ble_hs_adv_fields fields={0};
 fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;
 fields.name=(const uint8_t*)name;fields.name_len=strlen(name);fields.name_is_complete=1;
 if(ble_gap_adv_set_fields(&fields))return;
 struct ble_gap_adv_params adv={0};adv.conn_mode=BLE_GAP_CONN_MODE_UND;adv.disc_mode=BLE_GAP_DISC_MODE_GEN;
 (void)ble_gap_adv_start(addr_type,NULL,BLE_HS_FOREVER,&adv,gap_cb,NULL);
}
static void synced(void){if(ble_hs_id_infer_auto(0,&addr_type)==0)advertise();}
static void host(void *arg){(void)arg;nimble_port_run();nimble_port_freertos_deinit();}
static void sender(void *arg){
 (void)arg;line_t line;
 for(;;){
  if(xQueueReceive(outbox,&line,portMAX_DELAY)!=pdTRUE)continue;
  size_t total=strnlen(line.text,MAX_LINE),done=0;
  while(done<total && conn_handle!=BLE_HS_CONN_HANDLE_NONE && subscribed){
   uint16_t capacity=att_mtu>3?att_mtu-3:20;
   if(capacity>180)capacity=180;
   uint16_t count=(uint16_t)((total-done)<capacity?(total-done):capacity);
   struct os_mbuf *m=ble_hs_mbuf_from_flat(line.text+done,count);
   if(!m||ble_gatts_notify_custom(conn_handle,notify_handle,m))break;
   done+=count;vTaskDelay(pdMS_TO_TICKS(13)+1);
  }
 }
}
bool offgrid_ble_receive_line(char *out,size_t capacity){
 if(!inbox||!out||capacity<MAX_LINE)return false;
 line_t item;if(xQueueReceive(inbox,&item,0)!=pdTRUE)return false;
 memcpy(out,item.text,MAX_LINE);return true;
}
void offgrid_ble_init(const uint8_t mac[6],const uint8_t key[32],bool present){
 snprintf(name,sizeof(name),"OffGrid-%02X%02X",mac[4],mac[5]);
 key_present=present;if(present)memcpy(key_buf,key,32);
 /* PSA Crypto must be initialized before importing a transient HMAC key. */
 psa_status_t psa_status = psa_crypto_init();
 if (psa_status != PSA_SUCCESS) {
  ESP_LOGE("offgrid-ble","PSA crypto initialization failed: %ld",(long)psa_status);
  return;
 }
 inbox=xQueueCreate(16,sizeof(line_t));outbox=xQueueCreate(32,sizeof(line_t));
 if(!inbox||!outbox){ESP_LOGE("offgrid-ble","No BLE queues");return;}
 if(nimble_port_init()!=ESP_OK){ESP_LOGE("offgrid-ble","NimBLE init failed");return;}
 ble_svc_gap_init();ble_svc_gatt_init();ble_svc_gap_device_name_set(name);
 if(ble_gatts_count_cfg(definitions)||ble_gatts_add_svcs(definitions)){ESP_LOGE("offgrid-ble","GATT setup failed");return;}
 ble_hs_cfg.sync_cb=synced;
 if(xTaskCreate(sender,"ble_notify",4096,NULL,3,NULL)!=pdPASS)return;
 nimble_port_freertos_init(host);
 ESP_LOGI("offgrid-ble","Advertising %s",name);
}
