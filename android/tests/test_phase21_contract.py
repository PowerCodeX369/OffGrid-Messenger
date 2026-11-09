#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
src=r/'app/src/main/java/com/offgrid/messenger'
main=(src/'MainActivity.kt').read_text()
wire=(src/'OgmCodec.kt').read_text()
voice=(src/'AndroidMedia.kt').read_text()
ble=(src/'BleNodeClient.kt').read_text()
media=(src/'MediaPayload.kt').read_text()
man=ET.parse(r/'app/src/main/AndroidManifest.xml').getroot()
ANDROID='{http://schemas.android.com/apk/res/android}'
assert 'android.permission.RECORD_AUDIO' in [x.attrib.get(ANDROID+'name') for x in man.findall('uses-permission')]
assert 'versionCode=220' in (r/'app/build.gradle.kts').read_text()
assert 'OgmCodec.IMAGE' in main and 'OgmCodec.VOICE' in main and 'OgmCodec.PLAYED' in main
assert 'GetContent' in main and 'photoPicker.launch("image/*")' in main
assert 'micPermission.launch(Manifest.permission.RECORD_AUDIO)' in main
assert 'photoBody' in main and 'voiceBody' in main
assert 'unpackPhoto' in main and 'unpackVoice' in main
assert 'playedReceipts.add(item.id)' in main and 'setOnCompletionListener' in main
assert 'OgmCodec.seal(secret,kind,messageId,body,width,height)' in main
assert 'magic!="OGC4" && magic!="OGB5"' in wire
assert 'onCharacteristicWrite' in ble and 'requestMtu(247)' in ble
assert 'OutputFormat.OGG' in voice and 'OutputFormat.MPEG_4' in voice
assert 'ANDROID_PHOTO_TARGET = 48 * 1024' in media
assert 'ANDROID_VOICE_TARGET = 96 * 1024' in media
print('PASS: Android media UI, permissions, OGM4/OGB5, 2.0.9 BLE MTU + voice played receipts')
