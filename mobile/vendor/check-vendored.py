#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Prove the vendored Gadgetbridge code is upstream plus our patches, nothing else.

Tracks keeps a copy of Gadgetbridge's Garmin layer under device-garmin/, pinned
to one release, and records every change it makes to that copy as a patch in
vendor/patches/. pull-gadgetbridge.sh refreshes the copy by overwriting it and
re-applying the patches, so an edit that is not a patch is silently lost on
the next refresh. That happened: on 2026-09-30, 17 vendored files were found
carrying Tracks changes no patch recorded — the music-server proxy, upload
refusal reporting, the activity recovery listing among them.

This script rebuilds "upstream at the pinned release + every patch, in order"
in a temporary directory and compares it, file by file, with the tree. It only
reads the repository. With --write-lock, and only when everything matches, it
writes vendor/gadgetbridge.lock: a hash of every vendored file, which
VendoredLockTest checks on every test run with no network and no checkout —
so an edit to a vendored file without a patch fails the build the same day,
instead of disappearing at the next refresh.

Usage:
    vendor/check-vendored.py <gadgetbridge-checkout-at-pinned-release> [--write-lock]
    vendor/check-vendored.py --verify-lock

--verify-lock needs no checkout: it only compares the tree with the lock, and
is what pull-gadgetbridge.sh runs before it overwrites anything.

The checkout must be at the release named in device-garmin/VENDORED.md.
"""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
JAVA = REPO / "mobile/device-garmin/src/main/java"
PROTO = REPO / "mobile/device-garmin/src/main/proto"
LOCK = HERE / "gadgetbridge.lock"


def vendored_files() -> list[tuple[Path, str]]:
    """(path in this repo, path in the Gadgetbridge checkout) for every vendored file."""
    out = []
    for p in sorted((JAVA / "nodomain").rglob("*")):
        if p.suffix in (".java", ".kt"):
            out.append((p, "app/src/main/java/" + str(p.relative_to(JAVA))))
    for p in sorted(PROTO.rglob("*.proto")):
        out.append((p, "app/src/main/proto/" + str(p.relative_to(PROTO))))
    return out


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_lock() -> int:
    if not LOCK.exists():
        print("no vendor/gadgetbridge.lock — run with a checkout and --write-lock first")
        return 1
    locked = {}
    for line in LOCK.read_text().splitlines():
        if line and not line.startswith("#"):
            digest, path = line.split("  ", 1)
            locked[path] = digest
    current = {str(local.relative_to(REPO)): sha256(local) for local, _ in vendored_files()}
    bad = sorted(p for p in set(locked) | set(current) if locked.get(p) != current.get(p))
    if bad:
        print(f"{len(bad)} vendored file(s) differ from the lock — unrecorded edits a refresh would lose:")
        for p in bad:
            print("  ", p)
        return 1
    print(f"OK: {len(current)} vendored files match the lock.")
    return 0


def main() -> int:
    if "--verify-lock" in sys.argv:
        return verify_lock()
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    write_lock = "--write-lock" in sys.argv
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    upstream = Path(args[0])

    files = vendored_files()
    with tempfile.TemporaryDirectory() as tmp:
        scratch = Path(tmp)
        missing = []
        for local, up in files:
            src = upstream / up
            dst = scratch / local.relative_to(REPO)
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.exists():
                shutil.copyfile(src, dst)
            else:
                missing.append(str(local.relative_to(REPO)))
        if missing:
            print(f"{len(missing)} vendored file(s) are not in the upstream checkout — wrong release?")
            for m in missing[:20]:
                print("  ", m)
            return 1

        subprocess.run(["git", "init", "-q"], cwd=scratch, check=True)
        for patch in sorted((HERE / "patches").glob("*.patch")):
            r = subprocess.run(["git", "apply", str(patch)], cwd=scratch, capture_output=True, text=True)
            if r.returncode:
                print(f"{patch.name} does not apply to upstream: {r.stderr.strip()}")
                return 1

        drifted = [str(local.relative_to(REPO)) for local, _ in files
                   if (scratch / local.relative_to(REPO)).read_bytes() != local.read_bytes()]

    if drifted:
        print(f"{len(drifted)} vendored file(s) differ from upstream + patches.")
        print("Record each change as a patch (git diff against the rebuilt baseline) and")
        print("explain it in device-garmin/SHIMS.md, or the next refresh will drop it:")
        for d in drifted:
            print("  ", d)
        return 1

    print(f"OK: {len(files)} vendored files are exactly upstream + "
          f"{len(list((HERE / 'patches').glob('*.patch')))} patches.")
    if write_lock:
        LOCK.write_text(
            "# Generated by vendor/check-vendored.py --write-lock. Do not edit.\n"
            "# sha256 of each vendored file as upstream + vendor/patches produce it.\n"
            + "".join(f"{sha256(local)}  {local.relative_to(REPO)}\n" for local, _ in files)
        )
        print(f"wrote {LOCK.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
