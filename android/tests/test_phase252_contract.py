"""Phase 2.5.2 focused source and preservation checks; not an APK build."""
from pathlib import Path
from zipfile import ZipFile
import hashlib,re
root=Path(__file__).resolve().parents[1]
base=Path('/mnt/data/OffGrid_Android_Phase2_5_1_AdaptiveChat.zip')
ui=(root/'app/src/main/java/com/offgrid/messenger/PremiumUi.kt').read_text()
cam=(root/'app/src/main/java/com/offgrid/messenger/InAppCamera.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
checks={
 '2.5.2 version': 'versionCode=252' in gradle and '2.5.2-reach-and-scroll' in gradle,
 'no firmware or BLE change': 'val reachMode' not in ui and 'var reachMode' in ui,
 'reach mode controlled by user': 'onToggleReach={reachMode=!reachMode}' in ui and 'One-handed Reach mode' in ui,
 'composer above timeline in reach branch': bool(re.search(r'if\(reachMode\)\s*\{\s*offlineBanner\(\)\s*composer\(\).*?timeline\(Modifier\.fillMaxWidth\(\)\.heightIn\(max=280\.dp\)\)',ui,re.S)),
 'normal composer stays below timeline': bool(re.search(r'else\s*\{\s*// Standard messenger:.*?timeline\(Modifier\.weight\(1f\)\.fillMaxWidth\(\)\)\s*offlineBanner\(\)\s*composer\(\)',ui,re.S)),
 'outgoing/incoming auto-follow': 'ConversationUiRules.followNewMessage(' in ui and 'LaunchedEffect(latest?.id,filter)' in ui,
 'new messages not cached by list identity': 'val newestFirst=visibleItems.asReversed()' in ui and 'val newestFirst=remember(visibleItems)' not in ui,
 'incoming unread badges kept': ui.count('if(unreadCount>0)')==2,
 'home nav has system gesture-bar padding': 'modifier=Modifier.fillMaxSize().navigationBarsPadding(),dark=dark,items=items' in ui,
 'CameraX remains full-frame 4:3': 'PreviewView.ScaleType.FIT_CENTER' in cam and cam.count('setTargetAspectRatio(AspectRatio.RATIO_4_3)')==2,
 'activity resizable and IME resize': 'android:resizeableActivity="true"' in manifest and 'android:windowSoftInputMode="adjustResize"' in manifest,
 'no blur reintroduced': 'hazeEffect' not in ui and 'hazeSource' not in ui,
}
with ZipFile(base) as z:
    files={n.removeprefix('android/'):z.read(n) for n in z.namelist() if n.startswith('android/') and not n.endswith('/')}
changed={'app/src/main/java/com/offgrid/messenger/PremiumUi.kt','app/build.gradle.kts'}
unchanged=[n for n in files if n not in changed and (root/n).exists()]
checks['all previous source/resources unchanged']=all(hashlib.sha256((root/n).read_bytes()).digest()==hashlib.sha256(files[n]).digest() for n in unchanged if not n.startswith('tests/'))
print('Audited',len([n for n in unchanged if not n.startswith('tests/')]),'unchanged Android files (non-test)')
for key,val in checks.items():print(('PASS' if val else 'FAIL'),key)
assert all(checks.values()),'Phase 2.5.2 source contract failed'
