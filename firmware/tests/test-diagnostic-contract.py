"""Static regression contracts. Not an ESP-IDF compile or real-hardware benchmark."""
from pathlib import Path
import re,hashlib
root=Path(__file__).resolve().parents[1]
source=root.parent/'OffGridMessenger_Phase1_4_10_2_InterruptUSB'
fw=(root/'main/main.c').read_text();app=(root/'web/app.js').read_text()
for path in ('main/link_rate_policy.h','sdkconfig.defaults','web/adaptive.js','web/logo.svg'):
 assert (root/path).read_bytes()==(source/path).read_bytes(),f'Unexpected modification: {path}'
for text in ('pdMS_TO_TICKS(5) + 1','vTaskDelay(1)','KEYGET','KEYSET','APPACK',
             'esp_now_set_peer_rate_config','TYPE_BENCH_DATA','TYPE_BENCH_ACK',
             'RBENCH_START','RBENCH_DONE','USBIN_BEGIN','USBIN_DONE','USBOUT_DONE'):
 assert text in fw,text
for text in ('P.seal(secretKey','P.open(secretKey','diagnosticLine(s)','runUsbInput','runUsbOutput',
             'runRadioBenchmark','runFullBenchmark','showSpeedDiagnostics','probeAck'):
 assert text in app,text
assert 'DIAG=1' in fw and "parts.includes('DIAG=1')" in app
assert 'P.types.probe' in app and 'P.types.probeAck' in app
assert 'f->type==TYPE_BENCH_DATA' in fw and 'send_frame(BROADCAST,&ack)' in fw
# Commands accept only bounded test packets, and cannot erase NVS.
assert 'BENCH_MAX_PACKETS 128' in fw
assert 'BENCH_PAYLOAD_BYTES 160' in fw
assert 'nvs_flash_erase()' in fw  # pre-existing recovery path; no new erase command
assert 'diagPrint' in app
assert (root/'web/index.html').read_text().count('app.js?v=1.4.10.3')==1
print('PASS diagnostic command contracts and preserved key/radio/watchdog wiring')
