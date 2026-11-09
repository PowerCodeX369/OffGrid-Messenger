from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
s=(root/'main/main.c').read_text()
# At 100 Hz, 5ms would produce zero ticks. The extra +1 is essential.
assert ((5 * 100) // 1000) == 0
for hz in (100, 250, 1000):
    assert ((5 * hz) // 1000) + 1 >= 1
# Ensure the actual dispatcher uses the protected timeout and always blocks.
dispatch=s.split('static void dispatcher(void *arg) {',1)[1].split('static void serial_task',1)[0]
assert 'xQueueReceive(event_queue,&e,pdMS_TO_TICKS(5) + 1)' in dispatch
assert re.search(r'vTaskDelay\(1\);\s*}\s*}',dispatch)
assert 'esp_task_wdt_deinit' not in s
assert 'esp_task_wdt_delete' not in s
# No changes to the radio payload and stored-key interface.
assert '#define MAX_TEXT 180' in s
assert 'send_frame(BROADCAST,&o->frame)' in s
assert all(x in s for x in ['KEYGET','KEYSET','nvs_get_blob','nvs_set_blob'])
print('Watchdog scheduler regression checks: PASS')
