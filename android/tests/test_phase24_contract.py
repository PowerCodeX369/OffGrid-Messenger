from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
java=root/'app/src/main/java/com/offgrid/messenger'
main=(java/'MainActivity.kt').read_text()
ui=(java/'PremiumUi.kt').read_text()
history=(java/'EncryptedHistory.kt').read_text()
notifications=(java/'OffGridNotifications.kt').read_text()
service=(java/'OffGridConnectionService.kt').read_text()
xml=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
permissions=[x.get(a+'name') for x in xml.findall('uses-permission')]
for needed in ['android.permission.POST_NOTIFICATIONS','android.permission.FOREGROUND_SERVICE',
               'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE','android.permission.BLUETOOTH_CONNECT']:
 assert needed in permissions,needed
activity=xml.find('./application/activity')
assert activity.get(a+'windowSoftInputMode')=='adjustResize'
assert activity.get(a+'resizeableActivity')=='true'
assert xml.find('./application/service').get(a+'foregroundServiceType')=='connectedDevice'
assert xml.find('application').get(a+'allowBackup')=='false'
import re as _re
assert int(_re.search(r'versionCode\s*=\s*(\d+)',(root/'app/build.gradle.kts').read_text()).group(1))>=240,'versionCode regressed'
assert 'enableEdgeToEdge()' in main
assert 'imePadding().navigationBarsPadding()' in ui
assert 'ChatTimeline' in ui and 'ChatTopBar' in ui and 'GlassComposer' in ui
assert 'maxH*ratio' in ui and '(width/ratio).coerceAtMost(maxH)' in ui
assert 'ContentScale.Fit' in ui and 'FullPhoto' in ui
assert 'awaitFirstDown' in ui and 'awaitPointerEvent()' in ui and 'cancelPx' in ui
assert 'ReceiptStates.visual' in ui and 'ReceiptStates.advance' in main
assert 'controlReceipt(OgmCodec.READ,id)' in main and 'markVisible' in main
assert 'status=="played"' in ui and 'OgmCodec.PLAYED' in main
assert 'AES/GCM/NoPadding' in history and 'AndroidKeyStore' in history
assert 'AtomicFile' in history and 'HistoryCodec.encode' in history
assert 'cipher.init(Cipher.ENCRYPT_MODE,key())' in history and 'val iv=cipher.iv' in history
assert 'VISIBILITY_PRIVATE' in notifications and 'setPublicVersion' in notifications
assert 'onStartCommand' in service and 'startForeground' in service
assert 'BleSessionHolder' in main and 'OffGridConnectionService' in main
assert 'requestMtu(247)' in (java/'BleNodeClient.kt').read_text()
assert 'sendFrame(frame)' in (java/'BleSessionHolder.kt').read_text()
for dpi in ('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi'):
    assert (root/f'app/src/main/res/mipmap-{dpi}/ic_launcher.png').exists()
print('PASS 2.4 static contracts: adaptive insets, voice gestures, media sizing, receipts, encrypted history, notifications, foreground BLE, launcher icon')
