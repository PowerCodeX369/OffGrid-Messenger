#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
src=app/src/main/java/com/offgrid/messenger
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$src/HiddenMessageIds.kt" "$src/VoiceGestureRules.kt" tests/phase242/HiddenGestureTest.kt -include-runtime -d "$TMP/newhci.jar"
java -jar "$TMP/newhci.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TMP/history.jar"
java -jar "$TMP/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$TMP/mutations.jar"
java -jar "$TMP/mutations.jar"
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TMP/ble.jar"
java -jar "$TMP/ble.jar"
python3 tests/phase242/test_ui_contract.py
printf 'PASS Phase 2.4.2 host regression suite; full Android Gradle build remains unverified\n'
