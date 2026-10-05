#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate the Android resources the vendored Gadgetbridge code refers to.

The vendored protocol code names 600-odd resources — mostly `R.string` labels
and `R.drawable` icons for activity types. They are display concerns Tracks
does not use (it renders its own labels from spec/sport_taxonomy.yaml), but
they have to *resolve*, or nothing compiles.

Three things make this a generator rather than a checked-in file:

  * There are ~600 of them, and 508 come from one file (model/ActivityKind.java).
    Hand-maintaining that list is a standing invitation to drift.
  * Upstream adds activity types regularly. A refresh should pick them up
    without anyone noticing they need to.
  * Strings are copied from Gadgetbridge (AGPL, same licence, credited in
    NOTICE), but its *icons* are not — Gadgetbridge's artwork is under a
    separate licence (LICENSE.artwork upstream) which we deliberately do not
    vendor. So every drawable becomes an alias to one placeholder of our own.

Run from vendor/pull-gadgetbridge.sh, which knows where the checkout is.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent / "device-garmin"
RES = MODULE / "src" / "main" / "res" / "values"

# `R.string.foo`, but not `androidx.cardview.R.color.foo` — a qualified
# reference resolves against that library's own R, not Gadgetbridge's.
R_REF = re.compile(r"(?<![.\w])R\.(string|drawable|color|style|attr|plurals)\.(\w+)")

BANNER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED — do not edit by hand.
  Written by vendor/generate-gb-resources.py; re-run vendor/pull-gadgetbridge.sh.

{note}
-->
"""


def scan_references(roots: list[Path]) -> dict[str, set[str]]:
    """Every R.<kind>.<name> the vendored and shim sources mention."""
    found: dict[str, set[str]] = {}
    for root in roots:
        if not root.exists():
            continue
        for path in list(root.rglob("*.java")) + list(root.rglob("*.kt")):
            for kind, name in R_REF.findall(path.read_text(encoding="utf-8")):
                found.setdefault(kind, set()).add(name)
    return found


def load_upstream_strings(gb_res: Path) -> dict[str, tuple[str, str]]:
    """Name -> (attributes, raw inner XML) for every string upstream defines.

    Two forms, and the second one matters more than its rarity suggests.
    strings.xml holds `<string name="...">` — the translatable labels. But
    values.xml holds `<item type="string" name="...">` for things like
    `pref_media_play_value` = "PLAY": untranslatable machine values that get
    compared against stored preferences and sent to the watch. Miss those and
    you get a plausible-looking label where a protocol constant belongs, which
    fails silently rather than loudly.
    """
    out: dict[str, tuple[str, str]] = {}
    # Kept as raw text rather than parsed: these carry Android escaping
    # (\', \n), xliff placeholders, and CDATA that a parse/serialise round trip
    # would quietly rewrite.
    for src in sorted((gb_res / "values").glob("*.xml")):
        text = src.read_text(encoding="utf-8")
        for match in re.finditer(
            r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>', text, re.DOTALL
        ):
            name, attrs, body = match.groups()
            out.setdefault(name, (attrs, body))
        for match in re.finditer(
            r'<item\s+([^>]*?)type="string"([^>]*)>(.*?)</item>', text, re.DOTALL
        ):
            before, after, body = match.groups()
            name_match = re.search(r'name="([^"]+)"', before + after)
            if name_match:
                out.setdefault(name_match.group(1), ("", body))
    return out


def write_strings(names: set[str], upstream: dict[str, tuple[str, str]]) -> int:
    missing = sorted(n for n in names if n not in upstream)
    if missing:
        # Deliberately fatal rather than substituting a derived label. Most of
        # these names are display text where a wrong guess is cosmetic — but
        # some are protocol constants (pref_media_play_value = "PLAY"), where a
        # plausible-looking substitute breaks preference comparisons and watch
        # commands with no visible symptom. Not a distinction worth guessing at.
        print(
            f"error: {len(missing)} referenced string(s) are not defined anywhere in\n"
            f"       upstream's res/values/. They were probably renamed. Check\n"
            f"       what they are before adding them by hand — some are\n"
            f"       protocol constants, not labels:",
            file=sys.stderr,
        )
        for name in missing:
            print(f"         {name}", file=sys.stderr)
        raise SystemExit(1)

    lines = []
    for name in sorted(names):
        attrs, body = upstream[name]
        lines.append(f'    <string name="{name}"{attrs}>{body}</string>')

    note = (
        "  Copied from Gadgetbridge's app/src/main/res/values/strings.xml.\n"
        "  Labels for activity types and FIT field values. Tracks renders its\n"
        "  own labels from spec/sport_taxonomy.yaml; these exist so the\n"
        "  vendored protocol code resolves."
    )
    RES.joinpath("gadgetbridge_strings.xml").write_text(
        BANNER.format(note=note)
        + "<resources>\n"
        + "\n".join(lines)
        + "\n</resources>\n",
        encoding="utf-8",
    )
    return len(lines)


def write_drawables(names: set[str]) -> int:
    note = (
        "  Every entry aliases one placeholder. Gadgetbridge's icons are under\n"
        "  a separate artwork licence that we deliberately do not vendor (see\n"
        "  NOTICE), and Tracks draws its own sport icons anyway — these exist\n"
        "  only so the vendored code resolves."
    )
    lines = [
        f'    <drawable name="{name}">@drawable/ic_gb_placeholder</drawable>'
        for name in sorted(names)
        if name != "ic_gb_placeholder"
    ]
    RES.joinpath("gadgetbridge_drawables.xml").write_text(
        BANNER.format(note=note)
        + "<resources>\n"
        + "\n".join(lines)
        + "\n</resources>\n",
        encoding="utf-8",
    )
    return len(lines)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: generate-gb-resources.py <gadgetbridge-source-dir>", file=sys.stderr)
        return 2

    gb_res = Path(sys.argv[1]) / "app" / "src" / "main" / "res"
    if not (gb_res / "values" / "strings.xml").is_file():
        print(f"error: no strings.xml under {gb_res}", file=sys.stderr)
        return 1

    RES.mkdir(parents=True, exist_ok=True)
    refs = scan_references([MODULE / "src" / "main" / "java", MODULE / "src" / "compat" / "java"])

    upstream = load_upstream_strings(gb_res)
    n_strings = write_strings(refs.get("string", set()), upstream)
    n_drawables = write_drawables(refs.get("drawable", set()))

    # Anything else means a vendored file started reaching for Gadgetbridge's
    # theme or its layouts. That is a judgement call about what to shim, not
    # something to paper over with a generated stub — so stop and say so.
    unexpected = {k: v for k, v in refs.items() if k not in ("string", "drawable")}
    if unexpected:
        print(
            "error: vendored code now references resource kinds this generator\n"
            "       does not handle. Decide whether to shim the caller or extend\n"
            "       this script — do not stub them blindly:",
            file=sys.stderr,
        )
        for kind, names in sorted(unexpected.items()):
            print(f"         R.{kind}: {', '.join(sorted(names))}", file=sys.stderr)
        return 1

    print(f"  resources: {n_strings} strings, {n_drawables} drawable aliases")
    return 0


if __name__ == "__main__":
    sys.exit(main())
