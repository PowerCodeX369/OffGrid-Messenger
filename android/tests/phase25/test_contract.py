from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import hashlib
import json
root=Path(__file__).resolve().parents[3]
src=root/'android/app/src/main/java/com/offgrid/messenger'
ui=(src/'PremiumUi.kt').read_text();main=(src/'MainActivity.kt').read_text()
notifications=(src/'OffGridNotifications.kt').read_text();history=(src/'EncryptedHistory.kt').read_text()
web_path=root/'firmware/web/app.js';protocol_path=root/'firmware/web/protocol.js'
web=web_path.read_text() if web_path.exists() else None
protocol=protocol_path.read_text() if protocol_path.exists() else None
assert 'versionCode=241' in (root/'android/app/build.gradle.kts').read_text()
for text in ['AnimatedContent(targetState=page','ChatTopBar(','WindowInsets.safeDrawing','imePadding().navigationBarsPadding()',
             'HapticFeedbackType.LongPress','Recording discarded','trash bounce','onLongPress','Delete for everyone',
             'Edit message','showChatInfo','chooseAvatar','onSurfaceVariant','blur(75.dp)','shadow(']:
    assert text in ui,text
for text in ['MessageMutation.editPayload','MessageMutation.deletePayload','OgmCodec.EDIT, OgmCodec.DELETE',
             'loadAvatar()','saveAvatar(data)','contactName=contactName,avatarBytes=avatarBytes']:
    assert text in main,text
for text in ['setShortcutId','ShortcutManagerCompat.pushDynamicShortcut','MessagingStyle','setContact(',
             'VISIBILITY_PRIVATE','CHAT_ID']:
    assert text in notifications,text
assert '.setGroup("offgrid_chat")' not in notifications
assert 'OffGridMessenger/contact-avatar/1' in history and 'AtomicFile' in history
if web is not None:
    assert 'edit:9,delete:10' in protocol
    assert 'function showMessageActions(id)' in web and 'env.kind===P.types.edit' in web
    assert 'P.sealAtRest(secretKey,P.textBytes(url)' in web
r=root/'android/app/src/main/res'
android='{http://schemas.android.com/apk/res/android}'
xml=ET.parse(r/'mipmap-anydpi-v26/ic_launcher.xml').getroot()
assert xml.find('monochrome') is not None
for n in ['ic_launcher_foreground.xml','ic_launcher_monochrome.xml']:
    root_xml=ET.parse(r/'drawable'/n).getroot()
    assert root_xml.attrib.get(android+'viewportWidth')=='108'
for dpi in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
    assert (r/f'mipmap-{dpi}/ic_launcher.png').exists()
# Verify SHA-256 of every packaged source known to be baseline-unchanged.
# This works in both the Android-only and the complete archive: absent firmware
# files are not assumed to be present in the Android-only source ZIP.
manifest=json.loads((root/'PRESERVATION_241.json').read_text())
checked=0
for path,digest in manifest['preserved_files'].items():
    f=root/path
    if f.exists():
        assert hashlib.sha256(f.read_bytes()).hexdigest()==digest, 'baseline source changed: '+path
        checked+=1
assert checked>=10
print('PASS release contract: navigation, keyboard insets, haptics, native conversation, monochrome icon')
print('PASS preserved %d baseline source files present in this release' % checked)
