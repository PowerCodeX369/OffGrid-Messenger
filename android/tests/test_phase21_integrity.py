from pathlib import Path
import hashlib,json
android=Path(__file__).resolve().parents[1]
local=json.loads((android/'ANDROID_BASELINE_SHA256.json').read_text())
for path,digest in local.items():
    assert hashlib.sha256((android/path).read_bytes()).hexdigest()==digest, 'BLE security baseline changed: '+path
print(f'PASS: Android BLE pairing and fast GATT transport hashes preserved ({len(local)} files)')
root=android.parent
if (root/'PRESERVATION_21.json').exists() and (root/'firmware').is_dir():
    manifest=json.loads((root/'PRESERVATION_21.json').read_text())
    for path,digest in manifest['preserved'].items():
        assert hashlib.sha256((root/path).read_bytes()).hexdigest()==digest, 'Firmware baseline changed: '+path
    assert all(p.startswith('android/') for p in manifest['changed_android_files'])
    print(f'PASS: Full baseline preservation verified ({len(manifest["preserved"])} files)')
