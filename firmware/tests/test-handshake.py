from pathlib import Path
import re
root=Path(__file__).resolve().parent.parent
c=(root/'main/main.c').read_text()
js=(root.parent/'web/app.js').read_text()
assert '#define READY_BEACON_MS' in c
assert 'show_ready("BEACON")' in c
assert 'show_ready("STATUS")' in c
assert 'show_ready("BOOT")' in c
assert c.count('show_ready("BEACON")') == 1
for field in ['PROTO=2','CAP=180','STABLE=1','NVSKEY=1']:
    assert field in c and field in js,field
assert 'usbWaitTimer=setInterval(updateUsbWait' in js
assert "logUsb('ESP32> KEYDATA|<redacted>')" in js
assert 'usbLastRawAt=performance.now()' in js
assert 'if(usbWaitTimer){clearInterval(usbWaitTimer);usbWaitTimer=null;}' in js
print('USB handshake regression checks: PASS')
