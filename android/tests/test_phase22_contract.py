#!/usr/bin/env python3
from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[1]
base=root.parent
src=root/'app/src/main/java/com/offgrid/messenger'
main=(src/'MainActivity.kt').read_text()
media=(src/'MediaTransferWindow.kt').read_text()
photo=(src/'AndroidMedia.kt').read_text()
for fragment in ['if(t.media && !t.control && t.frames.size>1)',
                 'activeMedia?.pauseForReceipts()',
                 'if(parsed.bulk) mediaAcks.accept',
                 'val targetBytes = if(fastPhotos)',
                 'mediaWindow:MediaTransferWindow?',
                 'getSharedPreferences("offgrid_media"']:
    assert fragment in main, fragment
for fragment in ['window: Int = 3','onQueued(', 'onDelivered(', 'onFailed(', 'pauseForReceipts()', 'APPACKB']:
    assert fragment in media, fragment
assert 'qualityFallback' in photo and 'FAST_PHOTO_TARGET' in (src/'MediaPayload.kt').read_text()
assert 'versionCode=220' in (root/'app/build.gradle.kts').read_text()
if (base/'PRESERVATION_22.json').is_file():
    manifest=json.loads((base/'PRESERVATION_22.json').read_text())
    frozen=manifest['frozen_sha256']
    reference=base
else:
    manifest=json.loads((root/'PRESERVATION_22_ANDROID.json').read_text())
    frozen=manifest['frozen_sha256']
    reference=root
for rel,digest in frozen.items():
    p=reference/rel
    assert p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==digest,rel
print(f'PASS: Phase 2.2 media integration + {len(frozen)} preserved source files')
