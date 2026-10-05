#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Record what the server's FIT parsers make of each file, for the phone to match.

The Kotlin port in mobile/core (`com.tracks.core.fit.decode`,
`com.tracks.core.parse`) has one job: produce, from the same bytes, what these
parsers produce. This script runs the real ones — `backend/app/parsers/` and
fitdecode — and writes down the answers.

## Outputs

- `spec/fixtures/fit/*.fit` — the synthetic corpus (see make_fit_samples.py).
- `spec/fixtures/fit_parse.json` — for each sample: which parser claims it,
  every parser's output (or the fact that it raised), and a digest of every
  decoded message, field by field.
- `spec/fixtures/py_math.json` — Python's `round`, `statistics.mean`,
  `statistics.median` and 3.12's compensated `sum`, on inputs chosen to catch
  anyone who reimplements them naively. The parsers' numbers are only as
  right as these.
- `spec/fixtures/personal/fit_parse_personal.json` — **gitignored**. The same
  digests for real files, when a directory of them is mounted at /inputs.
  Digests only: a personal file's output is GPS and health data, and even the
  gitignored fixture should not be a second copy of it.

## Canonical form

Outputs are compared as canonical JSON: sorted keys, no whitespace, ASCII.
Floats are written as their IEEE-754 bits (`{"$f": "400921fb54442d18"}`), so
equality is bit-exact and no language's float formatting is involved;
instants, dates and times are tagged ISO strings. The parser-output form also
writes an integral float as an int — the importer casts everything into
typed columns, and the phone keeping `142` where Python happens to hold
`142.0` is not a disagreement. The message digest does not: there, a float
where fitdecode has an int *is* a decoder bug.

Usage (backend image; add the -v for /inputs to cover personal files):

    docker run --rm --entrypoint python \\
        -v "$PWD:/repo" -w /repo/backend \\
        -v ~/Downloads/fit-files:/inputs:ro -e INPUTS_HOST_ROOT=~/Downloads/fit-files \\
        tracks-backend /repo/spec/make_fit_parse_fixtures.py
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import math
import os
import random
import statistics
import struct
import sys
import tempfile
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(SPEC_DIR))

import fitdecode  # noqa: E402

from app.parsers.activity import ActivityParser  # noqa: E402
from app.parsers.daily_health import DailyHealthParser  # noqa: E402
from app.parsers.sleep import SleepParser  # noqa: E402

import make_fit_samples  # noqa: E402

FIXTURES = SPEC_DIR / "fixtures"
PERSONAL = FIXTURES / "personal"

# The importer's order — services/fit_import.py `_PARSERS`. The first parser
# whose can_parse says yes gets the file.
PARSERS = [("activity", ActivityParser()), ("sleep", SleepParser()), ("daily", DailyHealthParser())]


# ── Canonical form ───────────────────────────────────────────────────────────

def tag(value, *, normalize: bool):
    if value is None or isinstance(value, (bool, str)):
        return value
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        if normalize and value.is_integer() and abs(value) < 2 ** 53:
            return int(value)
        return {"$f": struct.pack(">d", value).hex()}
    if isinstance(value, dt.datetime):
        return {"$dt": value.isoformat()}
    if isinstance(value, dt.date):
        return {"$d": value.isoformat()}
    if isinstance(value, dt.time):
        return {"$t": value.isoformat()}
    if isinstance(value, (list, tuple)):
        return [tag(v, normalize=normalize) for v in value]
    if isinstance(value, dict):
        return {str(k): tag(v, normalize=normalize) for k, v in value.items()}
    if isinstance(value, bytes):
        return {"$b": value.hex()}
    raise TypeError(f"cannot canonicalise {type(value).__name__}: {value!r}")


def canonical(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def digest(obj) -> str:
    return hashlib.sha256(canonical(obj).encode("ascii")).hexdigest()


def message_dump(path: Path):
    """Every data message as fitdecode yields it: name, and each field's name, value and raw value."""
    out = []
    try:
        with fitdecode.FitReader(path) as fit:
            for frame in fit:
                if isinstance(frame, fitdecode.FitDataMessage):
                    out.append({
                        "n": frame.name,
                        "g": frame.global_mesg_num,
                        "f": [[f.name, tag(f.value, normalize=False), tag(f.raw_value, normalize=False)]
                              for f in frame.fields],
                    })
    except Exception:  # noqa: BLE001 — recording where fitdecode gives up is the point
        # That it failed, and after how many messages — not the exception's
        # class, which only Python has.
        out.append({"error": True})
    return out


def run_parsers(path: Path, *, all_parsers: bool):
    claims = {}
    for name, parser in PARSERS:
        claims[name] = parser.can_parse(path)
    selected = next((name for name, _ in PARSERS if claims[name]), None)
    results = {}
    for name, parser in PARSERS:
        if not all_parsers and name != selected:
            continue
        try:
            results[name] = {"ok": tag(parser.parse(path), normalize=True)}
        except Exception as exc:  # noqa: BLE001
            # The class name is for a human reading the fixture; the Kotlin
            # side only has to fail too, not fail with the same name.
            results[name] = {"error": type(exc).__name__}
    return claims, selected, results


# ── The committed corpus ─────────────────────────────────────────────────────

# Full message dumps are kept for files small enough to read in a diff; larger
# ones are held to their digest.
_FULL_DUMP_LIMIT = 40_000


def corpus_fixture() -> dict:
    built = make_fit_samples.write_all()
    cases = []
    for name in sorted(built):
        path = make_fit_samples.SAMPLES_DIR / name
        claims, selected, results = run_parsers(path, all_parsers=True)
        dump = message_dump(path)
        text = canonical(dump)
        case = {
            "file": f"spec/fixtures/fit/{name}",
            "sha256": hashlib.sha256(built[name]).hexdigest(),
            "claims": claims,
            "selected": selected,
            "results": results,
            "messages_sha256": digest(dump),
            "message_count": len(dump),
        }
        if len(text) <= _FULL_DUMP_LIMIT:
            case["messages"] = dump
        cases.append(case)
    return {
        "_comment": "Generated by spec/make_fit_parse_fixtures.py from the server's parsers. "
                    "Do not edit; rerun it.",
        "fitdecode": fitdecode.__version__,
        "python": sys.version.split()[0],
        "cases": cases,
    }


# ── Python arithmetic ────────────────────────────────────────────────────────

def py_math_fixture() -> dict:
    rng = random.Random(44)

    def f(x: float):
        return {"$f": struct.pack(">d", x).hex()}

    rounds = []
    specials = [2.675, 0.125, 0.375, 1.005, 2.5, 3.5, -2.5, 0.5, 1e-7, 123456.78901234,
                0.1 + 0.2, 1 / 3, 2 / 3, 15.049999999999999, 15.05, 99.995, -0.0049]
    values = specials + [rng.uniform(-1000, 1000) for _ in range(300)] + \
        [round(rng.uniform(0, 100), 4) + 0.00005 for _ in range(200)]
    for x in values:
        for n in (0, 1, 2, 3, 4):
            rounds.append({"x": f(x), "n": n, "expect": f(round(x, n))})

    def lists():
        yield [0.1] * 10
        yield [1e16, 1.0, -1e16]
        yield [3.2, 3.3, 3.2999999999999998]
        yield [142, 143, 141]
        yield [142, 143]
        yield [0.1, 0.2, 0.3, 0.4]
        for _ in range(40):
            yield [rng.uniform(0, 12) for _ in range(rng.randint(1, 120))]
        for _ in range(15):
            yield [float(rng.randint(60, 190)) for _ in range(rng.randint(1, 120))]

    aggregates = []
    for data in lists():
        floats = [float(x) for x in data]
        aggregates.append({
            "xs": [f(x) for x in floats],
            "sum": f(float(sum(floats))),
            "mean": f(float(statistics.mean(floats))),
            "median": f(float(statistics.median(floats))),
        })
    return {
        "_comment": "Generated by spec/make_fit_parse_fixtures.py. Python's own round/sum/mean/median.",
        "python": sys.version.split()[0],
        "round": rounds, "aggregates": aggregates,
    }


# ── Personal files ───────────────────────────────────────────────────────────

def personal_fixture(inputs: Path, host_root: str) -> dict:
    cases = []
    files = sorted(p for p in inputs.rglob("*") if p.is_file() and p.suffix.lower() == ".fit")
    for path in files:
        claims, selected, results = run_parsers(path, all_parsers=False)
        cases.append({
            "file": str(path.relative_to(inputs)),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "claims": claims,
            "selected": selected,
            "result": {k: (digest(v["ok"]) if "ok" in v else {"error": v["error"]})
                       for k, v in results.items()},
            "messages_sha256": digest(message_dump(path)),
        })
    return {
        "_comment": "PERSONAL — gitignored. Digests of the server's parse of real files.",
        "root": host_root,
        "fitdecode": fitdecode.__version__,
        "cases": cases,
    }


def _write(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Compact: these are machine-written and machine-read, and indenting the
    # track-point arrays would triple the size of a file that sits in git.
    path.write_text(canonical(obj) + "\n")
    st = ROOT.stat()
    for p in (path, path.parent):
        try:
            os.chown(p, st.st_uid, st.st_gid)
        except PermissionError:
            pass


def main() -> int:
    _write(FIXTURES / "fit_parse.json", corpus_fixture())
    _write(FIXTURES / "py_math.json", py_math_fixture())
    st = ROOT.stat()
    for p in [make_fit_samples.SAMPLES_DIR, *make_fit_samples.SAMPLES_DIR.iterdir()]:
        try:
            os.chown(p, st.st_uid, st.st_gid)
        except PermissionError:
            pass
    inputs = Path("/inputs")
    if inputs.is_dir():
        host_root = os.environ.get("INPUTS_HOST_ROOT", str(inputs))
        _write(PERSONAL / "fit_parse_personal.json", personal_fixture(inputs, host_root))
        print(f"personal: {PERSONAL / 'fit_parse_personal.json'}")
    print("wrote spec/fixtures/fit_parse.json, spec/fixtures/py_math.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
