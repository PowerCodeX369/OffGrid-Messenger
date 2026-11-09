# Post-v2.5.6 integrated repository layout repair

## Problem

The Git reconstruction recorded the latest `v2.5.6` **integrated** snapshot but left root `web/` at the older browser v1.6.1 state. The more recent browser v2.4.1 files from the v2.4.1 source release were stored only in `firmware/web/`. Thus the v2.5.6 tag faithfully preserved the *reconstructed* snapshot while the root browser app was misleadingly stale.

## Current `main` resolution

- Promote all six v2.4.1 browser assets to root `web/`: `app.js`, `index.html`, `protocol.js`, `adaptive.js`, `styles.css`, and `logo.svg`.
- Remove the maintained `firmware/web/` duplicate. Firmware is an ESP-IDF project for radio, BLE and USB, not a host of the Web Serial HTML files; the firmware CMake configuration does not embed them.
- Redirect firmware and Android cross-client regression tests to the root browser source.
- Preserve the original 47 historical annotated Git tags and component branches. **Do not re-tag v2.5.6**: it is a historical snapshot with known layout problems. This correction is a new post-release maintenance commit on `main`.
- Ensure repository checks fail if the stale nested web folder is accidentally reintroduced.

## What was checked and what remains unknown

Diffs, JavaScript syntax, Node-based crypto/protocol tests, selected Python static contract checks, Git integrity, and repository structure can be checked without hardware. Full ESP-IDF and Android builds, a browser/device session, and physical radio communication are **not** established by those checks. Original source archives beyond those included in this reconstructed Git repository have not been independently re-audited to rule out newer unpublished web changes.

## GitHub migration

Create a backup copy or `git bundle` before replacing a published repository. If replacing the existing GitHub `main` with this repaired repository, prefer a normal fast-forward push when its parent history matches. Do **not** delete/recreate the GitHub project or force-push without first reviewing remote commits; both can lose collaboration records or overwrite work by others.
