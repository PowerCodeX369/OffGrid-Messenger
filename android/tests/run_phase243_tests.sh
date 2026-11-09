#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/test_phase243_trueglass.py
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/HiddenMessageIds.kt" "$src/VoiceGestureRules.kt" tests/phase242/HiddenGestureTest.kt -include-runtime -d "$TMP/hci.jar"
java -jar "$TMP/hci.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TMP/history.jar"
java -jar "$TMP/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$TMP/mutations.jar"
java -jar "$TMP/mutations.jar"
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TMP/ble.jar"
java -jar "$TMP/ble.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$TMP/window.jar"
java -jar "$TMP/window.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaBinaryWindowContractTest.kt -include-runtime -d "$TMP/bwin.jar"
java -jar "$TMP/bwin.jar"
echo 'PASS all Phase 2.4.3 host tests; full Android SDK compile remains unverified'
