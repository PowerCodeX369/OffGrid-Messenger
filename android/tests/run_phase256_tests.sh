#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/offgrid/messenger
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
kotlinc "$SRC/RadioLink.kt" tests/RadioLinkTest.kt -include-runtime -d "$TMP/link.jar"
java -jar "$TMP/link.jar"
kotlinc tests/phase24/ChatItemStub.kt "$SRC/OgmCodec.kt" "$SRC/HistoryCodec.kt" "$SRC/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TMP/history.jar"
java -jar "$TMP/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$SRC/OgmCodec.kt" "$SRC/HistoryCodec.kt" "$SRC/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$TMP/mutations.jar"
java -jar "$TMP/mutations.jar"
kotlinc "$SRC/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TMP/binary.jar"
java -jar "$TMP/binary.jar"
kotlinc tests/mock_android/*.kt "$SRC/BleBinaryCodec.kt" "$SRC/BleNodeClient.kt" -include-runtime -d "$TMP/gatt.jar"
java -cp "$TMP/gatt.jar" com.offgrid.messenger.BleClientContractTestKt
java -cp "$TMP/gatt.jar" com.offgrid.messenger.BinaryGattTestKt
kotlinc "$SRC/OgmCodec.kt" "$SRC/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$TMP/window.jar"
java -jar "$TMP/window.jar"
python3 tests/test_phase256_scope.py
printf 'PASS Phase 2.5.6 focused Android source/transport suite (not Gradle build)\n'
