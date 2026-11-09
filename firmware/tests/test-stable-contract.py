"""Source/API compatibility checks ONLY. Not actual ESP-IDF compilation or radio tests."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
fw=(root/'main/main.c').read_text()
app=(root/'web/app.js').read_text()
proto=(root/'web/protocol.js').read_text()
checks={
 'reused stable broadcast transport': 'send_frame(BROADCAST,&o->frame)' in fw,
 'no peer PHY rate switch': 'esp_now_set_peer_rate_config' not in fw,
 'no blocking WiFi TX callback': 'tx_done' not in fw and 'xSemaphoreTake' not in fw,
 'lower bounded interframe gap': '#define SEND_GAP_MS 8' in fw,
 'smaller stable 180-byte frame': '#define MAX_TEXT 180' in fw and 'const CHUNK=160' in proto,
 'handshake rejects mismatched firmware': all(x in fw for x in ('CAP=180','STABLE=1','NVSKEY=1')) and all(x in app for x in ('CAP=180','STABLE=1','NVSKEY=1')),
 'browser uses AES-GCM': "await P.seal(" in app and 'P.open(' in app,
 'ESP32 stores NVS key': all(x in fw for x in ('nvs_get_blob','nvs_set_blob','nvs_commit','KEYGET','KEYSET')),
 'browser requests key from ESP32': "keyRequest('get','KEYGET')" in app,
 'browser not storing new keys': not re.search(r"dbPut\(['\"]keys['\"]",app),
 'text and receipts stay one in-flight': 'function chosenWindow(job=activeTransfer)' in app and "const kind=job?.kind==='image'&&!job?.control?'image':'text'" in app and "if(kind!=='image'" in (root/'web/adaptive.js').read_text(),
 'approved gallery retained': 'openPhotoViewer' in app and 'navigatePhoto' in app,
 'usb-serial support': 'requestPort()' in app,
}
for label,ok in checks.items():print(('PASS' if ok else 'FAIL'),label)
assert all(checks.values())
