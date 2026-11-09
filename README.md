# OffGrid Messenger

**Latest reconstructed version: `v2.5.6`**  
**Status:** Advanced two-node offline messenger prototype; source-tested portions, not independently security-audited or production-certified.  
**Hardware:** ESP32-C3 Super Mini (two radios), Android phone over BLE, optional desktop/browser over USB Serial/JTAG.  
**Connectivity:** BLE + ESP-NOW (including genuine Espressif LR PHY). **No internet required for messaging.**

## What we built

OffGrid Messenger began as an ESP32-to-ESP32 offline communication experiment. Over the archived releases, it became a connected system with an Android client, Web Serial browser client, encrypted text/photo/voice messages, delivery/read/played receipts, responsive chat UI, and adaptive long-range radio control. The radio link is primarily a **two-node point-to-point** connection, not a production multi-hop mesh.

```text
Android phone -- BLE -- ESP32-C3 ))) ESP-NOW / LR ((( ESP32-C3 -- USB -- browser
                              encrypted application messages
```

## Project structure

| Directory | Purpose |
|---|---|
| `android/` | Android Studio / Kotlin / Compose app, BLE transport, local encrypted history, voice/photo/UI |
| `firmware/` | ESP-IDF radio firmware, BLE node, USB protocol, adaptive PHY, encryption and host tests |
| `web/` | Browser Web Serial interface and protocol implementation |
| `docs/history/` | Release-by-release, source-grounded notes (`v1.0.0` to `v2.5.6`) |
| `docs/HISTORY.md` | Chronological release timeline and navigable version index |
| `SOURCE_MANIFEST.json` | Source ZIP names, archive SHA-256 values, diff statistics and inheritance notes |

**Source duplication note:** Depending on historical snapshot, `firmware/web/` may contain an original copy of the web interface used with the firmware project. `web/` is the standalone browser source. Update both intentionally when necessary and test cross-client behavior.

## Project story

1. **Phase 1.0–1.3 — Foundations:** ESP32 communication and the first Web Serial interface.
2. **Phase 1.4 — Reliability:** handshake fixes, watchdog recovery, USB input/output, selective ACKs, diagnostics, adaptive Wi-Fi PHY and genuine Espressif LR experiments.
3. **Phase 1.5–1.6 — Media and usability:** voice messages, better delivery receipts, browser refinement.
4. **Phase 2.0–2.3 — Android:** Android BLE, optimized latency, interoperable encrypted media, selective media acknowledgments and binary BLE throughput.
5. **Phase 2.4 — UI refinement:** polished Compose UI and multiple theme/interaction corrections.
6. **Phase 2.5 — Product polish and RF stability:** camera capture, 4:3 framing, rollback to approved UI, auto-follow navigation, RF-switching stability, live link telemetry and background-service improvements.

Read [the complete 47-release history](docs/HISTORY.md), or inspect an annotated release tag in Git. These are original **source snapshots**, reconstructed into Git commits; original development commit metadata cannot be recovered.

## Branches

| Branch | Use |
|---|---|
| `main` | Complete integrated source, official release tags |
| `app` | Android-specific development/history |
| `web` | Browser-specific development/history |
| `firmware` | ESP32-specific development/history |

[Branch policy](docs/BRANCHING.md) · [Reconstruction methodology](docs/RECONSTRUCTION.md)

## Developer and contributor guides

- [Development and installation](docs/DEVELOPMENT.md) — verified configuration facts, setup and limitations.
- [Architecture and compatibility](docs/ARCHITECTURE.md) — BLE/ESP-NOW/USB component boundaries.
- [Testing matrix](docs/TESTING.md) — safe checks and hardware verification requirements.
- [Contributing](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md) · [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md).
- [GitHub bug reports and feature requests](.github/ISSUE_TEMPLATE/) · [pull-request checklist](.github/PULL_REQUEST_TEMPLATE.md).

**Validation status:** the repository includes source and history but its Android snapshot lacks a Gradle wrapper, and full Android/ESP-IDF builds plus physical-device tests have not been run as part of this documentation update. No release version newer than `v2.5.6` is implied. The owner has not selected an open-source license or configured a private vulnerability-reporting channel; these must be addressed before inviting public contributions. The reconstructed Git identity is now PowerCodeX (see provenance notes).

## Getting started

**Firmware:** install the ESP-IDF toolchain matching the archived project and configure the target supported board; check `firmware/README.md` and target-specific instructions, then `idf.py build`, `idf.py -p PORT flash monitor`. **Do not erase NVS casually**—existing pairing and peer state may be lost.

**Android:** open `android/` in Android Studio, Gradle sync and build the APK. Connect to the BLE node and provision the shared 32-byte conversation key as required by the app/device setup flow. Compatible board firmware is required for manual radio control.

**Web:** open the Web Serial browser client in a compatible Chromium-family browser under an appropriate secure context, connect the ESP32 USB serial interface, and use the same conversation key.

These are starting points; follow component-specific README and validation files for exact historical build requirements. No complete cross-platform build was performed during Git reconstruction.

## Known limitations

Two-node pairing rather than mesh routing; peer MAC persistence can complicate board replacement; no durable, guaranteed store-and-forward; range depends heavily on onboard antennas and conditions; Android background behavior depends on OEM power policies; Wi-Fi/BLE sharing and frequent telemetry favor responsiveness over minimum power; complete application security has not been independently audited. **Do not depend on this project as a certified emergency communication system.**

## Security and publishing

Application-layer encryption and on-device storage protection are present, but a fixed shared conversation key, physical key retrieval interfaces and lack of independent audit are important caveats. **Do not publish real keys, NVS dumps, keystores, private device data or signed production credentials.** The owner has requested a **public GitHub repository**, but publication is **pending** a thorough secrets review, rights/licensing decision and private vulnerability-reporting setup. No GitHub push has occurred. Read [security and release checklist](docs/SECURITY_AND_RELEASE.md).

## Version history integrity

Reconstruction includes **47 archived source releases** from `v1.0.0` to `v2.5.6`. Git timestamps are synthetic, and some components were carried forward when a snapshot included only one component. Original ZIP checksums and per-version provenance are in `SOURCE_MANIFEST.json`. A full Git bundle and verified offline repository ZIP accompany this repository.

## Project ownership and Git attribution

**Project maintainer / reconstructed commit identity:** PowerCodeX (`powercodex369@gmail.com`). All reconstructed commits and annotated tags have been attributed to this chosen identity as a repository-maintenance convention, **not as verified evidence of who authored each original historical change**. The original author and committer identities were not present in the archived ZIP releases. Original timestamps also were not recoverable. See [provenance](docs/RECONSTRUCTION.md). New contributors retain their own authorship credit on future commits.

## License

No open-source license has been selected by the project owner. **All rights are reserved unless and until a license is explicitly granted.** Please choose a license and review third-party assets before making the repository public.
