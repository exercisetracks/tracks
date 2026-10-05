# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Record what the server makes of GPX courses, for the phone's GpxCourse.

Synthetic documents only — each built here and stored in the fixture beside
the server's output, so there is no course file to keep and nothing personal.
They cover the cases the scanner must agree with ElementTree on: both GPX
namespaces and none, a foreign namespace, missing and unreadable coordinates,
points with no elevation, self-closing points, and a track long enough to be
sampled down to 300 points. Run inside the backend image:

    docker run --rm --env-file .env -v $PWD/backend:/app -v $PWD/spec:/spec \\
        tracks-backend python /spec/make_gpx_fixtures.py
"""

import json
import math
import sys
from pathlib import Path

sys.path.insert(0, "/app")

from app.calculators.race_predictor.course import extract_path_points, parse_gpx  # noqa: E402

OUT = Path("/spec/fixtures/gpx.json")

NS11 = 'xmlns="http://www.topografix.com/GPX/1/1"'
NS10 = 'xmlns="http://www.topografix.com/GPX/1/0"'


def doc(ns: str, points: list[str]) -> str:
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="tracks-fixture" {ns}>'
            f'<trk><name>x</name><trkseg>{"".join(points)}</trkseg></trk></gpx>')


def pt(lat, lon, ele=None, close=False) -> str:
    attrs = f'lat="{lat}" lon="{lon}"'
    if close:
        return f"<trkpt {attrs}/>"
    body = f"<ele>{ele}</ele>" if ele is not None else ""
    return f"<trkpt {attrs}>{body}<time>2026-06-01T08:00:00Z</time></trkpt>"


def climb(n: int, lat0=39.75, lon0=-150.22, step=0.0004, rise=1.3) -> list[str]:
    # A winding climb: ~40 m steps, elevation rising then falling, so segments
    # carry both gain and loss and gradients land either side of zero.
    out = []
    for i in range(n):
        lat = round(lat0 + i * step * math.cos(i / 17), 7)
        lon = round(lon0 + i * step * math.sin(i / 23 + 1), 7)
        ele = round(1800 + rise * i - 0.004 * i * i, 2)
        out.append(pt(lat, lon, ele))
    return out


CASES = {
    "gpx_1_1_climb": doc(NS11, climb(80)),
    "gpx_1_0_climb": doc(NS10, climb(60, rise=0.9)),
    "no_namespace": doc("", climb(40)),
    "foreign_namespace_is_empty": doc('xmlns="http://example.com/not-gpx"', climb(20)),
    "no_elevation_anywhere": doc(NS11, [pt(39.7 + i * 0.0005, -150.2, None) for i in range(30)]),
    "some_points_without_elevation": doc(NS11, [pt(39.7 + i * 0.0006, -150.2, None if i % 3 == 0 else 1600 + i) for i in range(30)]),
    "unreadable_latitude_is_skipped": doc(NS11, climb(10) + [pt("north", -150.2, 1700)] + climb(10, lat0=39.76)),
    "missing_longitude_reads_as_zero": doc(NS11, [pt(0.0005 * i, -0.0004 * i, 5) for i in range(5)] + ['<trkpt lat="0.003"><ele>6</ele></trkpt>']),
    "self_closing_points": doc(NS11, [pt(39.7 + i * 0.0007, -150.2, close=True) for i in range(25)]),
    "a_short_tail_is_dropped": doc(NS11, [pt(39.7, -150.2, 10), pt(39.70005, -150.2, 11)]),
    "one_point_is_no_course": doc(NS11, [pt(39.7, -150.2, 10)]),
    "long_track_is_sampled": doc(NS11, climb(1234, step=0.00005, rise=0.05)),
}


def main() -> int:
    out = []
    for name, text in CASES.items():
        out.append({
            "name": name,
            "gpx": text,
            "segments": parse_gpx(text),
            "path": extract_path_points(text, max_points=300),
        })
    OUT.write_text(json.dumps({"cases": out}, indent=1))
    print(f"wrote {OUT} ({len(out)} documents)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
