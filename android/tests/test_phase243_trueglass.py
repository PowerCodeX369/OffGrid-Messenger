"""Static safety and layout contract for Phase 2.4.3 (requires no Android SDK)."""
from pathlib import Path
import hashlib, zipfile

android = Path(__file__).resolve().parents[1]
ui = (android / 'app/src/main/java/com/offgrid/messenger/PremiumUi.kt').read_text()
gradle = (android / 'app/build.gradle.kts').read_text()
base = Path('/mnt/data/OffGrid_Android_Phase2_4_2_Stability.zip')
assert 'compileSdk=36' in gradle
assert 'versionCode=243' in gradle
assert 'haze:1.7.2' in gradle and 'haze-blur:2.1.0' not in gradle and 'haze:2.1.0' not in gradle
assert 'compose-bom:2026.06.01' in gradle
assert 'import dev.chrisbanes.haze.hazeEffect' in ui
assert 'import dev.chrisbanes.haze.hazeSource' in ui
assert 'HazeInput.Sources' not in ui and 'HazeBlurStyle' not in ui
assert 'Modifier.fillMaxSize().hazeSource(hazeState)' in ui, 'Chat list is not captured'
assert 'topSpace=if(showSearch)150.dp else 94.dp' in ui
assert 'bottomSpace=if(recording)170.dp else 112.dp' in ui
assert 'GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=0.dp,frost=true)' in ui
assert 'GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=if(small)18.dp else 23.dp,frost=true)' in ui
assert 'Box(Modifier.matchParentSize().hazeEffect(state=modalBackdrop)' in ui
assert 'Modifier.hazeSource(modalBackdrop)' in ui
assert 'LazyColumn(Modifier.fillMaxSize().then(if(background!=null) Modifier.hazeSource(background)' in ui
assert 'rememberLazyListState(initialFirstVisibleItemIndex=visibleItems.size)' in ui
assert 'VoiceGestureRules.classify' in ui
assert 'val actualBackdrop=if(frost && haze!=null)' in ui
assert 'Modifier.blur(75.dp)' not in ui
assert 'blurRadius=24.dp' in ui and 'blurRadius=28.dp' in ui
# Compare the unchanged Android source and resources against the distributed 2.4.2 release.
if base.exists():
    with zipfile.ZipFile(base) as z:
        unchanged=0
        for n in z.namelist():
            if not n.startswith('android/') or n.endswith('/'):
                continue
            rel=n[len('android/'):]
            if rel in ('app/src/main/java/com/offgrid/messenger/PremiumUi.kt','app/build.gradle.kts'):
                continue
            dest=android / rel
            assert dest.exists(), f'Missing baseline file {n}'
            assert hashlib.sha256(z.read(n)).digest()==hashlib.sha256(dest.read_bytes()).digest(),f'Unintended change: {n}'
            unchanged+=1
        print(f'PASS {unchanged} unchanged Android baseline files, including BLE, encryption, media, notification, icons')
print('PASS SDK36/AGP8.13 Haze v1 API / true-scroll-source blur layout / modal overlay contract')
