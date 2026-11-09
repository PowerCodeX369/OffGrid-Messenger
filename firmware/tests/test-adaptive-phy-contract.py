"""Static safety/compatibility checks (NOT an ESP-IDF compile or real-board test)."""
from pathlib import Path
import hashlib
root=Path(__file__).resolve().parents[1]
base=Path('/mnt/data/OffGridMessenger_Phase1_4_5_2_WatchdogFix')
fw=(root/'main/main.c').read_text()
app=(root/'web/app.js').read_text()
checks={
 'watchdog fix retained':'pdMS_TO_TICKS(5) + 1' in fw and 'vTaskDelay(1)' in fw,
 'browser firmware handshake retained':all(x in fw for x in ('CAP=180','STABLE=1','NVSKEY=1')),
 'node NVS storage retained':all(x in fw for x in ('nvs_get_blob','nvs_set_blob','KEYSET','KEYGET')),
 'browser AES-256-GCM retained':'P.seal(' in app and 'P.open(' in app,
 'rate API actually invoked':'esp_now_set_peer_rate_config(peer_mac,&cfg)' in fw,
 'per-peer unicast used for actual messages':'?peer_mac:BROADCAST' in fw,
 'independent low-rate discovery':all(x in fw for x in ('TYPE_HELLO','HELLO_MS','send_hello()','send_frame(BROADCAST,&hello)')),
 'low-rate confirmation and read controls':'send_frame(BROADCAST,&ack)' in fw and 'send_frame(BROADCAST,&read_frame)' in fw,
 'signal policy and cooldown':all(x in fw for x in ('og_rate_target','valid_rssi_samples<3','rate_hold_until_ms','downgrade_on_missing_ack')),
 'unicast data only when peer recently seen':'t-peer_last_seen_ms<PEER_TIMEOUT_MS' in fw,
 'USB status/debug unchanged':all(x in fw for x in ('show_ready("BEACON")','show_ready("STATUS")','emit_link()')),
 'rate configuration performed on dispatcher task':'adjust_phy();' in fw and 'static void dispatcher(' in fw,
 'versioned browser asset':'app.js?v=1.4.7' in (root/'web/index.html').read_text(),
}
for name,ok in checks.items():print(('PASS' if ok else 'FAIL'),name)
assert all(checks.values())
for filename in ('sdkconfig.defaults','web/protocol.js','web/styles.css','web/logo.svg','web/adaptive.js'):
    if not (base/filename).exists():continue
    assert (root/filename).read_bytes()==(base/filename).read_bytes(), f'{filename} unexpectedly changed'
print('PASS: unchanged baseline project config / frame protocol / stylesheet / logo')
