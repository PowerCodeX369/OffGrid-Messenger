from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/offgrid/messenger'
ui=(src/'PremiumUi.kt').read_text()
cam=(src/'InAppCamera.kt').read_text()
main=(src/'MainActivity.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
checks={
    'version 2.5.1':'versionCode=251' in gradle,
    'unread count uses source model':'ConversationUiRules.unreadCount(items)' in ui,
    'both unread badges':'if(unreadCount>0)' in ui and ui.count('if(unreadCount>0)')==2,
    'home screen before chat':'rememberSaveableString("chats")' in ui,
    'reverse newest-at-bottom':'reverseLayout=true' in ui and 'initialFirstVisibleItemIndex=0' in ui,
    'outgoing and near-bottom incoming follow':'ConversationUiRules.followNewMessage(' in ui,
    'visible id indexing reversed':'newestFirst.getOrNull(it)' in ui,
    'no old fixed spacer layout':'topSpace=' not in ui and 'bottomSpace=' not in ui,
    'IME shrinks scroll section only':'Modifier.weight(1f).fillMaxWidth()' in ui and '.imePadding().navigationBarsPadding()' in ui,
    'safe gestures/cutout horizontal':'WindowInsets.safeContent.only(' in ui,
    'CameraX full FIT preview':'PreviewView.ScaleType.FIT_CENTER' in cam,
    'CameraX preview and capture 4:3':cam.count('setTargetAspectRatio(AspectRatio.RATIO_4_3)')==2,
    'camera preview respects usable region':'val width=minOf(maxWidth,maxHeight*ratio)' in cam,
    'resizable activity':'android:resizeableActivity="true"' in (root/'app/src/main/AndroidManifest.xml').read_text(),
    'do not auto mark unread read on initial home':'private var chatScreenVisible by mutableStateOf(false)' in main,
}
import json,hashlib
preserved=json.loads((root/'tests/phase251_baseline.sha256.json').read_text())['unchanged_sha256']
checks['previous 2.5 files unchanged']=all((root/rel).is_file() and
    hashlib.sha256((root/rel).read_bytes()).hexdigest()==digest
    for rel,digest in preserved.items())
print(f"Audited {len(preserved)} unchanged baseline files")
for name,passed in checks.items():print(('PASS' if passed else 'FAIL'),name)
assert all(checks.values()),'Phase 2.5.1 contract failed'
