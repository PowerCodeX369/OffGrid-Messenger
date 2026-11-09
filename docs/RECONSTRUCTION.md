# Source history and archive provenance

This repository was rebuilt from **47 available ZIP release archives**, from Phase 1 to Phase 2.5.6. They are *snapshots*, not the original Git commits: **original timestamps, authors, unarchived intermediate revisions and commit messages cannot be recovered**. Synthetic commit dates record only reconstruction order. Commit messages contain grounded file-diff summaries; annotated tags identify each archived release. See `SOURCE_MANIFEST.json` for original archive names and SHA-256 hashes.

When an archive contains one component but not another, the absent component is carried forward from the previous release snapshot. Such inherited files are called out in every release note; do not mistake them for binaries rebuilt for that release. Both v2.5.3 and v2.5.5 are examples of component-specific snapshots.

The `main` branch represents the integrated project. The `app`, `web`, and `firmware` branches are **independent filtered histories** reconstructed from the same snapshots. Use them for isolated work; integrate changes intentionally through pull requests or cherry-picks after testing. Do not blindly merge component branches with `main`.

Source archives are *not* embedded within Git to avoid duplicating historical artifacts and inadvertently committing hidden files. SHA-256 values are provided for validating the original ZIPs.
