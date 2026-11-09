# Source history and archive provenance

This repository was rebuilt from **47 available ZIP release archives**, from Phase 1 to Phase 2.5.6. They are *snapshots*, not the original Git commits: **original timestamps, authors, unarchived intermediate revisions and commit messages cannot be recovered**. Synthetic commit dates record only reconstruction order. Commit messages contain grounded file-diff summaries; annotated tags identify each archived release. See `SOURCE_MANIFEST.json` for original archive names and SHA-256 hashes.

When an archive contains one component but not another, the absent component is carried forward from the previous release snapshot. Such inherited files are called out in every release note; do not mistake them for binaries rebuilt for that release. Both v2.5.3 and v2.5.5 are examples of component-specific snapshots.

The `main` branch represents the integrated project. The `app`, `web`, and `firmware` branches are **independent filtered histories** reconstructed from the same snapshots. Use them for isolated work; integrate changes intentionally through pull requests or cherry-picks after testing. Do not blindly merge component branches with `main`.

Source archives are *not* embedded within Git to avoid duplicating historical artifacts and inadvertently committing hidden files. SHA-256 values are provided for validating the original ZIPs.

## Identity rewrite for pre-publication preparation (2026-11-09)

At the project owner's request, the reconstructed Git commits on `main`, `app`, `web`, and `firmware`, and the annotated release tags, were rewritten to the chosen publication identity **PowerCodeX <powercodex369@gmail.com>**. The rewrite did **not** recover the original authors, commit dates or original version-control records; those remain unknown. It deliberately changes all historical Git object IDs, so earlier commit hashes and annotated tag object hashes are no longer valid identifiers for this edition. Source trees associated with historical version tags were preserved. For archival checksums of the original release ZIP files, consult `SOURCE_MANIFEST.json`.

This identity represents stewardship and reconstruction of the release snapshots, not an assertion that every historical change was personally authored at the synthetic Git commit timestamp. Later human contributions should be credited to their actual authors.
