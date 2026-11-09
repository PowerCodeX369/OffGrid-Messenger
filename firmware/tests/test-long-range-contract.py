"""Static regression checks only, not real Espressif SDK compile or range verification."""
from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parents[1]
# Frozen project invariants: self-contained SHA-256 checks instead of using
# an unrelated absolute /mnt/data path from the original authoring session.
FROZEN = {
    'web/protocol.js': '0d6fe9b9ed701444d8ad1c538f6e3ce6bb65a69f6796053d67085dadd4149635',
    'web/styles.css': 'f99534e82cc49e192baef569c28fef7f688b322a0ef9d57fdf0fea8fb5f034b6',
    'web/adaptive.js': '90073923f1b54502819c8e30faf9075905bf2bd49d24fb8958f887cc62a89f86',
    'web/logo.svg': 'ff03904fa9c8e21817768419f37fea2633b1963b43299cd3be913f3ebb82f594',
    'main/main.c': '51ad15ca812aab88b7d9f93275de2da99f4415b54f46e8749b0f305891d388a3',
}

S=(ROOT/'main/main.c').read_text()
WEB=(ROOT/'web/app.js').read_text()
checks={
 'preserves native USB IRQ RX driver':'usb_serial_jtag_driver_install(&cfg)' in S and 'usb_serial_jtag_read_bytes(dst,' in S,
 'preserves watchdog protections':'pdMS_TO_TICKS(5) + 1' in S and 'vTaskDelay(1);' in S,
 'preserves NVS key management':'KEYGET' in S and 'KEYSET' in S and 'pairkey' in S,
 'supports dual-mode WiFi LR': all(x in S for x in ('WIFI_PROTOCOL_11B','WIFI_PROTOCOL_11G','WIFI_PROTOCOL_11N','WIFI_PROTOCOL_LR','esp_wifi_set_protocol')),
 'sets real LR PHY rates':all(x in S for x in ('WIFI_PHY_MODE_LR','WIFI_PHY_RATE_LORA_250K','WIFI_PHY_RATE_LORA_500K','esp_now_set_peer_rate_config(peer_mac,&cfg)')),
 'persists learned peer for far-reconnect':all(x in S for x in ('read_saved_peer','save_peer_once','LR_PERSISTED_PEER_KEY','nvs_get_blob','nvs_set_blob')),
 'sends physical LR recovery probe':all(x in S for x in ('LR_HELLO_ID','send_lr_probe();','send_frame(peer_mac,&hello)')),
 'replies to LR probe unicast':'reply.id=LR_HELLO_ID+1;' in S and 'send_frame(e->mac,&reply)' in S,
 'ACKs use peer rate when known': 'send_frame(peer_known?r->mac:BROADCAST,&ack)' in S,
 'selective acknowledgments use unicast': 'send_frame(peer_known?e->mac:BROADCAST,&f)' in S,
 'retries never forced to short-range broadcast':'const uint8_t *dest=peer_known?peer_mac:BROADCAST;' in S,
 'has manual test and auto settings':all(x in S for x in ('RATE AUTO/RANGE/SPEED/LR500/LR250','OG_RATE_LR500','OG_RATE_LR250')),
 'website recognizes fractional LR bitrate': '0.25,0.5,1,6,12' in WEB,
 'AES browser-side still active': 'P.seal(' in WEB and 'P.open(' in WEB,
}
for name,good in checks.items(): print(('PASS' if good else 'FAIL')+': '+name)
assert all(checks.values())
for name, expected in FROZEN.items():
    actual = hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    assert actual == expected, f'frozen file unexpectedly changed: {name}'
print('PASS: frozen web encryption, styling, and ESP-NOW main.c hashes verified (portable)')
