# Phase 2.3 ESP-IDF v6.1, ESP32-C3

1. Confirm physical target ESP32-C3 and correct USB port before flashing.
2. Open the `firmware` directory in ESP-IDF environment.
3. `idf.py set-target esp32c3` **only if current project is not already targeting esp32c3**.
4. `idf.py reconfigure && idf.py build` (check 1.5 MiB factory partition fit).
5. `idf.py -p /dev/ttyACM0 flash` **for the phone-side ESP32 only**, using the correct port.
6. Do not erase flash/NVS. Keep your working backup firmware ZIP.
7. Connect Android Phase 2.3; check `Binary BLE capability confirmed; active=true`.
8. Only change the web-side ESP32 if there is a separate proven reason.

For original radio baseline and preservation details see `../README_PHASE_2_3.md`.
