#!/usr/bin/env bash
# Execute INSIDE a properly activated ESP-IDF environment on the user's machine.
# This script does not flash or erase NVS.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
project="$(pwd)"
log="$project/esp-idf-actual-build.log"
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v idf.py >/dev/null 2>&1 || fail 'idf.py not found. Open the ESP-IDF Extension Terminal, or run source ~/.espressif/v6.1/esp-idf/export.sh (adapt to your actual path).'
command -v cmake >/dev/null 2>&1 || fail 'CMake missing from your IDF environment.'
command -v ninja >/dev/null 2>&1 || fail 'Ninja missing from your IDF environment.'
: "${IDF_PATH:?ESP-IDF not activated: IDF_PATH is empty. Use the ESP-IDF extension terminal.}"
[[ -d "$IDF_PATH/components/bt" ]] || fail "Bluetooth component missing under $IDF_PATH/components/bt. Repair/update ESP-IDF installation."
[[ -d "$IDF_PATH/components/mbedtls" ]] || fail "Mbed TLS component missing under $IDF_PATH/components/mbedtls. Repair/update ESP-IDF installation."
version="$(idf.py --version)"; printf 'ESP-IDF: %s\n' "$version"
case "$version" in *6.1*) : ;; *) echo 'WARNING: built for ESP-IDF v6.1; check for API mismatches if using another version.' ;; esac
printf 'Project: %s\n' "$project"
printf 'IDF_PATH: %s\n' "$IDF_PATH"
# Preserve sdkconfig/NVS. If stale previous build, cleanup build cache only.
if [[ -e build/CMakeCache.txt ]] && ! grep -Fq "$project" build/CMakeCache.txt; then
  echo 'Old project path detected in build cache; deleting ONLY build/ (not sdkconfig / NVS).'
  rm -rf build
fi
# If sdkconfig is absent, idf.py will merge sdkconfig.defaults.
# An existing sdkconfig takes precedence; verify actual generated values.
printf '\n[1/3] Reconfigure (real installed ESP-IDF)...\n'
set +e
idf.py reconfigure 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
set -e
if [[ $rc != 0 ]]; then
  echo "ESP-IDF reconfigure FAILED ($rc). First important errors:"
  grep -nEm 12 'CMake Error|fatal error:|error:|Failed to|not found|missing' "$log" || true
  fail "Send esp-idf-actual-build.log; do NOT flash."
fi
[[ -f sdkconfig ]] || fail 'No sdkconfig created after reconfigure.'
for cfg in CONFIG_IDF_TARGET_ESP32C3=y CONFIG_BT_ENABLED=y CONFIG_BT_NIMBLE_ENABLED=y CONFIG_BT_NIMBLE_ROLE_PERIPHERAL=y CONFIG_BT_NIMBLE_GATT_SERVER=y CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG=y; do
  if ! grep -Fxq "$cfg" sdkconfig; then
    echo "MISSING REQUIRED CONFIG: $cfg" >&2
    echo 'Open idf.py menuconfig, enable ESP32-C3, Bluetooth -> NimBLE peripheral and GATT server, then save.' >&2
    fail 'Current sdkconfig settings do not enable the required transport; no flash.'
  fi
done
# An existing sdkconfig may override sdkconfig.defaults, especially after an earlier build.
for config in 'CONFIG_PARTITION_TABLE_CUSTOM=y' 'CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions.csv"'; do
  if ! grep -Fxq "$config" sdkconfig; then
    echo "ACTIVE CONFIG IS STALE: missing $config" >&2
    echo 'Run: idf.py menuconfig -> Partition Table -> Custom partition table CSV, filename partitions.csv; Save.' >&2
    fail 'App still uses old partition table; refuse to flash.'
  fi
done
printf 'Partition table: custom partitions.csv (1.5 MiB app).\n'

printf '\n[2/3] Compile BLE + radio using actual ESP-IDF headers/toolchain...\n'
set +e
idf.py build 2>&1 | tee -a "$log"
rc=${PIPESTATUS[0]}
set -e
if [[ $rc != 0 ]]; then
  echo "ESP-IDF compile FAILED ($rc). First important errors:"
  grep -nEm 16 'CMake Error|fatal error:|error:|undefined reference|region .* overflowed|FAILED:|not found' "$log" || true
  fail "Do NOT flash. Send $log and build output for repair."
fi
printf '\n[3/3] Validate artifacts / toolchain results...\n'
[[ -f build/compile_commands.json ]] || fail 'No compile_commands.json; compilation database unavailable.'
grep -Fq 'ble_bridge.c' build/compile_commands.json || fail 'BLE file not present in actual compiler commands.'
[[ -f build/project_description.json ]] || fail 'project_description.json missing.'
echo 'PASS: Real ESP-IDF configured, BLE enabled, both C sources compiled and linked.'
echo "Build log: $log"
echo 'Firmware NOT flashed. Review output before testing hardware.'
