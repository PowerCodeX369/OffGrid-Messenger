# Security and publishing checklist

**Publication status: PUBLIC REQUESTED, NOT YET PUBLISHED OR AUDITED.** Before making this repository public, inspect *all historical tags*, not only `HEAD`. Git history can retain secrets even if later versions remove them.

- Search entire reachable Git history for pairing keys, NVS dumps, keystores, credentials, certificates and user-specific device addresses. A simple pattern scan is not a substitute for `gitleaks` / `trufflehog` or manual review.
- Never commit real device encryption keys (`KEYGET` output), provisioned NVS partitions, signing keys, IDE credentials, crash reports containing personal data, or production secrets.
- The source contains key-provisioning interfaces and intentionally uses long-lived shared keys. Keep the repository **private** until the threat model is reviewed.
- Test the Android build with a supported Android Studio toolchain, and firmware with the target ESP-IDF toolchain. This reconstruction does **not** certify full builds.
- Preserve the Android signing certificate for updates to an existing app. Do not commit the certificate/private key.
- **Rights decision:** Do not add a LICENSE until the owner selects terms and third-party rights are checked. A public GitHub repository without a license is viewable but does not grant general reuse rights.
- **Before enabling public access:** configure GitHub private vulnerability reporting, review historic objects and archives with a dedicated history-aware secrets scanner, and resolve licensing and ownership of third-party code/assets.
- Attribution policy: PowerCodeX is the chosen reconstructed-history identity; original authors and dates remain unknown. New contributors keep their individual authorship credit.
