# OffGrid Messenger — Phase 1.6.1

## What's new

Built directly on the **user-approved Phase 1.6 Premium Glass UI**, with the working 1.5.1 voice messages and played receipts preserved.

- More restrained, Apple-like styling in both dark and light themes: softer glass, neutral surfaces, subtle green accents, smaller radii and quieter shadows.
- Desktop notification bell in the top-left toolbar. Click it to ask the browser for permission. Notifications are **off by default** and can be disabled again with the same bell or from Settings.
- Private-by-default notification bodies ("New encrypted message", "An encrypted voice note arrived", or "An encrypted photo arrived"). Optional preview of plaintext messages can be switched on in Settings. Never place the pairing key in notifications.
- Unread counters beside **Chats**, beside the contact, and in the browser tab title. These clear when you actually view the conversation, independently of whether a radio read receipt was sent yet.
- Existing WhatsApp-style mic/send button morph remains intact. Composer focus, incoming/outgoing bubbles, and delivery/read receipt transitions have subtler CSS animations.
- Obeys `prefers-reduced-motion`.

## Installation — web-only

**Do not flash your ESP32 boards.** The firmware, AES-GCM protocol, and adaptive/radio modules have not changed relative to 1.6.

Run in the extracted directory:

```bash
python3 -m http.server 8765 --directory web
```

Open two Chromium tabs at `http://localhost:8765/?v=1.6.1`. Hard-refresh both tabs to load the new scripts and CSS. Connect each tab to its own ESP32 through the existing Web Serial flow.

## Enabling desktop notifications on Linux KDE

1. Click the **bell** in the upper-left toolbar and allow notifications in Chromium's site permission prompt.
2. Keep the Chromium tab running and connected to the ESP32 (it may be in the background).
3. Send a message from the other node while this tab is not focused. The OS notification should appear (unless KDE Do Not Disturb or the system/browser blocks it).
4. Click the notification to return to the conversation. The unread badges will clear when you view it.

You can also use **Settings → Desktop notifications**. Message previews are off by default.

Browser notifications depend on the browser/OS permission manager and notification service. Native notification appearance and timing cannot be made identical across Linux KDE, Windows, and macOS; **in-app animations** are CSS-driven and behave consistently across Chromium platforms.

These notifications are local to the page's active USB-connected session. They cannot arrive while Chromium or the connected tab is closed; there is no cloud server, web push service, or background daemon in this project.

## Technical details

- Unread tracking is persisted inside the existing AES-GCM-encrypted IndexedDB message record (`seenLocally`). Pairing keys remain only in ESP32 NVS and browser memory.
- Existing `readSent` remains a separate flag for remote read receipts; a weak radio link cannot leave the local unread badge stuck.
- Message arrival is deduplicated by existing message ID, and one notification is produced per newly accepted incoming message while the page is in the background.
- The working 1.4.11 LR stable baseline, 1.5 voice firmware, 1.5.1 played receipts, and 1.6 design source files are retained as separate rollback archives. This release is **not yet promoted** until tested by the user.

## Verification

See `VALIDATION.md` and `TEST_RESULTS.txt`. Browser notification tests use a simulated Notification API and do **not** claim to verify the actual Linux notification daemon or ESP32 hardware.
