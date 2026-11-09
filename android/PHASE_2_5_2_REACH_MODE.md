# Phase 2.5.2 — Android Reach mode / message visibility / settings density

See `../README_FIRST.md` in the complete archive or the top-level README of the Android-only ZIP.

- User-entered Reach mode under chat **⋮**: composer below header; compact recent-message viewport.
- Correctly distinguish Android's one-handed **system translation** from Android window resizing. No public system-mode detection is relied upon.
- Fresh `SnapshotStateList`-derived messages, stable key `ChatItem.id`, and read-visibility tracking that does not restart on every recomposition.
- Bottom gesture-bar inset on the Chats screen; tightened settings and an expandable device-options group.
- **Only** `PremiumUi.kt` and app `build.gradle.kts` changed among prior app source/resources. Existing firmware remains untouched.
