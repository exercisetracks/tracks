#!/usr/bin/env python3
"""Prepend REUSE-style SPDX headers to tracked source files.

Idempotent: files that already carry an SPDX-License-Identifier are skipped.
Shebang lines are preserved as line 1.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COPYRIGHT = "SPDX-FileCopyrightText: 2026 Hawk Fugagli"
LICENSE = "SPDX-License-Identifier: AGPL-3.0-or-later"

COMMENT = {".py": "#", ".sh": "#", ".js": "//", ".jsx": "//"}

# Generated or vendored trees that should not be stamped.
EXCLUDE_PARTS = {"node_modules", "dist", "__pycache__", ".venv", "build"}

# Vendored third-party source. Stamping these with a Tracks copyright would
# misattribute someone else's work, and the next `vendor/pull-gadgetbridge.sh`
# would overwrite the stamp anyway. Their licensing is declared in bulk in
# REUSE.toml instead.
EXCLUDE_PREFIXES = ("mobile/device-garmin/src/main/java/nodomain/",
                    "mobile/device-garmin/src/main/proto/")

dry_run = "--apply" not in sys.argv

files = subprocess.run(
    ["git", "ls-files", "*.py", "*.js", "*.jsx", "*.sh"],
    cwd=ROOT, capture_output=True, text=True, check=True,
).stdout.split()

stamped = skipped = excluded = 0
for rel in files:
    path = ROOT / rel
    if EXCLUDE_PARTS & set(Path(rel).parts) or rel.startswith(EXCLUDE_PREFIXES):
        excluded += 1
        continue

    text = path.read_text(encoding="utf-8")
    if not text.strip():
        excluded += 1
        continue
    if "SPDX-License-Identifier" in text:
        skipped += 1
        continue

    c = COMMENT[path.suffix]
    header = f"{c} {COPYRIGHT}\n{c} {LICENSE}\n"

    lines = text.split("\n")
    if lines[0].startswith("#!"):
        new = lines[0] + "\n" + header + "\n".join(lines[1:])
    else:
        new = header + text

    if not dry_run:
        path.write_text(new, encoding="utf-8")
    stamped += 1

verb = "would stamp" if dry_run else "stamped"
print(f"{verb}: {stamped}  already-had: {skipped}  excluded: {excluded}")
if dry_run:
    print("\nre-run with --apply to write")
