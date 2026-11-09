#pragma once
#include <stdint.h>
#include <stddef.h>
#include "ble_uuid.h"
#include "os/os_mbuf.h"
#define BLE_HS_CONN_HANDLE_NONE 65535
#define BLE_GATT_ACCESS_OP_WRITE_CHR 1
#define BLE_ATT_ERR_WRITE_NOT_PERMITTED 3
#define BLE_ATT_ERR_INSUFFICIENT_AUTHEN 5
#define BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN 13
#define BLE_ATT_ERR_UNLIKELY 14
#define BLE_ATT_ERR_READ_NOT_PERMITTED 2
#define BLE_ATT_ERR_INSUFFICIENT_RES 17
#define BLE_GATT_SVC_TYPE_PRIMARY 0
#define BLE_GATT_CHR_F_WRITE 1
#define BLE_GATT_CHR_F_NOTIFY 2
#define BLE_GAP_EVENT_CONNECT 1
#define BLE_GAP_EVENT_DISCONNECT 2
#define BLE_GAP_EVENT_ADV_COMPLETE 3
#define BLE_GAP_EVENT_SUBSCRIBE 4
#define BLE_GAP_EVENT_MTU 5
#define BLE_HS_ADV_F_DISC_GEN 1
#define BLE_HS_ADV_F_BREDR_UNSUP 2
#define BLE_GAP_CONN_MODE_UND 1
#define BLE_GAP_DISC_MODE_GEN 2
#define BLE_HS_FOREVER 0
struct ble_gatt_access_ctxt {int op;struct os_mbuf *om;};
typedef int (*ble_gatt_callback)(uint16_t,uint16_t,struct ble_gatt_access_ctxt *,void*);
struct ble_gatt_chr_def {const ble_uuid_t *uuid;ble_gatt_callback access_cb;uint16_t *val_handle;int flags;};
struct ble_gatt_svc_def {int type;const ble_uuid_t *uuid;const struct ble_gatt_chr_def *characteristics;};
struct ble_gap_event {
  int type;
  struct {int status;uint16_t conn_handle;} connect;
  struct {uint16_t attr_handle;int cur_notify;} subscribe;
  struct {uint16_t value;} mtu;
};
struct ble_hs_adv_fields {int flags;const uint8_t *name;size_t name_len;int name_is_complete;};
struct ble_gap_adv_params {int conn_mode;int disc_mode;};
struct {void (*sync_cb)(void);} extern ble_hs_cfg;
