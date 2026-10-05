#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate the Kotlin FIT profile and exercise-name tables from the server's.

## Why fitdecode's profile, and not the one in mobile/fit-codegen

The phone has to read a FIT file *exactly as the server does*, and the server
reads through fitdecode. Which messages have names, which fields exist, which
integers render as enum names, and which subfields and components expand are
all properties of fitdecode's bundled profile — not of the FIT SDK in general.
The parsers depend on that directly: they look for `unknown_229`,
`unknown_140` and `unknown_411` by name precisely because fitdecode's profile
does not know those messages, and they read `steps` as a subfield of `cycles`
because that is how fitdecode resolves it.

Gadgetbridge's `fit_profile.json` (vendored under mobile/fit-codegen) is a
newer, larger profile that names several of those. Decoding with it would give
the phone different message names from the server for the same bytes, and the
parsers would silently take different branches. So the Kotlin decoder carries
fitdecode's profile, whole, generated from the installed package.

Whole rather than the dozen messages the parsers read today: a message the
parsers ignore can still change what they see — a component that accumulates,
a field that crashes fitdecode — and a subset would be a second place to
remember to update.

## Also generated: exercise names

`app/parsers/exercise_names/_data.py` maps a strength set's
`(category, subtype)` to a readable name. Same reasoning — the phone must name
a set exactly as the server does — so it is carried across verbatim.

Usage (needs fitdecode and `app` importable — i.e. the backend image):

    docker run --rm --entrypoint python \\
        -v "$PWD:/repo" -w /repo/backend tracks-backend \\
        /repo/spec/make_fit_profile.py
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
sys.path.insert(0, str(ROOT / "backend"))

import fitdecode  # noqa: E402
from fitdecode import profile, types  # noqa: E402

from app.parsers.exercise_names._data import _CATEGORY_ID, _NAMES  # noqa: E402

KOTLIN_DIR = ROOT / "mobile/core/src/commonMain/kotlin/com/tracks/core"
PROFILE_OUT = KOTLIN_DIR / "fit/decode/FitProfileData.kt"
NAMES_OUT = KOTLIN_DIR / "parse/ExerciseNamesData.kt"

# A JVM class-file string constant holds at most 65535 bytes of modified
# UTF-8. Chunks are kept well under that so a non-ASCII name cannot tip one over.
_CHUNK_BYTES = 40_000

_TOKEN = re.compile(r"^[A-Za-z0-9_.\-/%]*$")


def _num(value) -> str:
    """A scale or offset, as the decoder must read it back.

    Empty for None. `repr` for floats so the value round-trips exactly; the
    decoder parses these with `toDouble()`, which is correctly rounded.
    """
    if value is None:
        return ""
    if isinstance(value, bool):
        raise TypeError("boolean scale/offset")
    return repr(value)


def _tok(value) -> str:
    text = "" if value is None else str(value)
    if not _TOKEN.match(text):
        raise ValueError(f"profile token {text!r} would break the table format")
    return text


def _type_ref(t) -> str:
    """`t:<field type>` or `b:<base type>`, so the two namespaces cannot collide."""
    if isinstance(t, types.BaseType):
        return "b:" + _tok(t.name)
    return "t:" + _tok(t.name)


def profile_lines() -> list[str]:
    lines = []
    for name in sorted(profile.FIELD_TYPES):
        t = profile.FIELD_TYPES[name]
        entries = ",".join(
            f"{int(k)}={_tok(v)}" for k, v in sorted((t.enum or {}).items()))
        lines.append(f"T|{_tok(name)}|{_tok(t.base_type.name)}|{entries}")

    def components(owner) -> None:
        for c in owner.components or []:
            lines.append("|".join([
                "C", _tok(c.name), str(int(c.def_num)), _num(c.scale), _num(c.offset),
                "1" if c.accumulate else "0", str(int(c.bits)), str(int(c.bit_offset)),
            ]))

    for num in sorted(profile.MESSAGE_TYPES):
        m = profile.MESSAGE_TYPES[num]
        lines.append(f"M|{int(num)}|{_tok(m.name)}")
        # Field order within a message does not matter to the decoder, which
        # looks fields up by number; sorted only to keep diffs of this file
        # readable when fitdecode is upgraded.
        for def_num in sorted(m.fields):
            f = m.fields[def_num]
            lines.append("|".join([
                "F", str(int(def_num)), _tok(f.name), _type_ref(f.type),
                _num(f.scale), _num(f.offset),
            ]))
            components(f)
            # Subfield order DOES matter: fitdecode takes the first whose
            # reference matches, so they are written in profile order.
            for s in f.subfields or []:
                refs = ";".join(
                    f"{_tok(r.name)}:{int(r.def_num)}:{int(r.raw_value)}" for r in s.ref_fields)
                lines.append("|".join([
                    "S", _tok(s.name), _type_ref(s.type), _num(s.scale), _num(s.offset), refs,
                ]))
                components(s)
    return lines


def chunks(lines: list[str]) -> list[str]:
    out, current, size = [], [], 0
    for line in lines:
        n = len(line.encode("utf-8")) + 1
        if current and size + n > _CHUNK_BYTES:
            out.append("\n".join(current))
            current, size = [], 0
        current.append(line)
        size += n
    if current:
        out.append("\n".join(current))
    return out


def kotlin_string(text: str) -> str:
    """A Kotlin string literal. Raw strings would need `$` escaping anyway."""
    escaped = (text.replace("\\", "\\\\").replace('"', '\\"')
               .replace("$", "\\$").replace("\n", "\\n"))
    return f'"{escaped}"'


HEADER = """// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED by spec/make_fit_profile.py — do not edit by hand.
"""


def write_profile() -> None:
    parts = chunks(profile_lines())
    body = ",\n".join("        " + kotlin_string(p) for p in parts)
    PROFILE_OUT.parent.mkdir(parents=True, exist_ok=True)
    PROFILE_OUT.write_text(f"""{HEADER}// Source: fitdecode {fitdecode.__version__}'s bundled profile — the one the
// server decodes with. See that script for why this and not a newer profile.
package com.tracks.core.fit.decode

/**
 * fitdecode's profile, as a compact line table parsed once by [FitProfile].
 *
 * Lines: `T|type|base|key=name,…`, `M|num|name`, `F|num|name|type|scale|offset`,
 * `S|name|type|scale|offset|ref:num:raw;…` (a subfield of the preceding field),
 * `C|name|num|scale|offset|accumulate|bits|bitOffset` (a component of the
 * preceding field or subfield). A type is `t:<field type>` or `b:<base type>`.
 *
 * Text rather than Kotlin constructors because the equivalent code would blow
 * through the JVM's 64 KB-per-method limit; chunked because a single class-file
 * string constant is capped at 64 KB too.
 */
internal object FitProfileData {{
    const val FITDECODE_VERSION: String = "{fitdecode.__version__}"

    val chunks: List<String> = listOf(
{body},
    )
}}
""")


def write_names() -> None:
    lines = [f"{cat}\t{int(sub)}\t{name}" for (cat, sub), name in sorted(_NAMES.items())]
    ids = [f"{int(k)}\t{v}" for k, v in sorted(_CATEGORY_ID.items())]
    for line in lines + ids:
        if "\n" in line or line.count("\t") > 2:
            raise ValueError(f"exercise table entry {line!r} would break the format")
    name_parts = ",\n".join("        " + kotlin_string(p) for p in chunks(lines))
    id_parts = ",\n".join("        " + kotlin_string(p) for p in chunks(ids))
    NAMES_OUT.parent.mkdir(parents=True, exist_ok=True)
    NAMES_OUT.write_text(f"""{HEADER}// Source: backend/app/parsers/exercise_names/_data.py
package com.tracks.core.parse

/**
 * The server's exercise-name tables, as tab-separated lines parsed once by
 * [ExerciseNames]: `category\\tsubtype\\tname` and `id\\tcategory`.
 */
internal object ExerciseNamesData {{
    val names: List<String> = listOf(
{name_parts},
    )

    val categoryIds: List<String> = listOf(
{id_parts},
    )
}}
""")


def _own_like_repo(path: Path) -> None:
    """Run as root in a container, the files would otherwise be root's on the host."""
    st = ROOT.stat()
    try:
        os.chown(path, st.st_uid, st.st_gid)
    except PermissionError:
        pass


def main() -> int:
    write_profile()
    write_names()
    for path in (PROFILE_OUT, NAMES_OUT):
        _own_like_repo(path.parent)
        _own_like_repo(path)
        print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
