#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)";trap 'rm -rf "$tmp"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$tmp/binary.jar"
java -jar "$tmp/binary.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$tmp/window.jar"
java -jar "$tmp/window.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaBinaryWindowContractTest.kt -include-runtime -d "$tmp/binary-window.jar"
java -jar "$tmp/binary-window.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowSpeedModel.kt -include-runtime -d "$tmp/model.jar"
java -jar "$tmp/model.jar"
python3 tests/test_ble_latency_contract.py
python3 tests/test_phase21_contract.py
python3 ../firmware/tests/test_phase23_integrity.py
python3 ../firmware/tests/test_phase23_integrity.py
# Phase2.2 version/preservation checks replaced by 2.3 integrity checks.
kotlinc tests/mock_android/*.kt "$src/BleBinaryCodec.kt" "$src/BleNodeClient.kt" -include-runtime -d "$tmp/gatt.jar"
java -cp "$tmp/gatt.jar" com.offgrid.messenger.BleClientContractTestKt
java -cp "$tmp/gatt.jar" com.offgrid.messenger.BinaryGattTestKt
