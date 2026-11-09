# System architecture and compatibility

```text
Android app (Kotlin / Compose)
           | BLE GATT
           v
ESP32-C3 A <---- ESP-NOW / Espressif LR ----> ESP32-C3 B
                                             | USB Serial/JTAG
                                             v
                                        Chromium Web Serial UI
```

The primary topology is **two nodes**, not a routed multi-hop mesh. OffGrid messaging is designed to function without Internet access. Android and Web clients interact through distinct local transports; the ESP32 radio nodes carry application messages over ESP-NOW. Refer to the component implementation and version notes for exact packet details—this document does not promise compatibility with official Meshtastic/LoRa firmware.

## Components

| Path | Responsibility | Validation caveat |
|---|---|---|
| `firmware/` | ESP32-C3 BLE, USB transport, ESP-NOW radio and application packet handling | Build/flash requires ESP-IDF and real boards |
| `android/` | Android BLE transport, local history, media, Compose UI | Requires Android SDK/Gradle and a real handset for BLE verification |
| `web/` | Chromium Web Serial UI and protocol | Requires compatible browser, secure context and USB device |
| `firmware/web/` | Firmware-snapshot copy of the browser assets | Keep changes synchronized intentionally |

## Safety-sensitive compatibility boundaries

- Maintain the current Android application ID (`com.offgrid.messenger`) and signing identity for in-place updates.
- Preserve already provisioned ESP32 pairing/NVS state. Do not erase flash or change keys without an explicit migration plan.
- When altering wire format, media encoding, pairing or authentication, change both endpoints and add interoperability checks.
- Hardware RF range and delivery are environment-dependent; tests using mocks do not verify physical range.

## History

The v1.0.0–v2.5.6 Git history was reconstructed from release ZIP snapshots. Inspect [docs/RECONSTRUCTION.md](RECONSTRUCTION.md) before using commit ancestry to infer original author decisions.
