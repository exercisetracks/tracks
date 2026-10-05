# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import fitdecode
from datetime import datetime, timedelta, timezone
from pathlib import Path
from app.parsers.base import BaseParser
from app.parsers.utils import get, get_by_iter, as_str, parse_file_id

# monitoring_b / monitoring  — resting HR from the device's daily summary
# 68 — Garmin HRV status file (hrv_status_summary + per-5-min hrv_value records)
_MONITORING_TYPES = {"monitoring_b", "monitoring"}
_HRV_TYPE = 68

# Messages worth opening a file for whatever its file_id claims to be.
_BY_CONTENT = {"spo2_data", "hsa_body_battery_data"}


def roll_up_body_battery(levels: list[int], charged: list[int],
                         drained: list[int]) -> dict:
    """A day of Body Battery readings reduced to the five stored numbers.

    Split out of the parse loop so it can be tested without a FIT file, which is
    the only part of this worth testing: reading the field is fitdecode's job,
    and deciding what a day *is* is ours.

    Charged and drained are totals rather than high minus low. A day that climbs
    twenty, spends thirty, and recovers ten has charged thirty and drained
    thirty, and its high-minus-low says neither.

    ## Where charged and drained come from when nothing reports them

    Newer watches write `hsa_body_battery_data` with the two deltas per
    interval. A fenix 6 does not — it reports the *level* alone, buried in the
    stress message (see the parse loop) — so those are reconstructed from the
    series: every step up is charge, every step down is drain. That is the same
    quantity by a different route, and it is right by construction because
    Body Battery only ever moves one point at a time.
    """
    out: dict = {}
    if levels:
        out["body_battery_high"] = max(levels)
        out["body_battery_low"] = min(levels)
        # The file is written in time order, so the last reading is the most
        # recent one — "now" for today, "at bedtime" for any day before.
        out["body_battery_last"] = levels[-1]

    if not charged and not drained and len(levels) > 1:
        steps = [b - a for a, b in zip(levels, levels[1:])]
        gained = sum(d for d in steps if d > 0)
        spent = -sum(d for d in steps if d < 0)
        if gained:
            out["body_battery_charged"] = gained
        if spent:
            out["body_battery_drained"] = spent
        return out

    if charged:
        out["body_battery_charged"] = sum(charged)
    if drained:
        out["body_battery_drained"] = sum(drained)
    return out


# The two activity types in FIT's enum whose "cycles" are footfalls. The field
# means something different for each type — pedal strokes on a bike, arm strokes
# in a pool — so counting them all would add a swim's strokes to a walk's steps
# and call the result a step count.
_STEP_ACTIVITIES = {"walking", "running"}


def roll_up_steps(totals: dict[str, int]) -> int | None:
    """A day's step total from the per-activity-type counters.

    ## What these records actually look like

    A Garmin monitoring file carries one running counter *per activity type*.
    Walking and running each accumulate their own, and the day's step count is
    the two added together — not the largest of them, which would silently drop
    a run, and not the sum of every reading, which would count the same steps
    once per record.

    So the caller keeps the high-water mark for each type (they only ever climb
    within a day) and this adds the ones that are footfalls.

    Two earlier versions of this were wrong in ways worth recording. The first
    took the *first* reading it saw, which for a counter that starts at zero
    each midnight is close to zero. The second took the maximum across all
    types, which is the walking total on most days and the wrong number on any
    day with a bike ride in it.
    """
    counted = [v for k, v in totals.items() if k in _STEP_ACTIVITIES and v >= 0]
    return sum(counted) if counted else None


def roll_up_resting_calories(rate_per_day: int | None, minutes: int | None) -> int | None:
    """The calories a body spent today just being one.

    The watch reports a *daily* resting metabolic rate — one number for the
    whole day — and how far into the day its records have got. Today is only
    partly over, so the resting share is pro-rated: at nine in the morning a
    person has not yet spent a full day's worth of being alive, and adding the
    whole figure would show a total calorie count that runs ahead of the clock.

    This is what Garmin Connect does with the same two numbers, which matters
    more than the arithmetic: the point of showing total calories at all is that
    it matches what the watch face says.
    """
    if not rate_per_day or rate_per_day <= 0:
        return None
    # ``None`` means the file said nothing about how far into the day it
    # reached, which is a whole day's worth. **Zero** means it reached two
    # minutes past midnight, which is not — and conflating the two is how the
    # far side of a file that crossed midnight claimed a full day's resting
    # burn at 00:02 and then won the merge with it.
    elapsed = MINUTES_PER_DAY if minutes is None else minutes
    share = min(max(elapsed, 0), MINUTES_PER_DAY) / MINUTES_PER_DAY
    return round(rate_per_day * share) or None


MINUTES_PER_DAY = 1440


def roll_up_active_calories(totals: dict[str, int]) -> int | None:
    """A day's active calories, added up the same way steps are.

    This used to be a single high-water mark across every record, on the stated
    reasoning that active calories were one counter for the day and the same
    rising figure appeared on records of every type. The files say otherwise: a
    fenix 6 writes a *separate* counter per activity type, and one evening reads
    175 on its walking records and 182 on its generic ones at the same instant.
    Taking the larger threw one of them away.

    Gadgetbridge sums these per type from the same message, which is the closest
    thing to a second opinion available on an undocumented format.

    Unlike steps, every activity type counts. A calorie burned pedalling is
    still a calorie; a pedal stroke is not a footfall.
    """
    counted = [v for v in totals.values() if v >= 0]
    return sum(counted) if counted else None


def _is_parseable(t) -> bool:
    return t == _HRV_TYPE or as_str(t) in _MONITORING_TYPES


class DailyHealthParser(BaseParser):
    """
    Parses two kinds of Garmin daily-health FIT files:
      - monitoring_b / monitoring  (GARMIN/MONITOR/) — resting HR, steps, stress, respiration, SpO2
      - type 68                    (GARMIN/)          — HRV status summary

    Stress comes back twice over: as the day's average, which is a column, and
    as the reading-by-reading series, which is not. Both are wanted — the
    average is what a dial shows and the series is what a *day* actually did.
    """

    def can_parse(self, fit_path: Path) -> bool:
        try:
            with fitdecode.FitReader(fit_path) as fit:
                for frame in fit:
                    if isinstance(frame, fitdecode.FitDataMessage) and frame.name == "file_id":
                        if _is_parseable(get(frame, "type")):
                            return True
                    # Also accept files carrying readings we want regardless of
                    # their declared type. Both of these turn up in files whose
                    # file_id says something this parser does not recognise —
                    # what matters is that the messages are in there.
                    if isinstance(frame, fitdecode.FitDataMessage) and frame.name in _BY_CONTENT:
                        return True
        except Exception:
            pass
        return False

    def parse(self, fit_path: Path) -> dict:
        """Every local day this file describes.

        ## Why a list of days, and why *local* ones

        Because a monitoring file is not a day. It is a few hours of epochs, its
        counters reset at **local** midnight, and it routinely straddles one: a
        real file off a fenix 6 runs from 23:44 to 02:42 UTC, which is 17:44 to
        20:42 the same evening at UTC−6, with the step counter climbing
        straight through UTC midnight without a flicker.

        Filing the whole file under the UTC date of its ``file_id`` — which is
        what this did — therefore goes wrong twice over. It puts an evening's
        readings on tomorrow, and because a day's figures are merged by taking
        the larger of two readings (see ``_DAILY_METRIC_MAX``), tomorrow then
        inherits yesterday's finished step count and *starts* at three thousand.
        That is the "steps never reset at midnight" the numbers stopped matching
        the watch over.

        So each record is filed by the local date of its own timestamp, and a
        file that crosses midnight comes back as two days.

        The offset comes from the file: ``monitoring_info`` carries the same
        instant twice, once in UTC and once local. Where a file does not declare
        one — an HRV summary, a sleep file — the fallback is UTC rather than a
        guess, because the phone parses these same files with the same rules and
        two clients guessing differently would file one day under two dates.
        """
        device_info = {}
        offset = _utc_offset(fit_path)
        days: dict = {}
        fallback_date = None

        def day_at(when) -> dict | None:
            """The bucket a reading belongs to, by its own local date."""
            date = _local_date(when, offset) or fallback_date
            if date is None:
                return None
            bucket = days.setdefault(date, _new_day())
            when_utc = _epoch(when)
            if when_utc is not None and (bucket["last_at"] is None
                                         or when_utc > bucket["last_at"]):
                bucket["last_at"] = when_utc
            return bucket

        # A personal constant rather than a reading, so it belongs to every day
        # the file touches rather than to the epoch that carried it.
        resting_rate: int | None = None
        # Monitoring records number themselves against the previous one; see
        # _resolve_timestamp for the 16-bit rollover this unwinds.
        last_monitoring = None

        with fitdecode.FitReader(fit_path) as fit:
            for frame in fit:
                if not isinstance(frame, fitdecode.FitDataMessage):
                    continue

                if frame.name == "file_id":
                    device_info = parse_file_id(frame)
                    fallback_date = _local_date(get(frame, "time_created"), offset)

                elif frame.name == "monitoring_hr_data":
                    day = day_at(get(frame, "timestamp"))
                    rhr = get(frame, "current_day_resting_heart_rate")
                    if day is not None and rhr is not None:
                        # The newest reading wins, not the first. The watch
                        # reports "resting heart rate for the current day"
                        # continuously and has not recomputed it at two minutes
                        # past midnight — so the first thing a new day hears is
                        # yesterday's figure, and first-wins pinned it there.
                        day["metrics"]["resting_hr"] = float(rhr)

                elif frame.name == "monitoring_info":
                    rmr = get(frame, "resting_metabolic_rate")
                    if rmr is not None and resting_rate is None:
                        try:
                            resting_rate = int(rmr)
                        except (TypeError, ValueError):
                            pass
                    # avg_spo2 (field #194) — newer FIT profile standard. A
                    # fenix 6 may not populate it; per-epoch spo2_data records
                    # are handled below as a fallback.
                    spo2 = get(frame, "avg_spo2")
                    day = day_at(get(frame, "timestamp"))
                    if day is not None and spo2 is not None:
                        try:
                            v = float(spo2)
                            if 50 <= v <= 100:
                                day["metrics"]["spo2"] = round(v, 1)
                        except (TypeError, ValueError):
                            pass

                elif frame.name == "monitoring":
                    when = get(frame, "timestamp")
                    if when is None:
                        when = _resolve_timestamp(get(frame, "timestamp_16"),
                                                  last_monitoring)
                    # Rolling, not fixed. A 16-bit second wraps every eighteen
                    # hours and the difference is clamped to half of that, so
                    # measuring every record against the file's *first* full
                    # timestamp mis-resolves everything past nine hours in —
                    # which lands a whole evening on the following day.
                    if when is not None:
                        last_monitoring = when
                    when = _closes_the_day(when, offset)
                    day = day_at(when)
                    if day is None:
                        continue
                    # Per-epoch record: a running count for one activity type,
                    # of footfalls and of calories alike. Both are high-water
                    # marks per type within one local day — they only climb —
                    # and are added up afterwards. See the roll-ups above.
                    activity = as_str(get(frame, "activity_type")) or "walking"
                    steps = get(frame, "steps")
                    if steps is None and activity in _STEP_ACTIVITIES:
                        steps = get(frame, "cycles")
                    if steps is not None and activity in _STEP_ACTIVITIES:
                        try:
                            n = int(steps)
                            if n > day["steps"].get(activity, -1):
                                day["steps"][activity] = n
                        except (TypeError, ValueError):
                            pass
                    active_cal = get(frame, "active_calories")
                    if active_cal is not None:
                        try:
                            n = int(active_cal)
                            if n > day["calories"].get(activity, -1):
                                day["calories"][activity] = n
                        except (TypeError, ValueError):
                            pass
                    duration = get(frame, "duration_min")
                    if duration is not None:
                        try:
                            day["elapsed"] = max(day["elapsed"] or 0, int(duration))
                        except (TypeError, ValueError):
                            pass
                    minute = _minute_of_day(when, offset)
                    if minute is not None:
                        day["elapsed"] = max(day["elapsed"] or 0, minute)

                elif frame.name == "stress_level":
                    # Its own message, not a field on `monitoring`. A Fenix
                    # emits several hundred of these per file.
                    at = get(frame, "stress_level_time") or get(frame, "timestamp")
                    day = day_at(at)
                    if day is None:
                        continue
                    stress = get(frame, "stress_level_value")
                    if stress is not None:
                        try:
                            v = float(stress)
                            # Negative is Garmin's "unmeasured / off wrist".
                            # Zero is a real reading and stays.
                            if 0 <= v <= 100:
                                day["stress"].append(v)
                                # And the reading keeps its clock. The day's
                                # average is one number for a quantity that
                                # moves all day — a morning of calm and an
                                # afternoon of 80 average out to the same 40 as
                                # a flat, mediocre day, and the watch itself
                                # draws the curve. See ``_finish_day``.
                                minute = _minute_of_day(at, offset)
                                if minute is not None:
                                    day["stress_at"][minute] = int(round(v))
                        except (TypeError, ValueError):
                            pass

                    # And this is where Body Battery lives: field 3 of this
                    # message, `body_energy`, which fitdecode's profile does not
                    # name — it comes through as `unknown_3`. Gadgetbridge reads
                    # the same field for the same reason.
                    energy = get(frame, "body_energy")
                    if energy is None:
                        energy = get_by_iter(frame, "unknown_3")
                    if energy is not None:
                        try:
                            v = int(energy)
                            if 0 <= v <= 100:
                                day["battery"].append(v)
                        except (TypeError, ValueError):
                            pass

                elif frame.name == "respiration_rate":
                    day = day_at(get(frame, "timestamp"))
                    resp = get(frame, "respiration_rate")
                    if day is not None and resp is not None:
                        try:
                            v = float(resp)
                            # The upper bound throws out the occasional absurd
                            # reading rather than letting one drag the average.
                            if 0 < v <= 60:
                                day["resp"].append(v)
                        except (TypeError, ValueError):
                            pass

                elif frame.name == "spo2_data":
                    day = day_at(get(frame, "timestamp"))
                    reading = get(frame, "reading_spo2")
                    if day is not None and reading is not None:
                        try:
                            v = float(reading)
                            if 50 <= v <= 100:
                                day["spo2"].append(v)
                        except (TypeError, ValueError):
                            pass

                elif frame.name == "hsa_body_battery_data":
                    # Message 314. `level` is the 0-100 reading at the end of
                    # the interval; `charged` and `uncharged` are what was
                    # gained and spent during it. Taken from the watch and never
                    # recomputed — Garmin has not published the model, so a
                    # reimplementation would be a different metric wearing the
                    # same name.
                    day = day_at(get(frame, "timestamp"))
                    if day is None:
                        continue
                    level = get(frame, "level")
                    if level is not None:
                        try:
                            v = int(level)
                            # Negative is the profile's "no reading" (the field
                            # is signed), and the scale is 0-100.
                            if 0 <= v <= 100:
                                day["battery"].append(v)
                        except (TypeError, ValueError):
                            pass
                    for field, bucket in (("charged", "charged"),
                                          ("uncharged", "drained")):
                        raw = get(frame, field)
                        if raw is None:
                            continue
                        try:
                            n = int(raw)
                            if n > 0:
                                day[bucket].append(n)
                        except (TypeError, ValueError):
                            pass

                elif frame.name == "hrv_status_summary":
                    day = day_at(get(frame, "timestamp"))
                    if day is None:
                        continue
                    hrv = get(frame, "last_night_average")
                    if hrv is None:
                        hrv = get(frame, "weekly_average")
                    if hrv is not None and "hrv" not in day["metrics"]:
                        day["metrics"]["hrv"] = float(hrv)

        return {
            "type":    "daily",
            "device":  device_info,
            "days":    [_finish_day(date, day, resting_rate, offset)
                        for date, day in sorted(days.items())],
        }


# ── Time ─────────────────────────────────────────────────────────────────────

_GARMIN_EPOCH = 631065600
_SECONDS_PER_DAY = 86400


def _utc_offset(fit_path: Path) -> timedelta:
    """How far the watch's clock is from UTC, read out of the file.

    ``monitoring_info`` carries the same instant twice — once in UTC and once in
    local time — and the difference between them is the answer. A pass of its
    own because it has to be known before the first record is filed, and a
    monitoring file is a few kilobytes.

    Zero where the file does not say. Deliberately not a guess from the server's
    own clock or the user's profile: the phone parses these same files with the
    same rules, and two clients guessing differently would file one day under
    two dates and then merge it as two.
    """
    try:
        with fitdecode.FitReader(fit_path) as fit:
            for frame in fit:
                if not isinstance(frame, fitdecode.FitDataMessage):
                    continue
                if frame.name != "monitoring_info":
                    continue
                utc = get(frame, "timestamp")
                local = get(frame, "local_timestamp")
                if utc is None or local is None:
                    continue
                delta = _epoch(local) - _epoch(utc)
                # Sanity: real offsets are inside a day. Anything else means the
                # field was not what this thinks it is, and UTC beats a date
                # filed a century out.
                if -_SECONDS_PER_DAY < delta < _SECONDS_PER_DAY:
                    return timedelta(seconds=delta)
    except Exception:
        pass
    return timedelta(0)


def _epoch(when) -> int | None:
    """A fitdecode datetime as a Unix second, whether or not it is tz-aware."""
    if when is None or not hasattr(when, "timestamp"):
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return int(when.timestamp())


def _closes_the_day(when, offset: timedelta):
    """A monitoring record stamped exactly at local midnight closes the day that
    has just ended, rather than opening the one that has just begun.

    The watch writes one there carrying the finished day's totals — 3,333 steps
    at 00:00:00 — and its counters reset immediately afterwards. Taken at face
    value that record is the new day's *first* reading, so the new day begins at
    three thousand steps and stays there: precisely the "steps never reset at
    midnight" this rewrite is about. One second is enough to put it back where
    it belongs, and any record even a second later is genuinely the new day's.
    """
    seconds = _epoch(when)
    if seconds is None:
        return when
    if int(seconds + offset.total_seconds()) % _SECONDS_PER_DAY != 0:
        return when
    return datetime.fromtimestamp(seconds - 1, tz=timezone.utc)


def _local_date(when, offset: timedelta):
    """The **local** date a FIT timestamp falls on.

    Local, because that is the day the watch's own counters belong to and the
    day the person looking at the watch is thinking of. See ``parse``.
    """
    seconds = _epoch(when)
    if seconds is None:
        return None
    return datetime.fromtimestamp(seconds + offset.total_seconds(),
                                  tz=timezone.utc).date()


def _minute_of_day(when, offset: timedelta) -> int | None:
    """Minutes since local midnight — the watch's own ``duration_min``."""
    seconds = _epoch(when)
    if seconds is None:
        return None
    return int((seconds + offset.total_seconds()) % _SECONDS_PER_DAY) // 60


def _resolve_timestamp(ts16, last):
    """A monitoring record's 16-bit timestamp, against the last full one.

    Garmin writes most epoch records with only the low sixteen bits of the
    Garmin-epoch second, which wrap every eighteen hours. The reference is the
    last record that carried a whole timestamp. Same arithmetic as
    Gadgetbridge's ``FitMonitoring.computeTimestamp``.
    """
    reference = _epoch(last)
    if ts16 is None or reference is None:
        return last
    garmin_reference = reference - _GARMIN_EPOCH
    diff = (int(ts16) & 0xFFFF) - (garmin_reference & 0xFFFF)
    if diff < -32768:
        diff += 65536
    elif diff > 32768:
        diff -= 65536
    return datetime.fromtimestamp(reference + diff, tz=timezone.utc)


# ── Days ─────────────────────────────────────────────────────────────────────


def _new_day() -> dict:
    return {
        "metrics": {},
        "stress": [], "resp": [], "spo2": [], "battery": [],
        "charged": [], "drained": [],
        # Minute of local day -> stress level. A dict rather than a list
        # because a day is described by several files that overlap, and the
        # same minute arriving twice is one reading, not two.
        "stress_at": {},
        "steps": {}, "calories": {},
        "elapsed": None, "last_at": None,
    }


def _finish_day(date, day: dict, resting_rate: int | None,
                offset: timedelta) -> dict:
    """One bucket, reduced to the columns the importer writes."""
    metrics = dict(day["metrics"])

    if day["stress"]:
        metrics["avg_stress_level"] = round(sum(day["stress"]) / len(day["stress"]), 1)
    if day["resp"]:
        metrics["avg_respiration_rate"] = round(sum(day["resp"]) / len(day["resp"]), 2)
    metrics.update(roll_up_body_battery(day["battery"], day["charged"], day["drained"]))

    steps = roll_up_steps(day["steps"])
    if steps is not None:
        metrics["steps"] = steps
    calories = roll_up_active_calories(day["calories"])
    if calories is not None:
        metrics["active_calories"] = calories

    # The watch reports how far into the day it has got on the first epoch
    # record of a file and not afterwards, so a slice carrying only stress and
    # heart-rate records has to be timed by its own clock — otherwise the far
    # side of a file that crossed midnight claims a whole day's resting burn two
    # minutes after it.
    elapsed = day["elapsed"]
    if elapsed is None and day["last_at"] is not None:
        elapsed = int((day["last_at"] + offset.total_seconds())
                      % _SECONDS_PER_DAY) // 60
    resting = roll_up_resting_calories(resting_rate, elapsed)
    if resting is not None:
        metrics["resting_calories"] = resting

    if day["spo2"] and "spo2" not in metrics:
        metrics["spo2"] = round(sum(day["spo2"]) / len(day["spo2"]), 1)

    # Outside ``metrics``, which is an allow-list of *columns*. This is a
    # variable-length series and goes in ``extra`` alongside the sleep
    # timeline, for the same reason and by the same route.
    return {
        "date": date,
        "metrics": metrics,
        "stress_series": [[m, v] for m, v in sorted(day["stress_at"].items())],
    }
