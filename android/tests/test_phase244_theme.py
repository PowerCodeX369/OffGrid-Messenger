"""Phase 2.4.4 dependency/contrast/source-preservation checks (not an Android SDK build)."""
from pathlib import Path
from zipfile import ZipFile
import re,hashlib
root=Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/com/offgrid/messenger/PremiumUi.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
import re as _re
assert int(_re.search(r'versionCode\s*=\s*(\d+)',gradle).group(1))>=244,'versionCode regressed'
assert 'compileSdk=36' in gradle
assert 'dev.chrisbanes.haze' not in gradle
assert 'dev.chrisbanes.haze' not in ui
assert '.hazeSource(' not in ui and '.hazeEffect(' not in ui
assert 'LocalContentColor provides colors.onBackground' in ui
assert 'contentColor=colors.onSurface' in ui
assert 'titleContentColor=MaterialTheme.colorScheme.onSurface' in ui
assert 'textContentColor=MaterialTheme.colorScheme.onSurfaceVariant' in ui
assert 'val photo=bitmap' in ui and 'if(photo!=null) Image(photo,' in ui
assert ui.count('item { GlassCard(Modifier.fillMaxWidth(),dark=dark)') == 3
# The background modifier precedes system-bar padding so status bar isn't left white in dark mode.
assert '.background(Brush.linearGradient(bg)) // cover the system status-bar region too\n        .windowInsetsPadding' in ui
assert 'onSurface=Color(0xFFE5ECF6)' in ui
assert 'onSurfaceVariant=Color(0xFFB6C6D9)' in ui
assert 'onSurface=Color(0xFF111A29)' in ui

def rgb(s):
 h=s.lstrip('#');return [int(h[i:i+2],16)/255 for i in (0,2,4)]
def luminance(x):
 v=rgb(x);v=[c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4 for c in v];return sum(a*b for a,b in zip(v,[.2126,.7152,.0722]))
def contrast(a,b):
 x,y=sorted((luminance(a),luminance(b)),reverse=True);return (x+.05)/(y+.05)
pairs={'dark primary text on card':('#E5ECF6','#18283D'),
       'dark secondary text on card':('#B6C6D9','#18283D'),
       'dark text on root':('#E5ECF6','#0D1523'),
       'light primary text on card':('#111A29','#FFFFFF'),
       'light secondary text on card':('#3F5167','#FFFFFF')}
for title,(a,b) in pairs.items():
 value=contrast(a,b);print(f'PASS {title}: {value:.2f}:1');assert value>=4.5,title
base=Path('/mnt/data/OffGrid_Android_Phase2_4_3_TrueGlass.zip')
with ZipFile(base) as z:
 n=0
 for info in z.infolist():
  if not info.filename.startswith('android/') or info.is_dir():continue
  rel=info.filename[len('android/'):]
  if rel in ('app/src/main/java/com/offgrid/messenger/PremiumUi.kt','app/build.gradle.kts'):continue
  f=root/rel
  assert f.exists(),f'Missing {rel}'
  assert hashlib.sha256(f.read_bytes()).digest()==hashlib.sha256(z.read(info.filename)).digest(),f'Unexpected baseline change: {rel}'
  n+=1
 print(f'PASS {n} unchanged Android baseline files (including BLE, crypto, history, audio, notifications, icons)')
print('PASS no backdrop capture; Material content colors, system bar background, settings width, prior IDE fixes')
