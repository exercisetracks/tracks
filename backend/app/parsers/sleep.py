# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import fitdecode
from datetime import datetime, timezone
from pathlib import Path
from app.parsers.base import BaseParser
from app.parsers.utils import get, as_str, get_by_iter, parse_file_id

# Global message 411, `sleep_summary`: the watch's own finished verdict on the
# night. fitdecode's profile does not carry it, so it arrives as
# `unknown_411` with fields named `unknown_<n>` — the same route the Body
# Battery reading takes out of `stress_level`.
_SLEEP_SUMMARY = "unknown_411"

# FIT counts from 1989-12-31; Unix from 1970. The summary's start and end are
# plain uint32 rather than the `date_time` type, so nothing converts them for
# us.
_GARMIN_EPOCH = 631065600


def _summary_field(frame, name: str, number: int):
    """A summary field by name, falling back to its number.

    Named lookup first so this keeps working the day fitdecode learns the
    message; by number for today, where every field is `unknown_<n>`.
    """
    value = get(frame, name)
    if value is None:
        value = get_by_iter(frame, f"unknown_{number}")
    return value


def _as_int(value) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _read_summary(frame) -> dict | None:
    """One `sleep_summary` message, as hours and instants.

    Returns None unless the message carries a usable window: durations without
    a window cannot be reconciled with the stage timeline, and a window is what
    the rest of this is for.
    """
    start = _as_int(_summary_field(frame, "sleep_start_timestamp_utc", 10))
    end = _as_int(_summary_field(frame, "sleep_end_timestamp_utc", 8))
    if start is None or end is None or end <= start:
        return None

    def minutes(name: str, number: int) -> float | None:
        raw = _as_int(_summary_field(frame, name, number))
        # 0xFFFF is FIT's "no value" for a uint16, and would otherwise read as
        # a thousand hours of deep sleep.
        return None if raw is None or raw >= 0xFFFF else raw / 60.0

    return {
        "start": datetime.fromtimestamp(start + _GARMIN_EPOCH, tz=timezone.utc),
        "end": datetime.fromtimestamp(end + _GARMIN_EPOCH, tz=timezone.utc),
        "deep": minutes("deep_duration", 1),
        "light": minutes("light_duration", 2),
        "rem": minutes("rem_duration", 3),
        "awake": minutes("awake_duration", 4),
        "score": _as_int(_summary_field(frame, "sleep_score", 0)),
    }


class SleepParser(BaseParser):
    """
    Parses Garmin sleep FIT files.
    file_id.type is integer 49 on Garmin devices (fitdecode doesn't name this value);
    the string 'sleep' is accepted as well for forward compatibility.
    """

    def can_parse(self, fit_path: Path) -> bool:
        try:
            with fitdecode.FitReader(fit_path) as fit:
                for frame in fit:
                    if isinstance(frame, fitdecode.FitDataMessage) and frame.name == "file_id":
                        t = get(frame, "type")
                        # fitdecode decodes this as integer 49 on Garmin Fenix/Forerunner;
                        # newer fitdecode versions or other devices may give the string "sleep"
                        return t == 49 or as_str(t) == "sleep"
        except Exception:
            pass
        return False

    def parse(self, fit_path: Path) -> dict:
        """The night, preferring the watch's own account of it.

        ## Why the raw stage stream is not the answer on its own

        A SLEEP file carries two descriptions of the same night. There is the
        `sleep_level` stream — a record at each stage transition, which is what
        this used to read and nothing else — and there is `sleep_summary`,
        which is the watch's *finished* verdict: when the session began and
        ended, and how many minutes went to each stage.

        They do not agree, and the summary is the one the watch itself shows.
        The stream runs from the first transition the sensor noticed to the
        last, which on a real night off a fenix 6 was 01:19 to 09:26 — while
        the watch reported that night as 01:32 to 07:01, because Garmin's own
        onset rule trims the settling-down at the start and everything after
        the final waking. Reading only the stream therefore put two and a half
        hours of lying in bed inside the night, and made a chart that
        disagreed with the wrist it came from.

        So the summary wins where it exists: its window bounds the night, the
        timeline is clipped to it, and its per-stage minutes are the totals.
        The stream is still what draws the *shape*, because the summary has no
        shape in it — only durations.

        Files with no summary fall back to computing everything from the
        stream, which is every night this parser read before now.
        """
        device_info = {}
        sleep_levels = []   # list of (datetime, level_str)
        assessment = {}
        summary = None
        sleep_date = None

        with fitdecode.FitReader(fit_path) as fit:
            for frame in fit:
                if not isinstance(frame, fitdecode.FitDataMessage):
                    continue

                if frame.name == "file_id":
                    device_info = parse_file_id(frame)
                    tc = get(frame, "time_created")
                    if tc is not None:
                        sleep_date = tc.date() if hasattr(tc, "date") else tc

                elif frame.name == "sleep_level":
                    ts = get(frame, "timestamp")
                    level = as_str(get(frame, "sleep_level"))
                    if ts is not None and level is not None:
                        sleep_levels.append((ts, level))

                elif frame.name == _SLEEP_SUMMARY:
                    # One per session, and a file can hold a nap as well as a
                    # night. The longest wins: the night is what the day is
                    # named for, and a twenty-minute nap must not redefine it.
                    found = _read_summary(frame)
                    if found is not None and (
                        summary is None
                        or (found["end"] - found["start"])
                        > (summary["end"] - summary["start"])
                    ):
                        summary = found

                elif frame.name == "sleep_assessment":
                    score = get(frame, "overall_sleep_score")
                    if score is None:
                        score = get(frame, "combined_awake_score")
                    if score is None:
                        score = get(frame, "quality_score")
                    hrv = get(frame, "hrv_rmssd_5min")
                    if hrv is None:
                        hrv = get(frame, "avg_hrv_score")
                    assessment = {
                        "sleep_score": _to_float(score),
                        "resting_hr":  _to_float(get(frame, "resting_heart_rate")),
                        "hrv":         _to_float(hrv),
                    }

        # Everything outside the watch's own session is lying in bed, not
        # sleeping in it. Clipped before anything is totalled or drawn, so the
        # hours, the timeline and the window cannot disagree with each other.
        if summary is not None:
            sleep_levels = _clip(sleep_levels, summary["start"], summary["end"])

        # Use last sleep_level timestamp as the wake-up date (what Garmin shows as "sleep date")
        if sleep_levels:
            last_ts = sleep_levels[-1][0]
            sleep_date = last_ts.date() if hasattr(last_ts, "date") else sleep_date

        metrics = _compute_stage_durations(sleep_levels)
        if summary is not None:
            metrics.update(_summary_metrics(summary))
        for field, value in assessment.items():
            if value is not None:
                metrics[field] = value

        return {
            "type":    "daily",
            "device":  device_info,
            "date":    sleep_date,
            "metrics": metrics,
            "stages":  _stage_segments(sleep_levels),
        }


def _summary_metrics(summary: dict) -> dict:
    """The watch's own stage totals, as the columns store them.

    Only the durations it actually reported. A firmware that writes the window
    and leaves a stage blank should cost that stage rather than overwriting a
    perfectly good figure computed from the stream with a zero.

    `sleep_hours` is deep plus light plus REM, deliberately excluding awake —
    the same rule the stream-based total follows, and the same one the whole
    app is built on.
    """
    out: dict = {}
    slept = 0.0
    for field, key in (("deep", "sleep_deep_hours"),
                       ("light", "sleep_light_hours"),
                       ("rem", "sleep_rem_hours")):
        hours = summary.get(field)
        if hours is None:
            continue
        out[key] = round(hours, 2)
        slept += hours
    if slept > 0:
        out["sleep_hours"] = round(slept, 2)
    if summary.get("awake"):
        out["sleep_awake_hours"] = round(summary["awake"], 2)
    if summary.get("score"):
        out["sleep_score"] = float(summary["score"])
    return out


def _clip(levels: list, start, end) -> list:
    """The stage stream, cut to the watch's own sleep window.

    Each record marks the *start* of a stage and the next one ends it, so
    clipping is not a filter: the record that straddles the window's opening
    has to survive with its timestamp moved forward, or the night would begin
    at whatever the next transition happened to be. Same at the far end, where
    a closing record is synthesised so the last real stage has something to end
    it.
    """
    kept = []
    for index, (ts, level) in enumerate(levels):
        following = levels[index + 1][0] if index + 1 < len(levels) else None
        # Wholly before the window, and not still running when it opened.
        # The final record has nothing to end it and so ends where it starts —
        # otherwise a stream that finished hours before the window opened would
        # contribute its last record to it.
        if (following if following is not None else ts) <= start:
            continue
        if ts >= end:
            continue
        kept.append((max(ts, start), level))
    if not kept:
        return []
    # A record at the window's end, so the final stage has a length. It carries
    # no level of its own — nothing reads the last record's — and
    # `_stage_segments` drops it after using it as a boundary.
    if kept[-1][0] < end:
        kept.append((end, kept[-1][1]))
    return kept


def _stage_segments(levels: list) -> list[dict]:
    """
    The night as a list of spans, for drawing.

    ``_compute_stage_durations`` already walks these records to total the hours
    per stage and throws the shape away. The shape is the interesting part: how
    long the first deep block lasted, whether REM came in cycles, how many times
    the night broke. Garmin Connect draws exactly this and it is the one sleep
    view a summary cannot reconstruct.

    Awake and unmeasurable spans are kept here even though they do not count
    toward the sleep total — a night with four wakings is what the chart is for.
    Each record marks the *start* of a stage and the next one ends it, so the
    final record contributes no span.
    """
    out = []
    for i in range(len(levels) - 1):
        ts_start, level = levels[i]
        ts_end = levels[i + 1][0]
        seconds = (ts_end - ts_start).total_seconds()
        # Skip zero-length and any record that goes backwards; a corrupt
        # timestamp should cost one span rather than the whole night.
        if seconds <= 0:
            continue
        out.append({
            "start": ts_start.isoformat(),
            "end":   ts_end.isoformat(),
            "level": (level or "unmeasurable").lower(),
            "seconds": round(seconds),
        })
    return out


def _to_float(value) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _compute_stage_durations(levels: list) -> dict:
    """
    Accumulate hours in each sleep stage.
    Each sleep_level record marks the START of that stage; the next record ends it.
    Stages: deep, rem, light (includes any "light"/"sleep" variant), awake.

    Awake time is *recorded* but stays out of ``sleep_hours``, which is time
    asleep. It used to be neither — dropped on the floor with the unmeasurable
    spans — and it is one of the more useful numbers of the night: eight hours
    in bed with ninety minutes awake is a different night from six and a half
    slept straight through, and the totals alone cannot tell them apart.
    """
    deep = light = rem = awake = total = 0.0

    for i in range(len(levels) - 1):
        ts_start, level = levels[i]
        ts_end = levels[i + 1][0]
        hours = (ts_end - ts_start).total_seconds() / 3600.0
        lvl = (level or "").lower()

        if "deep" in lvl:
            deep += hours
            total += hours
        elif "rem" in lvl:
            rem += hours
            total += hours
        elif "light" in lvl or ("sleep" in lvl and "awake" not in lvl):
            light += hours
            total += hours
        elif "awake" in lvl or "wake" in lvl:
            # Counted on its own, never towards the total.
            awake += hours
        # "unmeasurable" is neither slept nor known to be awake

    result = {}
    if total > 0:
        result["sleep_hours"] = round(total, 2)
    if deep > 0:
        result["sleep_deep_hours"] = round(deep, 2)
    if light > 0:
        result["sleep_light_hours"] = round(light, 2)
    if rem > 0:
        result["sleep_rem_hours"] = round(rem, 2)
    if awake > 0:
        result["sleep_awake_hours"] = round(awake, 2)
    return result
