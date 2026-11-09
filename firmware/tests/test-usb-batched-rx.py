from pathlib import Path
import hashlib
root=Path(__file__).resolve().parents[1]
parent=Path(__file__).resolve().parents[2]/'OffGridMessenger_Phase1_4_9_1_CompileFix'
fw=(root/'main/main.c').read_text()
assert 'read(STDIN_FILENO,rx,sizeof(rx))' in fw
assert 'USB_RX_BATCH 256' in fw
assert 'vTaskDelay(1);' in fw
assert 'discard_until_newline' in fw
assert 'USBRX=BATCH256' in fw
assert 'getchar()' not in fw
assert 'pdMS_TO_TICKS(20)' not in fw
for rel in ('web/app.js','web/protocol.js','web/adaptive.js','web/index.html','web/styles.css','sdkconfig.defaults','main/link_rate_policy.h'):
 a=(root/rel).read_bytes();b=(parent/rel).read_bytes()
 assert a==b, f'unexpected change in {rel}'
for marker in ('esp_now_set_peer_rate_config','APPACK','KEYSET','KEYGET','TYPE_BENCH_DATA','RBENCH_DONE','xQueueReceive(event_queue,&e,pdMS_TO_TICKS(5) + 1)','vTaskDelay(1);','show_ready('):
 assert marker in fw, marker
print('PASS: 256-byte USB RX batching, preserved browser, radio, watchdog and NVS contracts')
