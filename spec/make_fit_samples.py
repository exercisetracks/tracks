#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Synthetic FIT files for the parser parity corpus.

## Why synthetic

The parsers' real inputs are health and GPS records, which never go in the
repo. So the committed corpus is written here instead: small, deterministic
files that exercise every path the server's parsers take *and* every decoder
rule those paths depend on — compressed timestamps, big-endian definitions,
developer fields, accumulating components, subfields, 16-bit monitoring
timestamps, chained files, invalid-value sentinels, arrays, truncation.

Real files still get checked — `make_fit_parse_fixtures.py` records digests
of them into a gitignored fixture — but they cannot be the committed oracle.

## Why hand-rolled

The writer is a few dozen lines, and it has to produce things a well-behaved
encoder refuses to: a field whose size is not a whole number of elements, a
developer field nobody described, a record that overruns its file. Message and
field numbers, base types and enum values are all looked up by name in
fitdecode's own profile, so nothing here is a transcribed number.

Deterministic: fixed seeds, fixed epochs. Rerunning rewrites byte-identical
files, so an unchanged corpus shows no diff.
"""

from __future__ import annotations

import math
import random
import struct
from pathlib import Path

from fitdecode import profile, types

SPEC_DIR = Path(__file__).resolve().parent
SAMPLES_DIR = SPEC_DIR / "fixtures" / "fit"

FIT_EPOCH = 631065600

_BY_NAME = {m.name: m for m in profile.MESSAGE_TYPES.values()}
_FMT = {
    "enum": "B", "sint8": "b", "uint8": "B", "sint16": "h", "uint16": "H", "sint32": "i",
    "uint32": "I", "string": "s", "float32": "f", "float64": "d", "uint8z": "B",
    "uint16z": "H", "uint32z": "I", "byte": "B", "sint64": "q", "uint64": "Q", "uint64z": "Q",
}
_INVALID = {
    "enum": 0xFF, "sint8": 0x7F, "uint8": 0xFF, "sint16": 0x7FFF, "uint16": 0xFFFF,
    "sint32": 0x7FFFFFFF, "uint32": 0xFFFFFFFF, "float32": float("nan"), "float64": float("nan"),
    "uint8z": 0, "uint16z": 0, "uint32z": 0, "byte": 0xFF, "sint64": 0x7FFFFFFFFFFFFFFF,
    "uint64": 0xFFFFFFFFFFFFFFFF, "uint64z": 0,
}
_BASE_BY_NAME = {b.name: b for b in types.BASE_TYPES.values()}


def enum(type_name: str, value_name: str) -> int:
    for k, v in profile.FIELD_TYPES[type_name].enum.items():
        if v == value_name:
            return k
    raise KeyError(f"{type_name}.{value_name}")


def mesg_num(name: str) -> int:
    return _BY_NAME[name].mesg_num


def has_field(mesg: str, field: str) -> bool:
    return any(f.name == field for f in _BY_NAME[mesg].fields.values())


class Str:
    """A string field of a fixed byte size (FIT strings are fixed-width)."""

    def __init__(self, text: str | bytes, size: int):
        self.data = text.encode("utf-8") if isinstance(text, str) else text
        self.size = size


class Raw:
    """A field written with an explicit base type and size, bypassing the profile."""

    def __init__(self, base: str, value, size: int | None = None):
        self.base = base
        self.value = value
        self.size = size


def fit_crc(data: bytes, crc: int = 0) -> int:
    table = [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
             0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400]
    for byte in data:
        tmp = table[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ table[byte & 0xF]
        tmp = table[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ table[(byte >> 4) & 0xF]
    return crc


class FitWriter:
    def __init__(self):
        self.body = bytearray()
        self._defs: dict[int, tuple] = {}

    # ── Encoding ─────────────────────────────────────────────────────────────

    @staticmethod
    def _encode(base: str, value, endian: str, size: int | None) -> tuple[bytes, int]:
        fmt = _FMT[base]
        width = struct.calcsize(fmt)
        if base == "string":
            data = value.data if isinstance(value, Str) else (value or "").encode("utf-8")
            n = value.size if isinstance(value, Str) else (size or len(data) + 1)
            return data[:n].ljust(n, b"\0"), n
        if isinstance(value, bytes):
            return value, len(value)
        values = value if isinstance(value, (list, tuple)) else [value]
        out = bytearray()
        for v in values:
            if v is None:
                v = _INVALID[base]
            elif fmt not in "fd" and isinstance(v, float):
                v = int(round(v))
            out += struct.pack(endian + fmt, v)
        n = len(out) if size is None else size
        if size is not None and size != len(out):
            # Deliberately odd sizes are written as raw bytes, padded or cut.
            out = bytes(out[:size]).ljust(size, b"\xff")
        return bytes(out), n

    def _spec(self, mesg: str | int, key):
        """(def_num, base_type_name) for a profile field name or an explicit Raw."""
        if isinstance(key, int):
            return key, None
        m = _BY_NAME[mesg] if isinstance(mesg, str) else profile.MESSAGE_TYPES.get(mesg)
        for f in m.fields.values():
            if f.name == key:
                base = f.type if isinstance(f.type, types.BaseType) else f.type.base_type
                return f.def_num, base.name
        raise KeyError(f"{mesg}.{key}")

    # ── Messages ─────────────────────────────────────────────────────────────

    def message(self, local: int, mesg: str | int, fields: dict, *, big: bool = False,
                dev: dict | None = None, time_offset: int | None = None) -> None:
        """Write one data message, (re)defining the local type when its shape changes.

        `fields` maps a profile field name — or a field number, with a `Raw`
        value — to its value. `dev` maps (dev_data_index, field_num) to a `Raw`.
        """
        endian = ">" if big else "<"
        global_num = mesg_num(mesg) if isinstance(mesg, str) else mesg
        encoded = []
        for key, value in fields.items():
            num, base = self._spec(mesg, key)
            size = None
            if isinstance(value, Raw):
                base, size, value = value.base, value.size, value.value
            data, n = self._encode(base, value, endian, size)
            encoded.append((num, n, _BASE_BY_NAME[base].identifier, data))
        dev_encoded = []
        for (index, num), raw in (dev or {}).items():
            data, n = self._encode(raw.base, raw.value, endian, raw.size)
            dev_encoded.append((num, n, index, data))

        shape = (global_num, big, tuple(e[:3] for e in encoded), tuple(d[:3] for d in dev_encoded))
        if self._defs.get(local) != shape:
            header = 0x40 | local | (0x20 if dev_encoded else 0)
            out = bytearray([header, 0, 1 if big else 0])
            out += struct.pack(endian + "H", global_num)
            out.append(len(encoded))
            for num, n, base_id, _ in encoded:
                out += bytes([num, n, base_id])
            if dev_encoded:
                out.append(len(dev_encoded))
                for num, n, index, _ in dev_encoded:
                    out += bytes([num, n, index])
            self.body += out
            self._defs[local] = shape

        if time_offset is None:
            self.body.append(local)
        else:
            assert local < 4 and 0 <= time_offset < 32
            self.body.append(0x80 | (local << 5) | time_offset)
        for *_, data in encoded:
            self.body += data
        for *_, data in dev_encoded:
            self.body += data

    def to_bytes(self) -> bytes:
        header = bytearray(struct.pack("<BBHI4s", 14, 0x20, 2132, len(self.body), b".FIT"))
        header += struct.pack("<H", fit_crc(bytes(header)))
        whole = bytes(header) + bytes(self.body)
        return whole + struct.pack("<H", fit_crc(whole))


def fit_ts(unix: int) -> int:
    return unix - FIT_EPOCH


def semicircles(deg: float) -> int:
    return int(round(deg * (2 ** 31) / 180.0))


# ── Samples ──────────────────────────────────────────────────────────────────

# 2031-04-12 07:30:00 UTC — deliberately in the future so no sample can be
# mistaken for, or collide with, a real recording.
START = 1933745400


def file_id(w: FitWriter, file_type, *, serial=3_900_000_123, product="fenix5x", created=START):
    fields = {
        "type": file_type if isinstance(file_type, int) else enum("file", file_type),
        "manufacturer": enum("manufacturer", "garmin"),
        "product": enum("garmin_product", product) if isinstance(product, str) else product,
        "serial_number": serial,
        "time_created": fit_ts(created),
    }
    w.message(0, "file_id", fields)


def developer_power(w: FitWriter) -> None:
    """A developer field named `power`, as a running-power pod declares one."""
    w.message(3, "developer_data_id", {
        "developer_data_index": 0,
        "application_id": list(range(16)),
    })
    w.message(3, "field_description", {
        "developer_data_index": 0,
        "field_definition_number": 0,
        "fit_base_type_id": _BASE_BY_NAME["uint16"].identifier,
        "field_name": Str("power", 16),
        "units": Str("watts", 8),
    })


def track(rng: random.Random, n: int, speed: float, lat0: float, lon0: float):
    """A wandering track, with sensor glitches for the smoother to find."""
    lat, lon, alt, bearing = lat0, lon0, 1200.0, 0.3
    for i in range(n):
        bearing += rng.uniform(-0.05, 0.05)
        v = max(0.0, speed + rng.uniform(-0.4, 0.4) + 0.3 * math.sin(i / 40))
        lat += v * math.cos(bearing) / 111_320
        lon += v * math.sin(bearing) / (111_320 * math.cos(math.radians(lat)))
        alt += rng.uniform(-0.6, 0.8)
        hr = 120 + int(30 * (i / n)) + rng.randint(-3, 3)
        if i % 97 == 50:
            hr += 60          # a spike: an optical sensor losing lock
        if i % 131 == 70:
            v += 9.0          # a GPS jump
        yield i, lat, lon, alt, v, hr


def sample_run() -> bytes:
    rng = random.Random(11)
    w = FitWriter()
    file_id(w, "activity")
    developer_power(w)
    w.message(2, "event", {"timestamp": fit_ts(START), "event": enum("event", "timer"),
                           "event_type": enum("event_type", "start")})
    distance = 0.0
    last_full = None
    n = 620
    for i, lat, lon, alt, v, hr in track(rng, n, 3.1, 45.071, 7.421):
        t = START + i
        distance += v
        fields = {
            "position_lat": semicircles(lat), "position_long": semicircles(lon),
            "altitude": int(round((alt + 500) * 5)), "heart_rate": hr,
            "cadence": 84 + rng.randint(-2, 2), "distance": int(distance * 100),
            "speed": int(v * 1000),
        }
        if i % 150 == 149:
            fields["heart_rate"] = None     # the sensor's own "no value"
        if 440 <= i < 520:
            # A flat stretch with deviations just over and just under each
            # spike threshold. The glitches `track` makes are far past any
            # threshold, so without these a smoother whose limits were slightly
            # off would still pass the corpus.
            #
            # Alternating, four spikes in seven samples, because the rolling
            # median runs afterwards: a lone spike in a flat stretch is erased
            # by the median whether or not spike removal caught it, so it
            # cannot tell two thresholds apart. Four in a window of seven
            # survive the median unless spike removal took them out.
            fields.update({"speed": 3000, "heart_rate": 140, "altitude": 8500, "cadence": 84})
            blocks = [("speed", 3050), ("speed", 2950), ("heart_rate", 16), ("heart_rate", 15),
                      ("altitude", 101), ("altitude", 99), ("cadence", 16), ("cadence", 15)]
            field, delta = blocks[(i - 440) // 10]
            if (i - 440) % 10 in (1, 3, 5, 7):
                fields[field] += delta
        dev = {(0, 0): Raw("uint16", 240 + rng.randint(-15, 15))} if 200 <= i < 400 else None
        # Every tenth record uses a compressed timestamp header, the rest a
        # full timestamp field — a real watch mixes the two.
        if i % 10 == 5 and last_full is not None and 0 < t - last_full < 32:
            w.message(1, "record", fields, dev=dev, time_offset=(t - FIT_EPOCH) & 0x1F)
        else:
            w.message(0 if dev is None else 2, "record", {"timestamp": fit_ts(t), **fields}, dev=dev)
            last_full = t
        if i and i % 200 == 0:
            lap_fields = {
                "timestamp": fit_ts(t), "start_time": fit_ts(t - 200),
                "total_elapsed_time": 200_000, "total_distance": 62_000, "avg_heart_rate": 131,
                "max_heart_rate": 150, "enhanced_avg_speed": 3100, "avg_speed": 3050,
                "total_ascent": 12, "total_descent": 9, "total_calories": 61, "avg_cadence": 85,
            }
            w.message(4, "lap", lap_fields)
    # Physiological metrics (unknown message 140), met_max at 65536 — 13.6 METs.
    w.message(5, 140, {7: Raw("uint32", int(13.6 * 65536))})
    w.message(6, "session", {
        "timestamp": fit_ts(START + n), "start_time": fit_ts(START),
        "sport": enum("sport", "running"), "sub_sport": enum("sub_sport", "trail"),
        "sport_profile_name": Str("Trail Run", 16),
        "total_elapsed_time": (n + 0.4) * 1000, "total_distance": int(distance * 100),
        "avg_heart_rate": 137, "max_heart_rate": 188, "total_calories": 211,
        "training_stress_score": 431, "intensity_factor": 812,
        "total_training_effect": 31, "total_anaerobic_training_effect": 12,
        "avg_speed": 3080, "max_speed": 12_500, "enhanced_avg_speed": 3081, "enhanced_max_speed": 12_501,
        "avg_cadence": 84, "total_ascent": 88, "total_descent": 61,
        # The watch's post-workout prompts: "strong", and 6/10 effort.
        "workout_feel": 75, "workout_rpe": 60,
    })
    return w.to_bytes()


def sample_ride() -> bytes:
    """Cycling, with power, native enhanced fields, and big-endian records."""
    rng = random.Random(22)
    w = FitWriter()
    file_id(w, "activity", product="edge1000", serial=3_900_000_456)
    n = 700
    for i, lat, lon, alt, v, hr in track(rng, n, 8.4, 44.912, 6.988):
        power = 210 + int(40 * math.sin(i / 25)) + rng.randint(-20, 20)
        if i % 113 == 60:
            power += 900
        if 500 <= i < 520:
            # Flat power with alternating spikes just over the 300 W threshold,
            # then just under — see sample_run for why alternating.
            power = 200
            if (i - 500) % 10 in (1, 3, 5, 7):
                power += 301 if i < 510 else 299
        w.message(0, "record", {
            "timestamp": fit_ts(START + 3600 + i),
            "position_lat": semicircles(lat), "position_long": semicircles(lon),
            "enhanced_altitude": int(round((alt + 500) * 5)),
            "enhanced_speed": int(v * 1000), "heart_rate": hr, "power": power,
            "cadence": 90 + rng.randint(-3, 3),
        }, big=(i % 2 == 1))
    # max_met_data (unknown message 229): VO2max 54.3 at scale 10.
    w.message(1, 229, {2: Raw("uint16", 543)})
    w.message(2, "session", {
        "timestamp": fit_ts(START + 3600 + n), "start_time": fit_ts(START + 3600),
        "sport": enum("sport", "cycling"), "sub_sport": enum("sub_sport", "road"),
        "total_elapsed_time": n * 1000, "total_distance": 580_000,
        "avg_heart_rate": 140, "max_heart_rate": 190, "avg_power": 215,
        "normalized_power": 238, "enhanced_avg_speed": 8300, "enhanced_max_speed": 17_000,
        "total_training_effect": 34, "total_ascent": 140, "total_descent": 120,
    }, big=True)
    return w.to_bytes()


def sample_strength() -> bytes:
    w = FitWriter()
    file_id(w, "activity", created=START + 7200)
    t = START + 7200
    sets = [
        ("active", ["squat"], [enum("squat_exercise_name", "barbell_back_squat")
                               if "squat_exercise_name" in profile.FIELD_TYPES else 6], 80.0, 5),
        ("rest", ["squat"], [None], None, None),
        ("active", ["bench_press"], [1], 60.5, 8),
        ("active", ["deadlift", "core"], [0, 3], 100.0, 3),   # a two-element array
        ("active", [None], [None], None, 12),                  # invalid category
        ("active", [65534], [None], 20.0, 10),                 # "unknown" category
        ("active", [40], [2], 10.0, 15),                       # a category id fitdecode lacks
    ]
    for i, (set_type, cats, subs, weight, reps) in enumerate(sets):
        cat_values = [c if (c is None or isinstance(c, int)) else enum("exercise_category", c) for c in cats]
        w.message(1, "set", {
            "timestamp": fit_ts(t + 60), "start_time": fit_ts(t),
            "set_type": enum("set_type", set_type), "category": cat_values,
            "category_subtype": subs,
            "weight": None if weight is None else int(weight * 16),
            "repetitions": reps, "duration": 45_000,
        })
        t += 90
    w.message(2, "session", {
        "timestamp": fit_ts(t), "start_time": fit_ts(START + 7200),
        "sport": enum("sport", "training"), "sub_sport": enum("sub_sport", "strength_training"),
        "total_elapsed_time": (t - START - 7200) * 1000, "avg_heart_rate": 101,
        "max_heart_rate": 139, "total_calories": 180,
    })
    return w.to_bytes()


def sample_climb() -> bytes:
    w = FitWriter()
    file_id(w, "activity", created=START + 20_000)
    t = START + 20_000
    for i in range(5):
        active = i % 2 == 0
        w.message(1, "split", {
            "split_type": enum("split_type", "climb_active" if active else "climb_rest"),
            "total_elapsed_time": 240_000 if active else 180_000,
            "start_time": fit_ts(t), "end_time": fit_ts(t + 240),
            "total_ascent": 15 if active else None, "total_calories": 25,
            15: Raw("uint8", 105 + i), 16: Raw("uint8", 160 + i), 80: Raw("uint16", 300 + i),
            70: Raw("uint8", 4 + i), 71: Raw("uint8", 3 if i < 3 else 2),
        })
        t += 300
    w.message(1, "split", {"split_type": enum("split_type", "run_split") if "run_split" in
                           profile.FIELD_TYPES["split_type"].enum.values() else 0,
                           "total_elapsed_time": 1000})
    w.message(2, "session", {
        "timestamp": fit_ts(t), "start_time": fit_ts(START + 20_000),
        "sport": enum("sport", "rock_climbing"), "sub_sport": enum("sub_sport", "bouldering"),
        "total_elapsed_time": (t - START - 20_000) * 1000,
    })
    return w.to_bytes()


def sample_unknown_sport() -> bytes:
    """A sport number fitdecode's enum lacks: the profile name takes over."""
    w = FitWriter()
    file_id(w, "activity", created=START + 30_000)
    w.message(1, "session", {
        "timestamp": fit_ts(START + 30_600), "start_time": fit_ts(START + 30_000),
        "sport": 200, "sub_sport": 201, "sport_profile_name": Str("  Safety  ", 16),
        "total_elapsed_time": 600_000,
    })
    return w.to_bytes()


def sample_monitoring() -> bytes:
    """A monitoring file straddling local midnight, the way a fenix writes one."""
    rng = random.Random(33)
    w = FitWriter()
    # 23:10 local on the 12th, at UTC-6 — 05:10 UTC on the 13th.
    offset = -6 * 3600
    t0 = START + 21 * 3600 + 40 * 60
    file_id(w, "monitoring_b", created=t0)
    w.message(1, "monitoring_info", {
        "timestamp": fit_ts(t0), "local_timestamp": fit_ts(t0 + offset),
        "resting_metabolic_rate": 1740,
    })
    walking = enum("activity_type", "walking")
    running = enum("activity_type", "running")
    generic = enum("activity_type", "generic")
    steps = {walking: 8000, running: 2100}
    cal = {walking: 300, running: 180, generic: 90}
    t = t0
    local_midnight = ((t0 + offset) // 86400 + 1) * 86400 - offset
    for i in range(160):
        t += 60
        kind = [walking, running, generic][i % 3]
        if t == local_midnight + 60:
            # The watch's counters reset at local midnight.
            steps = {walking: 0, running: 0}
            cal = {walking: 0, running: 0, generic: 0}
        if kind in steps:
            steps[kind] += rng.randint(0, 40)
        cal[kind] += rng.randint(0, 3)
        fields = {
            "activity_type": kind,
            "cycles": (steps[kind] if kind in steps else rng.randint(10, 90)) * (1 if kind in steps else 2),
            "active_calories": cal[kind], "duration_min": ((t + offset) % 86400) // 60,
        }
        if i % 4 == 0:
            w.message(2, "monitoring", {"timestamp": fit_ts(t), **fields})
        else:
            w.message(3, "monitoring", {"timestamp_16": fit_ts(t) & 0xFFFF, **fields})
        if i % 5 == 0:
            w.message(4, "stress_level", {
                "stress_level_time": fit_ts(t), "stress_level_value": rng.choice([-1, 0, 25, 41, 77]),
                3: Raw("sint8", rng.choice([-1, 55, 54, 53, 60, 61])),
            })
        if i % 7 == 0:
            w.message(5, "respiration_rate", {"timestamp": fit_ts(t),
                                              "respiration_rate": rng.choice([1450, 1623, 9000, -200])})
        if i % 20 == 0:
            w.message(6, "monitoring_hr_data", {"timestamp": fit_ts(t),
                                                "current_day_resting_heart_rate": 50 + i // 40})
    # The record the watch writes at exactly local midnight, carrying the
    # finished day's totals.
    w.message(2, "monitoring", {"timestamp": fit_ts(local_midnight), "activity_type": walking,
                                "cycles": 9999, "active_calories": 777})
    # A packed activity-type byte and an accumulating 16-bit counter.
    for k in range(4):
        w.message(7, "monitoring", {
            "timestamp": fit_ts(t + 60 * (k + 1)),
            "current_activity_type_intensity": [(running & 0x1F) | (2 << 5)],
            "cycles_16": (65_530 + 7 * k) & 0xFFFF,
        })
    if has_field("spo2_data", "reading_spo2"):
        for k in range(3):
            w.message(8, "spo2_data", {"timestamp": fit_ts(t + 30 * k), "reading_spo2": [93, 97, 40][k]})
    if has_field("hsa_body_battery_data", "level"):
        for k in range(3):
            w.message(9, "hsa_body_battery_data", {
                "timestamp": fit_ts(t + 120 * k), "level": [48, 51, -1][k],
                "charged": [0, 3, 2][k], "uncharged": [2, 0, 5][k],
            })
    return w.to_bytes()


def sample_hrv() -> bytes:
    w = FitWriter()
    file_id(w, 68, created=START + 86_000)
    w.message(1, "hrv_status_summary", {
        "timestamp": fit_ts(START + 86_000), "weekly_average": 62 * 128,
        "last_night_average": 58 * 128,
    })
    w.message(1, "hrv_status_summary", {
        "timestamp": fit_ts(START + 86_100), "weekly_average": 61 * 128, "last_night_average": None,
    })
    return w.to_bytes()


def _sleep(summary: bool, nap: bool) -> bytes:
    w = FitWriter()
    night_start = START - 7 * 3600
    file_id(w, 49, created=night_start)
    levels = ["awake", "light", "deep", "light", "rem", "light", "deep", "awake", "rem", "awake"]
    t = night_start - 25 * 60
    for i, level in enumerate(levels * 2):
        w.message(1, "sleep_level", {"timestamp": fit_ts(t), "sleep_level": enum("sleep_level", level)})
        t += 20 * 60 + 60 * (i % 5)
    w.message(1, "sleep_level", {"timestamp": fit_ts(t), "sleep_level": None})
    if summary:
        def summary_message(start, end, deep, light, rem, awake, score):
            w.message(2, 411, {
                10: Raw("uint32", fit_ts(start)), 8: Raw("uint32", fit_ts(end)),
                1: Raw("uint16", deep), 2: Raw("uint16", light), 3: Raw("uint16", rem),
                4: Raw("uint16", awake), 0: Raw("uint8", score),
            })
        if nap:
            summary_message(night_start + 13 * 3600, night_start + 13 * 3600 + 1500, 0, 25, 0, 0xFFFF, 0)
        summary_message(night_start, night_start + 6 * 3600 + 700, 81, 212, 63, 17, 78)
    if has_field("sleep_assessment", "overall_sleep_score"):
        w.message(3, "sleep_assessment", {"overall_sleep_score": 81, "resting_heart_rate": 48,
                                          "average_stress_during_sleep": 1500}
                  if has_field("sleep_assessment", "resting_heart_rate") else {"overall_sleep_score": 81})
    return w.to_bytes()


def sample_edges() -> bytes:
    """Decoder corners: odd sizes, 64-bit, NaN, bools, time-of-day, undeclared dev fields."""
    w = FitWriter()
    file_id(w, "activity", created=START + 40_000)
    # A uint16 field written three bytes wide: fitdecode falls back to bytes.
    w.message(1, "record", {"timestamp": fit_ts(START + 40_000), "heart_rate": 100,
                            "power": Raw("uint16", 300, size=3)})
    # A string with no terminator, and one that is empty.
    w.message(2, "session", {"timestamp": fit_ts(START + 40_060), "start_time": fit_ts(START + 40_000),
                             "sport": enum("sport", "walking"), "sport_profile_name": Str(b"WalkNoNul", 9),
                             "total_elapsed_time": 60_000})
    w.message(2, "session", {"timestamp": fit_ts(START + 40_060), "start_time": fit_ts(START + 40_000),
                             "sport": enum("sport", "walking"), "sport_profile_name": Str(b"", 4),
                             "total_elapsed_time": None})
    # user_profile: a localtime_into_day at exactly 86400, a bool, a float.
    if has_field("user_profile", "sleep_time"):
        w.message(3, "user_profile", {"sleep_time": 86400, "wake_time": 6 * 3600 + 30 * 60 + 5})
    # An unknown message with every base type, including NaN and uint64.
    w.message(4, 60_000, {
        0: Raw("sint8", -5), 1: Raw("uint64", 2 ** 63 + 12345), 2: Raw("sint64", -(2 ** 40)),
        3: Raw("float32", float("nan")), 4: Raw("float64", 2.5), 5: Raw("uint8z", 0),
        6: Raw("uint16", [1, None, 3]), 7: Raw("byte", [0xFF, 0xFF]), 8: Raw("byte", [1, 2, 3]),
        9: Raw("string", Str("héllo", 8)), 10: Raw("sint32", -123456), 11: Raw("uint32z", 99),
    })
    # A developer field whose index was never declared: bytes, and no name.
    w.message(5, "record", {"timestamp": fit_ts(START + 40_001), "heart_rate": 101},
              dev={(7, 3): Raw("uint8", 44)})
    return w.to_bytes()


def sample_hr_messages() -> bytes:
    """hr messages whose 12-bit event timestamps accumulate from a full one."""
    w = FitWriter()
    file_id(w, "activity", created=START + 50_000)
    w.message(1, "record", {"timestamp": fit_ts(START + 50_000), "heart_rate": 90})
    w.message(2, "hr", {"timestamp": fit_ts(START + 50_001), "event_timestamp": 1024 * 5,
                        "filtered_bpm": [90]})
    w.message(3, "hr", {"event_timestamp_12": [0x10, 0x32, 0x54, 0x76, 0x98, 0xBA, 0xDC, 0xFE,
                                               0x10, 0x32, 0x54, 0x76],
                        "filtered_bpm": [91, 92, 93, 94, 95, 96, 97, 98]})
    w.message(4, "session", {"timestamp": fit_ts(START + 50_060), "start_time": fit_ts(START + 50_000),
                             "sport": enum("sport", "swimming"), "total_elapsed_time": 60_000})
    return w.to_bytes()


def sample_hr_before_start() -> bytes:
    """An hr message with 12-bit timestamps and no full one before it: fitdecode asserts."""
    w = FitWriter()
    file_id(w, "activity", created=START + 51_000)
    w.message(3, "hr", {"event_timestamp_12": [1] * 12, "filtered_bpm": [80] * 8})
    return w.to_bytes()


SAMPLES = {
    "activity_run.fit": sample_run,
    "activity_ride.fit": sample_ride,
    "activity_strength.fit": sample_strength,
    "activity_climb.fit": sample_climb,
    "activity_unknown_sport.fit": sample_unknown_sport,
    "monitoring.fit": sample_monitoring,
    "hrv.fit": sample_hrv,
    "sleep.fit": lambda: _sleep(summary=True, nap=True),
    "sleep_stream_only.fit": lambda: _sleep(summary=False, nap=False),
    "edges.fit": sample_edges,
    "hr_messages.fit": sample_hr_messages,
    "hr_before_start.fit": sample_hr_before_start,
}


def derived_samples(built: dict[str, bytes]) -> dict[str, bytes]:
    """Files made from other files: chained, truncated, and not FIT at all."""
    run = built["activity_run.fit"]
    return {
        "chained.fit": built["hrv.fit"] + built["activity_unknown_sport.fit"],
        # Cut inside a record, well after file_id: claimed, then fails.
        "truncated.fit": run[: len(run) // 2],
        # Whole body, no CRC footer.
        "missing_crc.fit": built["activity_strength.fit"][:-2],
        "not_fit.fit": b"This is not a FIT file at all, just some text.\n",
        "empty.fit": b"",
    }


def build_all() -> dict[str, bytes]:
    built = {name: make() for name, make in SAMPLES.items()}
    built.update(derived_samples(built))
    return built


def write_all() -> dict[str, bytes]:
    built = build_all()
    SAMPLES_DIR.mkdir(parents=True, exist_ok=True)
    for name, data in built.items():
        (SAMPLES_DIR / name).write_bytes(data)
    return built


if __name__ == "__main__":
    for name, data in write_all().items():
        print(f"{name}: {len(data)} bytes")
