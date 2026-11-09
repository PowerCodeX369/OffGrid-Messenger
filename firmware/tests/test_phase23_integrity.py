#!/usr/bin/env python3
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[2]
manifest_path=root/'PRESERVATION_23.json'
checked=0
if manifest_path.is_file():
    m=json.loads(manifest_path.read_text())
    for rel,h in m['unchanged_sha256'].items():
        file=root/rel
        assert file.exists() and hashlib.sha256(file.read_bytes()).hexdigest()==h, f'Unexpected edit/removal: {rel}'
        checked+=1
else:
    print('SKIP historical Phase 2.3 file hashes: PRESERVATION_23.json was not archived')
main=(root/'firmware/main/main.c').read_text()
assert 'offgrid_ble_publish(ack);' in main and 'emit_hex_record(prefix,f->text,f->length);' in main
assert 'OFFGRID_BLE_COMMAND_BINARY' in main and 'memcpy(e.text,cmd.data.frame,cmd.length)' in main
assert 'f->magic=PROTOCOL_MAGIC;f->version=PROTOCOL_VERSION;f->type=TYPE_TEXT;' in main
assert 'ESP_ERROR_CHECK(esp_wifi_set_channel(CHANNEL,WIFI_SECOND_CHAN_NONE))' in main
bridge=(root/'firmware/main/ble_bridge.c').read_text()
assert 'if(!auth) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN' in bridge
assert 'if(xQueueSend(inbox,&item,0)!=pdTRUE)return BLE_ATT_ERR_INSUFFICIENT_RES;' in bridge
assert 'push("BINCAP|1|180",true)' in bridge
assert 'push("AUTH_OK",false)' in bridge
print(f'PASS Phase 2.3 BLE authentication/ESP-NOW contracts; {checked} archived baseline hashes checked')
