"""Proof that BLE latency changes did not alter radio logic or BLE security.
This test reconstructs the old source from the narrow 209 changes and checks SHA256
against actual Phase 2.0.6 source hashes, preventing unnoticed modifications.
"""
from pathlib import Path
import hashlib
R=Path(__file__).resolve().parents[1]
old_main='b9af55959075901bddbf62868981981e8754afad17a666acc9752311e79d927a'
old_ble='05beb146147bf0f62378a8264140327ddc463b0fce6153aa29ada70bc523ac91'
main=(R/'main/main.c').read_text()
old_poll='vTaskDelay(pdMS_TO_TICKS(15)+1);continue;'
new_poll='/* A single tick avoids 20-30ms inbox polling latency on 100Hz FreeRTOS. */\n      vTaskDelay(1);continue;'
assert new_poll in main and old_poll not in main
rebuilt=main.replace(new_poll,old_poll)
assert hashlib.sha256(rebuilt.encode()).hexdigest()==old_main,'Unexpected ESP-NOW main.c edit'
bridge=(R/'main/ble_bridge.c').read_text()
old_delay='done+=count;vTaskDelay(pdMS_TO_TICKS(13)+1);'
new_delay='done+=count;\n   /* One tick is enough to yield to NimBLE and Wi-Fi; the old 13ms+1 tick\n    * penalized EVERY ATT fragment. Keep a yield to avoid flooding buffers. */\n   vTaskDelay(1);'
assert new_delay in bridge and old_delay not in bridge
rebuilt=bridge.replace(new_delay,old_delay)
assert hashlib.sha256(rebuilt.encode()).hexdigest()==old_ble,'Unexpected BLE security or notifications edit'
conf=(R/'sdkconfig.defaults').read_text()
assert 'CONFIG_IDF_TARGET="esp32c3"' in conf
assert 'CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU=247' in conf
assert 'CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions.csv"' in conf
print('PASS: reconstructed exact 2.0.6 radio + BLE SHA256, only latency lines changed')
