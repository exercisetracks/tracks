"""Schedule.fit must keep the identity Garmin Connect writes.

Every constant here was read off a decoded Garmin Connect upload captured over
BLE — the one file the watch was observed to accept and act on. They are not
derived from anything, so nothing in the code can re-derive them if they drift;
this test is the only thing holding them in place. See AGENTS.md.
"""
from datetime import date

from app.calculators.fit_workout import generate_schedule_fit

from tests.fit_reader import read_messages


def _messages(raw: bytes):
    return read_messages(raw)


def test_file_id_is_connects_identity_not_the_watchs():
    raw = generate_schedule_fit([
        {"scheduled_date": date(2026, 8, 26), "workout_id": 42,
         "time_created": 1756160000000},
    ])
    file_id = next(m for m in _messages(raw) if m["mesg_num"] == 0)

    assert file_id[0] == 7            # type = schedules
    assert file_id[1] == 1            # manufacturer = garmin
    assert file_id[2] == 65534        # product = connect sentinel, NOT the watch
    assert file_id[3] == 1            # serial_number = 1, NOT the watch's serial
    assert file_id[5] == 1            # number
    assert 4 in file_id               # time_created present


def test_schedule_entry_points_back_at_its_workout():
    """schedule.serial_number + schedule.time_created are the foreign key into
    the workout file's own file_id. Connect's capture matched them exactly."""
    raw = generate_schedule_fit([
        {"scheduled_date": date(2026, 8, 26), "workout_id": 42,
         "time_created": 1756160000000},
    ])
    sched = next(m for m in _messages(raw) if m["mesg_num"] == 28)

    assert sched[0] == 1              # manufacturer = garmin
    assert sched[1] == 65534          # product = connect
    assert sched[2] == 42             # serial_number = workout id
    assert sched[5] == 0              # type = workout
    assert sched[4] == 0              # completed = false
    # scheduled_time is noon UTC on the target day
    assert sched[6] == 1156680000


def test_empty_schedule_is_empty_bytes():
    assert generate_schedule_fit([]) == b""


def test_training_plan_message_is_present():
    """Message 137 is undocumented and the FIT SDK cannot write it, which is why
    this file is hand-encoded. Connect sends it; a schedule without it was the
    last structural difference left when the calendar still would not fill in."""
    raw = generate_schedule_fit([
        {"scheduled_date": date(2026, 8, 26), "workout_id": 42,
         "time_created": 1756160000000},
    ], plan_name="10K")
    plan = next(m for m in _messages(raw) if m["mesg_num"] == 137)

    assert plan[254] == 1                     # message_index
    assert plan[0].rstrip(b"\x00") == b"10K"  # name
    assert plan[4] == 1 and plan[5] == 0 and plan[6] == 1

    sched = next(m for m in _messages(raw) if m["mesg_num"] == 28)
    assert sched[7] == plan[254]              # entry points back at the plan
    assert 10 in sched


def test_envelope_matches_connects():
    """Protocol 1.0, profile 21213, big-endian records. The SDK writes 2.0 and
    little-endian; the watch only ever accepted the former from Connect."""
    raw = generate_schedule_fit([
        {"scheduled_date": date(2026, 8, 26), "workout_id": 42,
         "time_created": 1756160000000},
    ])
    assert raw[0] == 14
    assert raw[1] == 0x10
    assert int.from_bytes(raw[2:4], "little") == 21213
    assert raw[8:12] == b".FIT"
    assert raw[raw[0] + 2] == 1               # first definition is big-endian
