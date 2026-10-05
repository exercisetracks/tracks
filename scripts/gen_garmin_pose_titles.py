# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate the mobile core's copy of Garmin's exercise/pose name manifest.

The phone needs this to build a mobility workout with no server: the Fenix
Yoga app resolves a pose animation by matching the workout step's
`wkt_step_name` against its own dictionary, so a stretch has to be sent under
Garmin's canonical name rather than the user's library name. See
`app/calculators/fit_workout.py::_garmin_official_name`, which this mirrors,
and `mobile/core/.../fit/GarminExercises.kt`, which consumes it.

Source of truth stays `backend/app/data/garmin_animations.json`. Only the
(category, name) -> display mapping is carried over; the per-app membership
lists and the remap heuristics stay on the server, which is the only side that
does exercise matching.

Regenerate: python3 scripts/gen_garmin_pose_titles.py
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "backend/app/data/garmin_animations.json"
TARGET = (ROOT / "mobile/core/src/commonMain/kotlin/com/tracks/core/fit"
                 "/GarminPoseTitlesData.kt")

# Kotlin string constants land in the class file's constant pool, which caps a
# single entry at 64 KiB of modified UTF-8. The whole table is comfortably past
# that, so it is split — the chunk size is well under the cap and the split
# point is arbitrary, since the pieces are concatenated before parsing.
CHUNK_BYTES = 8000

BANNER = """// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: backend/app/data/garmin_animations.json
// Regenerate: python3 scripts/gen_garmin_pose_titles.py
//
// Garmin's own name for every exercise and pose it ships an animation for,
// keyed by the (exercise_category, exercise_name) pair a FIT workout step
// carries. See GarminExercises.kt for why a mobility step has to be sent under
// Garmin's name rather than the user's.

package com.tracks.core.fit
"""


def main() -> None:
    entries = json.loads(SOURCE.read_text())
    # Sorted so an unrelated reordering upstream does not churn the diff.
    rows = sorted({(e["cat_int"], e["name_int"], e["display"]) for e in entries})
    packed = "\n".join(f"{cat}:{name}:{display}" for cat, name, display in rows)

    chunks: list[str] = []
    start = 0
    while start < len(packed):
        end = min(start + CHUNK_BYTES, len(packed))
        # Never split mid-row: back up to the last newline so each chunk holds
        # whole entries and the join is a plain concatenation.
        if end < len(packed):
            cut = packed.rfind("\n", start, end)
            if cut > start:
                end = cut + 1
        chunks.append(packed[start:end])
        start = end

    out = [BANNER]
    out.append("")
    out.append("/** Every catalogued pair, as `category:name:DISPLAY` rows. */")
    out.append("private val POSE_TITLE_CHUNKS: List<String> = listOf(")
    for chunk in chunks:
        out.append('    """' + chunk + '""",')
    out.append(")")
    out.append("")
    out.append("internal val GARMIN_POSE_TITLES: Map<Long, String> by lazy {")
    out.append("    val table = HashMap<Long, String>(%d)" % (len(rows) * 2))
    out.append("    for (chunk in POSE_TITLE_CHUNKS) {")
    out.append("        for (row in chunk.lineSequence()) {")
    out.append("            if (row.isEmpty()) continue")
    out.append("            val first = row.indexOf(':')")
    out.append("            val second = row.indexOf(':', first + 1)")
    out.append("            val category = row.substring(0, first).toLong()")
    out.append("            val name = row.substring(first + 1, second).toLong()")
    out.append("            table[(category shl 32) or name] = row.substring(second + 1)")
    out.append("        }")
    out.append("    }")
    out.append("    table")
    out.append("}")
    out.append("")

    TARGET.write_text("\n".join(out))
    print(f"{TARGET.relative_to(ROOT)}: {len(rows)} entries in {len(chunks)} chunks")


if __name__ == "__main__":
    main()
