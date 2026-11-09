from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'main/main.c').read_text()
web=(root/'web/app.js').read_text()
for item in ['esp_wifi_get_max_tx_power', 'esp_wifi_set_max_tx_power', 'RFTEST POWER/SIM/STATUS/OFF', 'RFTEST_ERROR', 'RFTEST|ACTIVE=','RF_TEST_LEASE_MS','rf_test_expire()', 'rf_test_is_active()', 'RFTEST_SIM|RSSI=', 'rf_simulated_rssi']:
 assert item in source,item
assert 'if(requested>rf_power_original_raw)' in source
assert 'if(rf_simulated && link_mode==OG_RATE_AUTO)return og_lr_target' in source
assert 'if(rf_test_is_active() && now_ms()-rf_test_last_touch_ms>RF_TEST_LEASE_MS)' in source
for item in ['function showRfLab()', 'rfLabReceive(s)', 'RFTEST ', 'runRadioBenchmark(count)', 'RF Lab · power & range test']:
 assert item in web,item
assert (root/'web/protocol.js').read_bytes()==Path('/mnt/data/OffGridMessenger_Phase1_4_11_1_ManualLR/web/protocol.js').read_bytes()
assert (root/'web/adaptive.js').read_bytes()==Path('/mnt/data/OffGridMessenger_Phase1_4_11_1_ManualLR/web/adaptive.js').read_bytes()
print('RF_LAB_SOURCE_PASS')
