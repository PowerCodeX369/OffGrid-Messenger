#!/usr/bin/env python3
"""Offline, read-only metadata checks. Does not compile or flash devices."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ["README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", "docs/HISTORY.md", "docs/RECONSTRUCTION.md", "android/app/build.gradle.kts", "firmware/CMakeLists.txt", "web/index.html", "SOURCE_MANIFEST.json"]

def validate(root=ROOT):
    errors = [f"Missing: {x}" for x in REQUIRED if not (root / x).is_file()]
    try:
        manifest = json.loads((root / 'SOURCE_MANIFEST.json').read_text())
        if not isinstance(manifest, (dict, list)):
            errors.append('Manifest must be an object or array')
    except (OSError, ValueError) as exc:
        errors.append(f'Invalid SOURCE_MANIFEST.json: {exc}')
    tags = sorted((root / 'docs/history').glob('v*.md'))
    if len(tags) != 47:
        errors.append(f'Expected 47 release history documents, found {len(tags)}')
    if not (root / 'docs/history/v2.5.6.md').is_file():
        errors.append('Missing v2.5.6 release notes')
    return errors

if __name__ == '__main__':
    problems = validate()
    if problems:
        for issue in problems:
            print('FAIL:', issue)
        raise SystemExit(1)
    print('PASS: repository metadata and 47 release documents checked (not a build or security audit)')
