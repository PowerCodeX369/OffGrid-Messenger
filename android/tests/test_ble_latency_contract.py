#!/usr/bin/env python3
"""Static source contract; compile with the real Android SDK before shipping APK."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
s=(r/'app/src/main/java/com/offgrid/messenger/BleNodeClient.kt').read_text()
assert 'client.requestMtu(247)' in s
assert 'negotiateMtuThenSubscribe(client)' in s
assert 'subscribeNotifications(client)' in s
assert 'if (mtuPending)' in s
assert 'CONNECTION_PRIORITY_HIGH' in s
assert 'CONNECTION_PRIORITY_BALANCED' in s
assert 'WRITE_TYPE_DEFAULT' in s
assert 'min(180, maxOf(20, negotiatedMtu - 3))' in s
assert 'TX_CHUNK' in s
assert 'AUTH_OK' in s and 'OgmCodec.proof' in s
assert 'onCharacteristicWrite' in s
assert 'connectGatt(' in s
print('PASS Android BLE MTU, priority, write serialization and HMAC code contracts')
