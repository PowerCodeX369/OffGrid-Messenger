"""Minimal 2.5.6 change-scope and source intent audit; not a Gradle build."""
import hashlib,zipfile,pathlib,re
base=pathlib.Path('/mnt/data/OffGrid_Android_Phase2_5_4_ScrollMotion.zip')
root=pathlib.Path(__file__).resolve().parents[2]
changes=[]
with zipfile.ZipFile(base) as z:
    for src in z.namelist():
        if not src.startswith('android/') or src.endswith('/'):continue
        f=root/src
        assert f.is_file(),src
        if hashlib.sha256(z.read(src)).digest()!=hashlib.sha256(f.read_bytes()).digest():changes.append(src)
allowed={
 'android/app/src/main/java/com/offgrid/messenger/MainActivity.kt',
 'android/app/src/main/java/com/offgrid/messenger/PremiumUi.kt',
 'android/app/src/main/java/com/offgrid/messenger/OffGridConnectionService.kt',
 'android/app/src/main/java/com/offgrid/messenger/BleSessionHolder.kt',
 'android/app/src/main/AndroidManifest.xml','android/app/build.gradle.kts'
}
assert set(changes)==allowed,(set(changes),allowed)
assert (root/'android/app/src/main/java/com/offgrid/messenger/RadioLink.kt').is_file()
ui=(root/'android/app/src/main/java/com/offgrid/messenger/PremiumUi.kt').read_text()
assert 'radioLink?.summary' in ui and 'DropdownMenu(expanded=menuOpen' in ui
assert 'onRateMode(mode)' in ui and 'RadioLink.choices.forEach' in ui
assert 'topSpace=if(showSearch)150.dp else 94.dp' in ui # 2.5.4 chat layout preserved
service=(root/'android/app/src/main/java/com/offgrid/messenger/OffGridConnectionService.kt').read_text()
assert 'START_STICKY' in service and 'ensureConnected(this)' in service and 'onTaskRemoved' in service
assert 'getBoolean("ble_background",false)' in service
holder=(root/'android/app/src/main/java/com/offgrid/messenger/BleSessionHolder.kt').read_text()
assert 'if(hasKey && activeAddress!=address) connect(address)' in holder
assert 'if(activeAddress==address)return' in holder
assert 'latestLink' in holder
activity=(root/'android/app/src/main/java/com/offgrid/messenger/MainActivity.kt').read_text()
assert activity.count('"ERROR" ->')==1
assert 'setRadioMode={mode ->' in activity and 'link?.send("RATE $mode")' in activity
assert 'versionCode=256' in (root/'android/app/build.gradle.kts').read_text()
print('PASS: 2.5.6 UI/service change scope, telemetry, rate controls, background service;',len(changes),'changed baseline files')
