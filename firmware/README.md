# OffGrid Messenger — Phase 1.3

This package contains the same working protocol-2 ESP-IDF firmware and an upgraded professional web interface.

## What is new in Phase 1.3

- Persistent chat history in browser local storage
- Contact name persistence per ESP32 node
- Improved logo and branding
- WhatsApp-style overlapping receipt ticks
- Smoother UI animations and transitions
- Clear chat history option

## Firmware

If your boards are already running the working Phase 1.2 firmware, you can usually keep that firmware because the wire protocol is unchanged. Reflash only if needed.

## Web UI

Run:

```bash
python3 -m http.server 8765 --directory web
```

Open:

```
http://localhost:8765/?v=1.3.0
```

Hard refresh each tab after opening.
