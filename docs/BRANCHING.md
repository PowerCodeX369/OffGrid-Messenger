# Branch strategy

- **main** — complete integration tree: `android/`, `web/`, `firmware/`, `docs/`; authoritative latest source.
- **app** — Android-only source plus its release timeline.
- **web** — browser app source plus its release timeline.
- **firmware** — ESP-IDF project source plus its release timeline.

Historical commit messages on the specialized branches are reconstructed from the relevant component changes. A historical version can have no changes in a component; corresponding tag exists on `main`, while component branches record only changed component states. For daily work, develop in these branches and merge reviewed changes into `main` only after cross-component compatibility tests. Prefer feature branches off `main` for cross-layer protocol changes. Do not assume Git can automatically merge unrelated reconstructed commit ancestries.
