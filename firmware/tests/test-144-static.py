"""Static protocol/firmware contract checks. No ESP-IDF compiler or radio is exercised."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
fw=(root/'main/main.c').read_text()
app=(root/'web/app.js').read_text()
checks={
    'discovery beacon sent without user messages': 'broadcast_hello();' in fw and 'TYPE_HELLO' in fw,
    'HELLO accepted through Wi-Fi receive path': 'if(f->type==TYPE_HELLO){hello_rx++;return;}' in fw,
    'NVS persistent 32-byte key': all(t in fw for t in ('nvs_get_blob','nvs_set_blob','nvs_commit','KEYGET','KEYSET ')),
    'radio TX/RX diagnostics': all(t in fw for t in ('|TXOK=','|TXFAIL=','|RX=','|HELLO=')),
    'new firmware ready handshake': 'DISC=1|NVSKEY=1' in fw and "parts.includes('NVSKEY=1')" in app,
    'key returned from local USB only': "if(strcmp(line,\"KEYGET\")==0)" in fw,
    'browser reads key from board': "keyRequest('get','KEYGET')" in app,
    'browser sends key to board': "keyRequest('set','KEYSET '+P.hex(raw))" in app,
    'browser no longer saves keys to indexed DB': not re.search(r"dbPut\(['\"]keys['\"]",app),
    'browser deletes old key after history rewrap': "dbDelete('keys',macKey())" in app,
    'approved photo gallery intact': 'openPhotoViewer' in app and 'navigatePhoto' in app,
    'web crypto intact': 'P.seal(' in app and 'P.open(' in app,
}
for name,good in checks.items():print(('PASS' if good else 'FAIL'),name)
if not all(checks.values()):raise SystemExit(1)
