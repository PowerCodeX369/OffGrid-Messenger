# OffGrid Android Phase 2.5.1

A presentation-only upgrade from Phase 2.5. Message payload formats, pairing, BLE binary transport, ACK windows, and ESP32 firmware are unchanged.

The new `ConversationUiRules.kt` governs unread counts and timeline auto-follow. It does not change radio delivery receipts. Incoming messages still become read when the chat is actually visible and the established READ control is sent; the unread badges are derived from the same local receipt state that is saved in encrypted history.

The chat timeline now uses a reverse LazyColumn with stable message IDs. Newest messages anchor at index 0, visually the bottom. Incoming messages follow while near the bottom; self-sent messages follow regardless. This avoids jarring jumps when reviewing earlier conversation history.

CameraX uses 4:3 aspect for preview and capture with FIT_CENTER. The actual preview is bounded to a 4:3 window and may have surrounding blank margins. Captured images still use the existing compression and encrypted transfer pipeline.
