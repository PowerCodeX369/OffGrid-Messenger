# OffGrid Messenger roadmap — after Phase 1.6.1

Completed in this release:
- Opt-in desktop notifications, with encrypted-content privacy by default
- Unread chat badges and browser-title count, cleared on actual local viewing
- Calmer dark/light design
- Subtle CSS composer, message, and receipt transitions; reduced-motion fallback

Preserved without changes:
- Text, photo and 120-second voice messaging
- Encrypted played receipts and blue read ticks
- Browser-side AES-256-GCM, keys in ESP32 NVS, adaptive ESP-NOW / LR policy
- Interrupt-driven USB Serial/JTAG driver

Next main development milestone: Android BLE companion, after the user confirms this web release. Outdoor range and long-duration field testing will be performed later during regular usage, as requested. GPS is out of scope.
