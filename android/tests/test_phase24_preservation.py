from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[2]
manifest=json.loads((root/'PRESERVATION_24.json').read_text())
for path,digest in manifest['preserved_sha256'].items():
    actual=root/path
    assert actual.is_file() and hashlib.sha256(actual.read_bytes()).hexdigest()==digest, f"Phase 2.3 transport changed: {path}"
print('PASS 2.4 preservation: %s Phase 2.3 firmware & transport files unchanged' %len(manifest['preserved_sha256']))
