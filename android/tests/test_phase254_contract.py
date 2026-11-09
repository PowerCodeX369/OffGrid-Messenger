"""Phase 2.5.4 minimal-scope source verification; not an Android Gradle build."""
from pathlib import Path
import hashlib, json
root=Path(__file__).resolve().parents[2]
manifest=json.loads((root/'PRESERVED_BASELINE_253.json').read_text())
for rel,sha in manifest.items():
    path=root/rel
    assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==sha,rel
ui=(root/'android/app/src/main/java/com/offgrid/messenger/PremiumUi.kt').read_text()
gradle=(root/'android/app/build.gradle.kts').read_text()
assert 'versionCode=254; versionName="2.5.4-scroll-motion"' in gradle
assert 'if(messageCount-lazy.firstVisibleItemIndex>18) lazy.scrollToItem(messageCount)' in ui
assert 'else lazy.animateScrollToItem(messageCount)' in ui
assert 'if(previous<0)' in ui and 'else if(messageCount>previous)' in ui
assert 'nearBottom' not in ui
assert 'navigationDepth(targetState)<navigationDepth(initialState)' in ui
assert 'slideInHorizontally(animationSpec=androidx.compose.animation.core.tween(260)) { direction*(it/7) }' in ui
assert 'slideOutHorizontally(animationSpec=androidx.compose.animation.core.tween(210)) { -direction*(it/8) }' in ui
assert '"chats" -> 0' in ui and '"chat" -> 1' in ui and '"settings" -> 2' in ui
camera=(root/'android/app/src/main/java/com/offgrid/messenger/InAppCamera.kt').read_text()
assert 'PreviewView.ScaleType.FIT_CENTER' in camera
assert camera.count('AspectRatio.RATIO_4_3')>=2
assert 'requestMtu(247)' in (root/'android/app/src/main/java/com/offgrid/messenger/BleNodeClient.kt').read_text()
print('PASS Phase 2.5.4 source scope, scroll, reciprocal navigation, 4:3 camera, BLE and',len(manifest),'preserved baseline files')
