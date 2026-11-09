from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[3]
source=root/'android/app/src/main/java/com/offgrid/messenger'
ui=(source/'PremiumUi.kt').read_text()
main=(source/'MainActivity.kt').read_text()
history=(source/'EncryptedHistory.kt').read_text()
assert 'versionCode=242' in (root/'android/app/build.gradle.kts').read_text()
for key in ('rememberLazyListState(initialFirstVisibleItemIndex=visibleItems.size)',
            'HazeInput.Sources(haze)', 'hazeSource(hazeState)',
            'Recording locked', 'onCancel();trashFlash=true',
            'VoiceGestureRules.classify', 'produceState<androidx.compose.ui.graphics.ImageBitmap?>', 'onSurface=Color(0xFFE5ECF6)'):
    assert key in ui, key
assert 'Search chats' not in ui
assert 'Modifier.animateItem()' not in ui
assert '.blur(75.dp)' not in ui
for key in ('hiddenMessageIds.add(item.id)', 'items.removeAt(idx)',
            'history.saveHiddenIds(deletedSnapshot)', 'history.loadHiddenIds()',
            'env.id in hiddenMessageIds', 'history.clear()'):
    assert key in main,key
for key in ('AtomicFile','AES/GCM/NoPadding','hiddenAad','offgrid-hidden-ids.enc'):
    assert key in history,key
# Critical 2.3 BLE and crypto implementation unchanged from 2.4.1.
manifest=json.loads((root/'PRESERVATION_242.json').read_text())
for path,digest in manifest['preserved_sha256'].items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==digest,path
print('PASS UI/HCI source checks and %d unchanged transport/resource files'%len(manifest['preserved_sha256']))
