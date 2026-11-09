"""Static Android source/resource contracts for Phase 2.5. Does NOT replace SDK build."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import hashlib
root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/offgrid/messenger'
ui=(src/'PremiumUi.kt').read_text()
main=(src/'MainActivity.kt').read_text()
cam=(src/'InAppCamera.kt').read_text()
receipt=(src/'ReceiptStates.kt').read_text()
media=(src/'AndroidMedia.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
assert 'versionCode=250' in gradle or 'versionCode=251' in gradle
assert 'compileSdk=36' in gradle and 'targetSdk=36' in gradle
assert 'camera-camera2:$camerax' in gradle and 'camera-lifecycle:$camerax' in gradle
assert 'camera-view:$camerax' in gradle and 'camerax="1.5.3"' in gradle
assert 'androidx.exifinterface:exifinterface:1.4.2' in gradle
xml=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
assert 'android.permission.CAMERA' in [e.get(ns+'name') for e in xml.findall('uses-permission')]
assert xml.find('./application/activity').get(ns+'windowSoftInputMode')=='adjustResize'
assert 'DropdownMenu(expanded=attachmentMenu' in ui
assert 'onPhoto()' in ui and 'onCamera()' in ui
assert 'bottomRadius=lowerRadius' in ui and 'animateDpAsState(if(keyboardVisible)3.dp else 22.dp' in ui
assert 'padding(horizontal=5.dp)' in ui and 'verticalAlignment=Alignment.CenterVertically' in ui
assert 'min=162.dp,max=208.dp' in ui and 'kind==OgmCodec.VOICE' in ui
assert 'status=="played"' in ui
assert 'ReceiptStates.visual(message.status,message.kind)' in ui
assert 'kind == OgmCodec.VOICE && status == "read"' not in receipt
assert 'status == "read" || status == "played" -> ReceiptVisual.READ' in receipt
assert 'controlReceipt(OgmCodec.READ,id)' in main
assert 'controlReceipt(OgmCodec.PLAYED,item.id)' in main
assert 'player.setOnCompletionListener' in main
assert 'InAppCameraScreen(' in main and 'Uri.fromFile(capturedFile)' in main
assert 'PhotoPreparer.prepare(contentResolver,uri,targetBytes)' in main
assert 'finally {capturedFile?.delete()}' in main
assert 'ImageCapture.OutputFileOptions.Builder(output)' in cam
assert 'imageCapture' in cam and 'ProcessCameraProvider.getInstance' in cam
assert 'provider.bindToLifecycle' in cam and 'onDispose' in cam
assert 'imageCapture=null' in cam
assert 'submitted.set(true)' in cam and 'submitted.get()' in cam
assert 'PhotoPreparer.prepare(' not in cam  # camera uses activity's shared compression path
assert 'ExifInterface.TAG_ORIENTATION' in media
assert 'if(!matrix.isIdentity)' in media
# Version-aware source-preservation guard. 2.5.1 uses a committed SHA-256 manifest
# of untouched 2.5 files, so this test runs after extracting the ZIP anywhere.
import json
if 'versionCode=251' in gradle:
    records=json.loads((root/'tests/phase251_baseline.sha256.json').read_text())['unchanged_sha256']
    for rel,digest in records.items():
        assert hashlib.sha256((root/rel).read_bytes()).hexdigest()==digest, rel
    print(f'PASS Phase 2.5/2.5.1 inherited source contracts and {len(records)} preserved files')
else:
    orig=Path('/mnt/data/OffGrid_Android_Phase2_4_4_ThemeStability.zip')
    changed={'app/src/main/java/com/offgrid/messenger/MainActivity.kt',
             'app/src/main/java/com/offgrid/messenger/PremiumUi.kt',
             'app/src/main/java/com/offgrid/messenger/ReceiptStates.kt',
             'app/src/main/java/com/offgrid/messenger/AndroidMedia.kt',
             'app/build.gradle.kts','app/src/main/AndroidManifest.xml',
             'tests/phase24/HistoryReceiptTest.kt','README.md'}
    count=0
    with ZipFile(orig) as z:
        for name in z.namelist():
            if not name.startswith('android/') or name.endswith('/'):continue
            rel=name[len('android/'):]
            if rel in changed:continue
            now=root/rel
            assert now.exists(),rel
            assert hashlib.sha256(now.read_bytes()).digest()==hashlib.sha256(z.read(name)).digest(),rel
            count+=1
    print(f'PASS Phase 2.5 source contracts and {count} preserved baseline files')
