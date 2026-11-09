"""Static regression checks only, not real Espressif SDK compile or range verification."""
from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parents[1]
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
# Portable SHA-256 checks for components intentionally kept unchanged by the UI patch.
expected={'main/main.c': '5ba1a3ad84f8c24f9c646578423701fb6c0d74e02af9bfa0437799a61026a8b0', 'main/link_rate_policy.h': '85d6346846fb5d8c96efc131b36e8c5167b8738b0fd466f36068275c29f19c44', 'main/Kconfig.projbuild': '8e60879148e28ab24d0a577c78a2fa123f4ed35e7d2f06c8c8800b3acd8c06d1', 'main/CMakeLists.txt': 'c46684d574a03293e32257d859d456943bbd6859371e6c3fc2f8ebccde1a5dce', 'sdkconfig.defaults': 'c8bfd8deca28f70f84a784e7eb297f080ae1f67dc2f550e9db7ce9b78d6c6cb0', 'web/protocol.js': 'd8ce13d3f7e4a9b43bca5528ccef8102cb34c7e73d93842f4f2d28e64f5718d8', 'web/adaptive.js': '90073923f1b54502819c8e30faf9075905bf2bd49d24fb8958f887cc62a89f86', 'web/logo.svg': 'ff03904fa9c8e21817768419f37fea2633b1963b43299cd3be913f3ebb82f594'}
for path,digest in expected.items():
    actual=hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
    assert actual==digest, f'preserved firmware/protocol file was modified: {path}'
print('PASS: firmware, crypto protocol, adaptive photo logic and project configuration unchanged')
