#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)";trap 'rm -rf "$tmp"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$tmp/history24.jar"
java -jar "$tmp/history24.jar"
python3 tests/test_phase24_contract.py
python3 tests/test_phase24_preservation.py
# Re-run both actual Phase 2.3 sender/window logic tests on the SAME preserved source files.
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$tmp/binary.jar"
java -jar "$tmp/binary.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$tmp/window.jar"
java -jar "$tmp/window.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaBinaryWindowContractTest.kt -include-runtime -d "$tmp/binary-window.jar"
java -jar "$tmp/binary-window.jar"
python3 tests/test_ble_latency_contract.py
