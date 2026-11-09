# Contributing to OffGrid Messenger

Thank you for your interest. OffGrid Messenger is an experimental two-node communication system, **not an audited emergency communications service**. Please open an issue to discuss a proposed change before implementing protocol-breaking changes.

## Before you contribute

- Read [README.md](README.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) and [docs/RECONSTRUCTION.md](docs/RECONSTRUCTION.md).
- **License status:** no license has been granted yet. A public repository does not automatically grant a right to reuse or redistribute the code. Obtain the owner's permission before contributing code; the owner must establish contribution terms before accepting outside PRs.
- Do not submit secret keys, pairing data, NVS dumps, private addresses, keystores, or personal logs. Scrub screenshots and logs.
- Do not assume the firmware and Android client may be upgraded independently: document compatibility whenever protocol behavior changes.

## Change process

1. Branch from `main` using `fix/<topic>`, `feat/<topic>`, or `docs/<topic>`; **do not rewrite historical tags**.
2. Make the smallest targeted change; keep ESP32, Android and Web protocol compatibility in mind.
3. Run the relevant commands in [docs/TESTING.md](docs/TESTING.md), recording results and skipped tests.
4. Update the component README and [CHANGELOG.md](CHANGELOG.md) for user-visible changes. Include any migration, flash, NVS, pairing, key, or signing implications.
5. Open a pull request against `main` with steps to reproduce, commands and outputs, connected-device test results where applicable, and security considerations.

`app`, `web`, and `firmware` are reconstructed component-history branches. For new changes, **prefer feature branches from `main`** rather than attempting automatic merges of unrelated reconstructed ancestry. Use an explicit maintainer decision for changes to component branches.

## Coding and review expectations

- Keep changes limited in scope; follow existing language conventions (C/ESP-IDF, Kotlin/Compose, JavaScript).
- Never silently erase/reset NVS or change Android package ID/signing identity.
- Test on real devices before claiming BLE, RF, background execution or flashing behavior verified.
- Clearly label simulated, contract, and hardware results. Do not claim encryption or RF range is independently certified.
- A maintainer reviews changes before merge; do not submit generated release files/build outputs.

## Contributor credit

The project owner controls author and maintainer credits. New contributors should author their own commits using their chosen verified Git identity. The older history was reconstructed from archived releases, and is **not** proof of historical individual commit authorship.
