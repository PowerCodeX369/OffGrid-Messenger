#!/usr/bin/env python3
"""Source integrity regression against frozen 1.4.10.2: only photo batch ACK extension."""
from pathlib import Path
import hashlib
root=Path(__file__).resolve().parents[1]
base=root.parent/'OffGridMessenger_Phase1_4_10_2_InterruptUSB'
for rel in ('sdkconfig.defaults','main/Kconfig.projbuild','main/link_rate_policy.h','main/CMakeLists.txt','web/styles.css','web/logo.svg','web/adaptive.js'):
    assert (root/rel).read_bytes()==(base/rel).read_bytes(),f'Baseline file modified unexpectedly: {rel}'
new=(root/'main/main.c').read_text()
old=(base/'main/main.c').read_text()
for literal in ('usb_serial_jtag_driver_install(&cfg)','usb_serial_jtag_read_bytes(dst,',
                'pdMS_TO_TICKS(5) + 1','vTaskDelay(1);','KEYGET','KEYSET',
                'esp_now_set_peer_rate_config(peer_mac,&cfg)','TYPE_BENCH_DATA',
                'APPACK ','TYPE_RECEIPT','MAX_PENDING 8'):
    assert literal in new and literal in old,literal
assert '#define SACK_MAX_IDS 6' in new
assert 'TYPE_BATCH_RECEIPT' in new and 'APPACKB ' in new
assert '|SACK=1|' in new
app=(root/'web/app.js').read_text();protocol=(root/'web/protocol.js').read_text()
assert 'APPACKB ${group.mac} ${group.session}' in app
assert 'Legacy · acknowledge every fragment' in app
assert 'speedDiagnostics.sackCapable' in app
assert 'OGC4' in (root/'web/protocol.js').read_text() or '[79,71,67,52]' in protocol
assert 'batch?[79,71,66,53]:[79,71,67,52]' in protocol
print('PASS selective ACK feature gated, frozen settings/USB PHY baseline preserved')
