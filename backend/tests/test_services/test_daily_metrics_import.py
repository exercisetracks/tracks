# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What survives the trip from a monitoring file into a day's row.

Two things are checked here, and the first one is the reason the second exists.

The importer filters a parser's output through an allow-list before writing it.
That is the right shape — a parser gaining a key should not silently start
writing a column — but it is a list that has to be kept in step by hand, and it
had drifted: steps, active calories, average stress and respiration were parsed
out of every monitoring file, dropped at the allow-list, and left null in every
row, while the model, the API schema, the sync delta and both clients all
carried them. The Health screen's Steps and Stress cards were reading columns
nothing had ever written to.

So the first test is not about any particular field. It asserts that every
watch-derived column on the model is one the importer will actually write, which
is the invariant that was broken and the one that catches the next drift.
"""

import pytest
from datetime import date, datetime, timedelta, timezone

from app.models.metrics import DailyMetric
from app.parsers.daily_health import (
    _closes_the_day,
    _local_date,
    _minute_of_day,
    _resolve_timestamp,
    roll_up_active_calories,
    roll_up_resting_calories,
    roll_up_body_battery,
    roll_up_steps,
)
from app.services.fit_import import (
    _DAILY_METRIC_FIELDS,
    _DAILY_METRIC_FROM_FILES,
    _merge_daily_value,
    _merge_stress_series,
)

# Columns that are not written by an import, with the reason each is exempt.
_NOT_FROM_A_DEVICE = {
    "id", "date", "user_id", "device_id", "updated_at",  # bookkeeping
    "extra",                                             # free-form side-channel
    "weight_kg", "hydration_ml", "calories_in",          # entered by the user
    "uid", "clock", "server_seq", "pending_refs",        # sync bookkeeping
}


class TestAllowList:
    def test_every_watch_derived_column_can_be_written(self):
        columns = {c.name for c in DailyMetric.__table__.columns}
        expected = columns - _NOT_FROM_A_DEVICE
        missing = expected - _DAILY_METRIC_FIELDS
        assert not missing, (
            f"{sorted(missing)} exist on daily_metrics and are served to clients, "
            "but the importer's allow-list will drop them — they will be null forever"
        )

    def test_the_allow_list_does_not_name_columns_that_do_not_exist(self):
        columns = {c.name for c in DailyMetric.__table__.columns}
        assert not (_DAILY_METRIC_FIELDS - columns)

    def test_a_re_parse_never_clears_training_load(self):
        # A re-parse empties the watch-derived columns first, because the merge
        # rules only ever widen and a figure that is too large survives every
        # correction that does not start from empty. `training_load` sits on the
        # same row and in the same allow-list, and is the *activity* importer's
        # own arithmetic — clearing it would take out the fitness model's input
        # and nothing in a monitoring file would ever put it back.
        assert "training_load" in _DAILY_METRIC_FIELDS
        assert "training_load" not in _DAILY_METRIC_FROM_FILES
        assert _DAILY_METRIC_FROM_FILES < _DAILY_METRIC_FIELDS

    def test_everything_else_is_rebuilt_by_a_re_parse(self):
        assert _DAILY_METRIC_FIELDS - _DAILY_METRIC_FROM_FILES == {"training_load"}


class TestMergingASecondFileForTheSameDay:
    """A watch appends to one monitoring file all day, so each sync brings a
    fuller version of a day already partly recorded."""

    def test_an_absent_value_is_simply_taken(self):
        assert _merge_daily_value("resting_hr", None, 48) == 48

    def test_a_summary_keeps_the_first_answer(self):
        # HRV is computed once by the device from the night just gone; a second
        # file repeating it is the same measurement, not a new one.
        assert _merge_daily_value("hrv", 48, 51) == 48

    def test_resting_heart_rate_takes_the_settled_reading(self):
        # The watch reports "resting HR for the current day" continuously and
        # has not recomputed it at two minutes past midnight, so the first thing
        # a new day hears is yesterday's figure. First-wins pinned it there.
        assert _merge_daily_value("resting_hr", 56, 59) == 59

    def test_the_day_high_widens(self):
        assert _merge_daily_value("body_battery_high", 60, 82) == 82

    def test_the_day_high_does_not_shrink(self):
        # The afternoon file covers a shorter window than the evening one did.
        assert _merge_daily_value("body_battery_high", 82, 60) == 82

    def test_the_day_low_widens_downward(self):
        assert _merge_daily_value("body_battery_low", 40, 12) == 12
        assert _merge_daily_value("body_battery_low", 12, 40) == 12

    def test_steps_only_ever_climb(self):
        # First-wins here was the specific bug: a morning sync would pin the
        # day's step count at whatever it was at breakfast.
        assert _merge_daily_value("steps", 2_100, 11_400) == 11_400

    def test_the_current_reading_is_always_the_newest(self):
        assert _merge_daily_value("body_battery_last", 71, 34) == 34
        assert _merge_daily_value("body_battery_last", 34, 71) == 71


class TestBodyBatteryRollup:
    def test_a_day_of_intervals(self):
        out = roll_up_body_battery([40, 55, 71, 68, 22], [15, 16], [3, 46])
        assert out["body_battery_high"] == 71
        assert out["body_battery_low"] == 22
        assert out["body_battery_last"] == 22

    def test_charged_and_drained_are_totals_not_the_range(self):
        # Up twenty, down thirty, up ten: high minus low describes none of that.
        out = roll_up_body_battery([50, 70, 40, 50], [20, 10], [30])
        assert out["body_battery_charged"] == 30
        assert out["body_battery_drained"] == 30
        assert out["body_battery_high"] - out["body_battery_low"] == 30

    def test_a_day_with_no_readings_writes_nothing(self):
        # Not zeroes. A zero Body Battery means "completely spent", which is a
        # very different claim from "the watch was in a drawer".
        assert roll_up_body_battery([], [], []) == {}

    def test_levels_without_deltas_reconstruct_them(self):
        # A fenix 6 reports the level and nothing else — it has no
        # hsa_body_battery_data message at all — so charged and drained are
        # rebuilt from the series. Every step up is charge, every step down is
        # drain, which is the same quantity by a different route.
        out = roll_up_body_battery([12, 44], [], [])
        assert out == {
            "body_battery_high": 44,
            "body_battery_low": 12,
            "body_battery_last": 44,
            "body_battery_charged": 32,
        }

    def test_a_reconstructed_day_counts_both_directions(self):
        # Up twenty, down thirty, up ten — the same day as the test above, and
        # it has to come out the same whether the watch reported the deltas or
        # the parser rebuilt them.
        out = roll_up_body_battery([50, 70, 40, 50], [], [])
        assert out["body_battery_charged"] == 30
        assert out["body_battery_drained"] == 30

    def test_a_single_reading_has_no_movement_to_report(self):
        # One sample is a level, not a curve; claiming zero charge would be a
        # measurement nobody made.
        out = roll_up_body_battery([44], [], [])
        assert "body_battery_charged" not in out
        assert "body_battery_drained" not in out

    def test_reported_deltas_win_over_reconstruction(self):
        # A watch that reports them knows better: its intervals can rise and
        # fall inside a single sample gap, which the series cannot see.
        out = roll_up_body_battery([50, 70], [40], [15])
        assert out["body_battery_charged"] == 40
        assert out["body_battery_drained"] == 15


class TestStepRollup:
    """A day's steps out of per-activity-type counters.

    Three things have to be true at once and each has been wrong at some point:
    the counter is cumulative (so the day is the high-water mark, not the first
    reading, which for a counter that resets at midnight is near zero), it is
    kept per activity type (so walking and running add up), and `cycles` means
    something else entirely for the types that are not footfalls (so a bike
    ride's pedal strokes must not join in).
    """

    def test_walking_and_running_add_up(self):
        assert roll_up_steps({"walking": 6200, "running": 3100}) == 9300

    def test_cycling_and_swimming_cycles_are_not_steps(self):
        # 9000 pedal strokes is not 9000 steps, and the swim is strokes too.
        assert roll_up_steps({"walking": 800, "cycling": 9000, "swimming": 1200}) == 800

    def test_a_day_with_no_records_writes_nothing(self):
        # Not zero. Zero steps is a claim about the wearer; None is a claim
        # about the file.
        assert roll_up_steps({}) is None
        assert roll_up_steps({"generic": 400}) is None

    def test_negative_sentinels_are_ignored(self):
        assert roll_up_steps({"walking": -1, "running": 300}) == 300


class TestActiveCalorieRollup:
    """One counter *per activity type*, added up — as steps are.

    This was a single high-water mark across every record, on the stated
    reasoning that the same rising figure appeared on records of every type.
    The files say otherwise: a fenix 6 reads 175 on its walking records and 182
    on its generic ones at the same instant, and taking the larger threw one of
    them away. Gadgetbridge sums them per type from the same message.
    """

    def test_every_activity_type_counts(self):
        # Unlike steps: a calorie burned pedalling is still a calorie, while a
        # pedal stroke is not a footfall.
        assert roll_up_active_calories({"walking": 175, "generic": 182}) == 357
        assert roll_up_active_calories({"walking": 120, "cycling": 300}) == 420

    def test_a_day_with_no_records_writes_nothing(self):
        assert roll_up_active_calories({}) is None

    def test_negative_sentinels_are_ignored(self):
        assert roll_up_active_calories({"walking": -1, "generic": 42}) == 42
        assert roll_up_active_calories({"walking": -1}) is None


class TestAwakeTime:
    """Time in bed and not asleep.

    The watch has always measured it and the parser used to drop it with the
    unmeasurable spans, so a night that broke six times and one slept straight
    through arrived looking identical. It is kept out of ``sleep_hours`` on
    purpose — that column is time *asleep*, and quietly widening it would change
    what every existing night meant.
    """

    def _levels(self, *pairs):
        from datetime import datetime, timedelta
        base = datetime(2026, 8, 17, 22, 0)
        out, t = [], base
        for level, minutes in pairs:
            out.append((t, level))
            t += timedelta(minutes=minutes)
        out.append((t, "awake"))
        return out

    def test_awake_spans_are_totalled_on_their_own(self):
        from app.parsers.sleep import _compute_stage_durations
        out = _compute_stage_durations(
            self._levels(("deep", 60), ("awake", 30), ("light", 90)),
        )
        assert out["sleep_awake_hours"] == 0.5
        assert out["sleep_deep_hours"] == 1.0
        assert out["sleep_light_hours"] == 1.5

    def test_awake_never_counts_towards_the_sleep_total(self):
        from app.parsers.sleep import _compute_stage_durations
        out = _compute_stage_durations(
            self._levels(("light", 120), ("awake", 60), ("rem", 60)),
        )
        assert out["sleep_hours"] == 3.0

    def test_a_night_with_no_waking_reports_none(self):
        from app.parsers.sleep import _compute_stage_durations
        out = _compute_stage_durations([
            (__import__("datetime").datetime(2026, 8, 17, 22, 0), "deep"),
            (__import__("datetime").datetime(2026, 8, 18, 5, 0), "light"),
        ])
        assert "sleep_awake_hours" not in out

    def test_awake_is_carried_by_the_import_allow_list(self):
        assert "sleep_awake_hours" in _DAILY_METRIC_FIELDS


class TestRestingCalories:
    """The half of the day's calorie burn that is just being alive.

    The watch reports a whole-day metabolic rate and how far into the day its
    records reach. Adding the full figure at nine in the morning would put the
    day's total ahead of the clock — and the entire reason to show a total is
    that it should agree with the watch face.
    """

    def test_a_finished_day_is_the_whole_rate(self):
        assert roll_up_resting_calories(2497, 1440) == 2497

    def test_a_part_day_is_pro_rated(self):
        # Half past noon: half a day of simply existing.
        assert roll_up_resting_calories(2400, 720) == 1200

    def test_a_file_past_midnight_does_not_exceed_a_day(self):
        assert roll_up_resting_calories(2400, 1600) == 2400

    def test_no_rate_means_no_figure(self):
        # Not zero. A device that does not report a metabolic rate has not
        # claimed the wearer burned nothing.
        assert roll_up_resting_calories(None, 1440) is None
        assert roll_up_resting_calories(0, 1440) is None

    def test_no_elapsed_reading_assumes_a_whole_day(self):
        # Sleep and HRV files carry the rate with no monitoring records to say
        # how far the day got; a full day is the only sane reading of those.
        assert roll_up_resting_calories(2000, None) == 2000

    def test_two_minutes_past_midnight_is_not_a_whole_day(self):
        # `None` and `0` used to mean the same thing here, which is how the far
        # side of a file that crossed midnight claimed a full day's resting burn
        # at 00:02 and then won the merge with it.
        assert roll_up_resting_calories(2400, 0) is None
        assert roll_up_resting_calories(2400, 2) == 3  # three, not 2400
        assert roll_up_resting_calories(2400, 60) == 100


class TestSleepStages:
    """The night's shape, which the totals cannot reconstruct.

    ``_compute_stage_durations`` walks the same records to add up hours per
    stage and throws the ordering away. How long the first deep block ran,
    whether REM arrived in cycles, how many times the night broke — none of that
    survives three totals, and it is the whole of what a hypnogram shows.
    """

    def _levels(self):
        from datetime import datetime, timedelta

        base = datetime(2026, 8, 17, 23, 0)
        return [
            (base, "light"),
            (base + timedelta(minutes=30), "deep"),
            (base + timedelta(minutes=90), "rem"),
            (base + timedelta(minutes=110), "awake"),
            (base + timedelta(minutes=115), "light"),
            (base + timedelta(minutes=180), "light"),  # closes the last span
        ]

    def test_each_record_becomes_a_span_ending_at_the_next(self):
        from app.parsers.sleep import _stage_segments

        spans = _stage_segments(self._levels())

        assert [s["level"] for s in spans] == ["light", "deep", "rem", "awake", "light"]
        assert [s["seconds"] for s in spans] == [1800, 3600, 1200, 300, 3900]

    def test_awake_spans_are_kept(self):
        # They do not count toward the sleep total and they are exactly what a
        # night-shape chart is read for — four wakings is the finding.
        from app.parsers.sleep import _stage_segments

        assert any(s["level"] == "awake" for s in _stage_segments(self._levels()))

    def test_a_backwards_timestamp_costs_one_span_and_not_the_night(self):
        from datetime import datetime, timedelta
        from app.parsers.sleep import _stage_segments

        base = datetime(2026, 8, 17, 23, 0)
        corrupt = [
            (base, "light"),
            (base - timedelta(minutes=5), "deep"),   # goes backwards
            (base + timedelta(minutes=60), "rem"),
            (base + timedelta(minutes=90), "light"),
        ]

        spans = _stage_segments(corrupt)

        assert [s["level"] for s in spans] == ["deep", "rem"]

    def test_a_night_with_one_record_has_no_spans(self):
        from datetime import datetime
        from app.parsers.sleep import _stage_segments

        assert _stage_segments([(datetime(2026, 8, 17, 23, 0), "light")]) == []


class TestLocalDays:
    """Which day a reading belongs to.

    A monitoring file is not a day. It is a few hours of epochs whose counters
    reset at **local** midnight, and it routinely straddles one: a real file off
    a fenix 6 runs 23:44 to 02:42 UTC, which is 17:44 to 20:42 the same evening
    at UTC−6, with the step counter climbing straight through UTC midnight.

    Filing the whole file under the UTC date of its ``file_id`` therefore went
    wrong twice: it put an evening's readings on tomorrow, and because steps
    merge by taking the larger of two readings, tomorrow then inherited
    yesterday's finished count and *started* at three thousand. That is the
    "steps never reset at midnight" these tests exist to keep fixed.
    """

    UTC_MINUS_6 = timedelta(hours=-6)

    def _utc(self, *args):
        return datetime(*args, tzinfo=timezone.utc)

    def test_an_evening_in_denver_is_still_today(self):
        # 02:42 UTC on the 16th is 20:42 on the 15th where the watch is.
        assert _local_date(self._utc(2026, 7, 16, 2, 42), self.UTC_MINUS_6) == date(2026, 7, 15)

    def test_utc_is_the_fallback_and_changes_nothing(self):
        assert _local_date(self._utc(2026, 7, 16, 2, 42), timedelta(0)) == date(2026, 7, 16)

    def test_a_positive_offset_runs_the_other_way(self):
        # Tokyo: 22:00 UTC is already tomorrow morning on the wrist.
        assert _local_date(self._utc(2026, 7, 15, 22, 0), timedelta(hours=9)) == date(2026, 7, 16)

    def test_minutes_into_the_local_day(self):
        assert _minute_of_day(self._utc(2026, 7, 16, 2, 56), self.UTC_MINUS_6) == 20 * 60 + 56
        assert _minute_of_day(self._utc(2026, 7, 16, 6, 0), self.UTC_MINUS_6) == 0

    def test_no_timestamp_belongs_to_no_day(self):
        assert _local_date(None, self.UTC_MINUS_6) is None
        assert _minute_of_day(None, self.UTC_MINUS_6) is None


class TestTheMidnightRecord:
    """The watch writes one record stamped exactly at local midnight carrying
    the finished day's totals — 3,333 steps at 00:00:00 — and resets its
    counters immediately afterwards.

    Read at face value that record is the *new* day's first reading, so the new
    day begins at three thousand steps and stays there. One second is enough to
    put it back where it belongs.
    """

    UTC_MINUS_6 = timedelta(hours=-6)

    def test_a_record_on_the_boundary_closes_the_day_before(self):
        midnight = datetime(2026, 7, 16, 6, 0, tzinfo=timezone.utc)  # 00:00 at UTC−6
        moved = _closes_the_day(midnight, self.UTC_MINUS_6)
        assert _local_date(moved, self.UTC_MINUS_6) == date(2026, 7, 15)

    def test_a_second_later_is_genuinely_the_new_day(self):
        just_after = datetime(2026, 7, 16, 6, 0, 1, tzinfo=timezone.utc)
        assert _closes_the_day(just_after, self.UTC_MINUS_6) == just_after
        assert _local_date(just_after, self.UTC_MINUS_6) == date(2026, 7, 16)

    def test_the_middle_of_a_day_is_left_alone(self):
        noon = datetime(2026, 7, 15, 18, 0, tzinfo=timezone.utc)
        assert _closes_the_day(noon, self.UTC_MINUS_6) == noon

    def test_nothing_to_move(self):
        assert _closes_the_day(None, self.UTC_MINUS_6) is None


class TestSixteenBitTimestamps:
    """Most monitoring epochs carry only the low sixteen bits of their second,
    which wrap every eighteen hours. The reference is the previous record.
    """

    def test_a_later_record_resolves_forward(self):
        last = datetime(2026, 7, 16, 2, 56, tzinfo=timezone.utc)
        # Two minutes on, in Garmin seconds.
        ts16 = (int(last.timestamp()) - 631065600 + 120) & 0xFFFF
        assert _resolve_timestamp(ts16, last) == last + timedelta(minutes=2)

    def test_a_wrap_does_not_jump_a_day(self):
        # A record whose low bits have rolled past zero is minutes later, not
        # eighteen hours earlier.
        last = datetime(2026, 7, 16, 3, 9, tzinfo=timezone.utc)
        garmin = int(last.timestamp()) - 631065600
        assert garmin & 0xFFFF > 65000, "pick a `last` close to the wrap"
        ts16 = (garmin + 180) & 0xFFFF
        assert _resolve_timestamp(ts16, last) == last + timedelta(minutes=3)

    def test_nothing_to_resolve_against(self):
        assert _resolve_timestamp(None, None) is None
        last = datetime(2026, 7, 16, 2, 56, tzinfo=timezone.utc)
        assert _resolve_timestamp(None, last) == last


class TestWritingSeveralDaysFromOneFile:
    """The importer takes a list of days now, because a parser can find two."""

    def test_each_day_is_written(self, db, user):
        from app.services.fit_import import _write_daily_metrics

        _write_daily_metrics(db, {
            "type": "daily",
            "days": [
                {"date": date(2026, 7, 15), "metrics": {"steps": 3333}},
                {"date": date(2026, 7, 16), "metrics": {"steps": 22}},
            ],
        }, device_id=None, user_id=user.id)
        db.flush()

        rows = {r.date: r.steps for r in
                db.query(DailyMetric).filter_by(user_id=user.id).all()}
        assert rows == {date(2026, 7, 15): 3333, date(2026, 7, 16): 22}

    def test_the_single_day_shape_still_works(self, db, user):
        from app.services.fit_import import _write_daily_metrics

        _write_daily_metrics(db, {
            "type": "daily",
            "date": date(2026, 7, 17),
            "metrics": {"steps": 900},
        }, device_id=None, user_id=user.id)
        db.flush()

        row = (db.query(DailyMetric)
               .filter_by(user_id=user.id, date=date(2026, 7, 17)).one())
        assert row.steps == 900


class TestStressSeries:
    """The curve behind the day's average.

    A day's ``avg_stress_level`` is one number for a quantity that moves all
    day: a calm morning and a shattering afternoon average out to exactly the
    same 40 as a flat, mediocre day, and the two are not the same day. The
    watch draws the curve and the parser was throwing it away at the point of
    computing the mean.
    """

    def test_a_day_is_merged_reading_by_reading_not_slice_by_slice(self):
        # One monitoring file is a few hours, and a day is described by
        # several of them. "Longest wins" — which is right for the sleep
        # timeline, where one file is the whole night — would keep the biggest
        # slice here and throw the rest of the day away.
        morning = [[60, 30], [63, 28]]
        evening = [[1200, 71], [1203, 68], [1206, 64]]

        merged = _merge_stress_series(morning, evening)

        assert merged == [[60, 30], [63, 28], [1200, 71], [1203, 68], [1206, 64]]

    def test_re_reading_the_same_file_changes_nothing(self):
        # A file stays on the watch until its upload is confirmed, so the same
        # hours arrive again on every sync until the signal comes back.
        once = [[60, 30], [63, 28]]
        assert _merge_stress_series(once, once) == once

    def test_the_new_reading_owns_its_minute(self):
        # Which is what makes a re-parse corrective rather than additive: a
        # series recorded by a parser that filed an evening under the wrong
        # date is replaced, not merged into.
        assert _merge_stress_series([[60, 30]], [[60, 44]]) == [[60, 44]]

    def test_a_corrupt_pair_costs_itself_and_not_the_day(self):
        assert _merge_stress_series([[60, 30], ["x"], None], [[63, 28]]) == [
            [60, 30], [63, 28],
        ]

    def test_the_series_is_kept_out_of_the_column_allow_list(self):
        # It is a variable-length list and lives in `extra`; naming it here
        # would mean the importer tried to set a column that does not exist.
        assert "stress_series" not in _DAILY_METRIC_FIELDS


class TestTheNightsClock:
    """When the night ran from and to, read off the timeline already stored.

    The sleep history chart places a night by the hours it occupied rather
    than by how long it was, which is the difference between "seven hours"
    and "seven hours starting at three in the morning". Both numbers are the
    first and last elements of a list already sitting on the row, so they are
    derived rather than stored — a column could disagree with the timeline it
    came from, and would need a backfill to exist at all.
    """

    def test_the_window_is_the_whole_span_waking_included(self):
        row = DailyMetric(date=date(2026, 8, 29), user_id=1, extra={
            "sleep_stages": [
                {"start": "2026-08-29T06:16:00+00:00",
                 "end": "2026-08-29T07:10:00+00:00", "level": "light"},
                {"start": "2026-08-29T07:10:00+00:00",
                 "end": "2026-08-29T13:50:00+00:00", "level": "deep"},
            ],
        })

        assert row.sleep_start == "2026-08-29T06:16:00+00:00"
        assert row.sleep_end == "2026-08-29T13:50:00+00:00"

    def test_a_night_with_no_timeline_has_no_clock(self):
        # Every night imported before the timeline was kept. Its totals are
        # still there, and a client draws what it can.
        row = DailyMetric(date=date(2026, 8, 29), user_id=1, sleep_hours=7.0)
        assert row.sleep_start is None
        assert row.sleep_end is None

    def test_a_malformed_timeline_costs_the_clock_and_not_the_row(self):
        row = DailyMetric(date=date(2026, 8, 29), user_id=1,
                          extra={"sleep_stages": ["nonsense"]})
        assert row.sleep_start is None
        assert row.sleep_end is None


class TestTheWatchsOwnSleepSummary:
    """The night as the watch finished it, not as the sensor streamed it.

    A SLEEP file describes one night twice: a `sleep_level` record at every
    transition, and a `sleep_summary` giving the session's window and its
    per-stage minutes. They disagree, and the summary is the one the watch
    displays — on a real night off a fenix 6 the stream ran 01:19 to 09:26
    while the watch reported 01:32 to 07:01, because Garmin trims the settling
    down at the start and everything after the final waking.
    """

    def _levels(self):
        base = datetime(2026, 8, 27, 7, 19, tzinfo=timezone.utc)
        return [
            (base, "light"),                                  # 07:19
            (base + timedelta(minutes=66), "deep"),           # 08:25
            (base + timedelta(minutes=204), "light"),         # 10:43
            (base + timedelta(minutes=274), "awake"),         # 11:53
            (base + timedelta(minutes=342), "light"),         # 13:01
            (base + timedelta(minutes=427), "light"),         # 14:26
        ]

    def test_the_window_trims_both_ends(self):
        from app.parsers.sleep import _clip, _stage_segments

        start = datetime(2026, 8, 27, 7, 32, tzinfo=timezone.utc)
        end = datetime(2026, 8, 27, 13, 1, tzinfo=timezone.utc)

        spans = _stage_segments(_clip(self._levels(), start, end))

        assert spans[0]["start"] == start.isoformat()
        assert spans[-1]["end"] == end.isoformat()
        # The stage running when the window opened survives, moved forward,
        # rather than the night starting at the next transition.
        assert spans[0]["level"] == "light"

    def test_a_stage_still_running_at_the_close_is_kept(self):
        from app.parsers.sleep import _clip

        start = datetime(2026, 8, 27, 7, 19, tzinfo=timezone.utc)
        end = datetime(2026, 8, 27, 9, 0, tzinfo=timezone.utc)

        kept = _clip(self._levels(), start, end)

        # light, deep, and a closing boundary at the window's end.
        assert [level for _, level in kept] == ["light", "deep", "deep"]
        assert kept[-1][0] == end

    def test_a_window_covering_nothing_costs_the_timeline(self):
        from app.parsers.sleep import _clip

        assert _clip(
            self._levels(),
            datetime(2026, 8, 28, 1, 0, tzinfo=timezone.utc),
            datetime(2026, 8, 28, 2, 0, tzinfo=timezone.utc),
        ) == []

    def test_the_watchs_own_minutes_become_the_hours(self):
        from app.parsers.sleep import _summary_metrics

        metrics = _summary_metrics({
            "deep": 1.1, "light": 3.2, "rem": 1.0, "awake": 0.5, "score": 74,
        })

        assert metrics["sleep_deep_hours"] == 1.1
        # Deep plus light plus REM. Awake is time in bed, never sleep.
        assert metrics["sleep_hours"] == 5.3
        assert metrics["sleep_awake_hours"] == 0.5
        assert metrics["sleep_score"] == 74.0

    def test_a_stage_the_summary_did_not_report_is_left_alone(self):
        # Rather than overwriting a perfectly good figure from the stream
        # with a zero.
        from app.parsers.sleep import _summary_metrics

        metrics = _summary_metrics({"deep": 1.1, "light": None, "rem": None,
                                    "awake": None, "score": None})

        assert "sleep_light_hours" not in metrics
        assert "sleep_awake_hours" not in metrics
        assert "sleep_score" not in metrics


class TestVo2MaxIsAVo2Max:
    """The reading that was a heart rate for as long as it existed.

    Message 79 is `user_metrics` and its field 10 is `minimal_hr`. The activity
    parser read it as VO2max and reported 46-48 for a user whose resting heart
    rate is 52 — a mid-range VO2max and a plausible minimum heart rate are the
    same numbers, so nothing ever looked wrong. The tell was that the figure
    was identical on rock climbing, hiking and cycling, and Garmin computes
    VO2max for none of the first two.
    """

    def test_the_watchs_own_figure_comes_through_at_its_scale(self):
        from app.parsers.activity import ActivityParser

        # max_met_data.vo2_max, scale 10.
        assert ActivityParser._plausible_vo2max(412, 10.0) == 41.2

    def test_met_max_converts_at_three_and_a_half(self):
        from app.parsers.activity import ActivityParser

        # 11.7 MET, at scale 65536, is 41 ml/kg/min — one MET is 3.5 by
        # definition, and it is the relation Firstbeat's own model uses.
        assert ActivityParser._plausible_vo2max(11.7 * 65536, 65536.0 / 3.5) == 41.0

    def test_a_reading_outside_human_range_is_refused(self):
        from app.parsers.activity import ActivityParser

        # A raw fixed-point field read at the wrong scale, which is the shape
        # the next such mix-up will take.
        assert ActivityParser._plausible_vo2max(12341, 1.0) is None
        assert ActivityParser._plausible_vo2max(0, 10.0) is None
        assert ActivityParser._plausible_vo2max(None, 10.0) is None
