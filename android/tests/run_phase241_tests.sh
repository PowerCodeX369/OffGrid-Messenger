#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)";trap 'rm -rf "$tmp"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$tmp/history.jar"
java -jar "$tmp/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$tmp/mutations.jar"
java -jar "$tmp/mutations.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/MessageMutation.kt" tests/phase25/CrossWire.kt -include-runtime -d "$tmp/cross.jar"
if [ -f ../firmware/tests/test-phase241-crosswire.cjs ]; then
  CROSSWIRE_JAR="$tmp/cross.jar" node ../firmware/tests/test-phase241-crosswire.cjs
else
  echo 'SKIP web cross-wire suite: use Complete ZIP for integrated web source'
fi
python3 tests/phase25/test_contract.py
if [ -f ../firmware/tests/test-phase241-mutations.cjs ]; then
  node ../firmware/tests/test-phase241-mutations.cjs
  node ../firmware/tests/test-protocol.cjs
  node ../firmware/tests/test-selective-ack.cjs
  node ../firmware/tests/test-selective-ack-browser.cjs
fi
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$tmp/ble.jar"
java -jar "$tmp/ble.jar"
kotlinc tests/mock_android/*.kt "$src/BleBinaryCodec.kt" "$src/BleNodeClient.kt" -include-runtime -d "$tmp/gatt.jar"
java -cp "$tmp/gatt.jar" com.offgrid.messenger.BleClientContractTestKt
java -cp "$tmp/gatt.jar" com.offgrid.messenger.BinaryGattTestKt
printf '\nPASS Phase 2.4.1 host suite (not an Android Studio build)\n'
