# Phase 1.6.1 validation

## Local checks

- JavaScript syntax validation for `web/app.js`, `web/protocol.js`, `web/adaptive.js`.
- Existing Node protocol, selective ACK, voice and diagnostic unit tests.
- Existing Playwright/Chromium voice recording (short and two-minute), played receipts, and auto-scroll regression tests.
- New Chromium tests at desktop and narrow-screen sizes:
  - Notification permission requested only on clicking the bell; default off.
  - Unread badges in sidebar and contact; encrypted local viewing state; title count.
  - One alert per new message while tab is in background; no alerts while foreground.
  - No duplicate notifications on duplicate messages; preview private by default.
  - Badges clear upon foreground viewing, and outbound read receipts remain independent.
  - Notification toggle-off and theme stability.
  - Mic-to-send transition, text send and revert to mic, blank input safety.
- Firmware and crypto source files compared by SHA-256 to the user-approved 1.6 build; expected to be byte-for-byte identical.
- Host-side C syntax checks and pure C policy tests.
- ZIP CRC and archive integrity.

## Not verified here

- Physical ESP32 throughput, range or RF loss recovery (no radio changes were made).
- Native KDE/Windows/macOS notification-daemon delivery, because automation uses a mocked browser Notification implementation.
- User's acceptance of visual refinements; actual UI should be reviewed in Chromium.

**No ESP32 firmware flash required.**
