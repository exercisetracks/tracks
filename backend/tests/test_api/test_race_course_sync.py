# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A race's course goes to the watch beside its race day, for the watch's map.

A Garmin workout has no field for a course, so the course goes to the watch's
course list for the week its race day is on the calendar. This is the cable
and browser half (courses_api/sync.py ``_race_course_items``); the phone
sends the same over Explore sync (LocalRacePlans.raceCourses)."""
import base64
from datetime import date, timedelta

from garmin_fit_sdk import Decoder, Stream

from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.custom_track import CustomTrack
from app.routes.courses_api.sync import _course_upload_items

PATH = [[46.5 + i * 0.001, 7.4 + i * 0.0012, 500.0 + (i % 7) * 3.0] for i in range(40)]


def _race(db, user_id, days, path=PATH, name="Bern 10K", sport="running", legs=None):
    g = TrainingGoal(user_id=user_id, goal_type="event", event_name=name, event_sport=sport,
                     event_distance_meters=10_000, event_date=date.today() + timedelta(days=days))
    db.add(g)
    db.flush()
    db.add(RacePlan(goal_id=g.id, user_id=user_id, course_path=path, tri_leg_courses=legs))
    db.commit()
    return g


def _races(db, user_id):
    return [i for i in _course_upload_items(db, user_id) if i["id"] < 0]


def _course(item) -> dict:
    msgs, errors = Decoder(Stream.from_byte_array(bytearray(base64.b64decode(item["fit_b64"])))).read()
    assert not errors, errors
    return msgs


def test_a_race_this_week_offers_its_course_under_its_name(client, user, db):
    goal = _race(db, user.id, 3)
    (item,) = _races(db, user.id)
    assert item["id"] == -(goal.id * 4)
    assert item["filename"] == f"RACE{goal.id * 4}.fit"
    msgs = _course(item)
    assert msgs["course_mesgs"][0]["name"] == "Bern 10K"
    assert len(msgs["record_mesgs"]) == len(PATH)
    assert msgs["record_mesgs"][0]["position_lat"] != 0


def test_the_browser_writes_it_among_the_courses(client, user, db):
    _race(db, user.id, 0)
    items = client.get("/device-sync/upload-list").json()["items"]
    race = [i for i in items if i["id"] < 0]
    assert [i["folder"] for i in race] == ["GARMIN/Courses"]


def test_a_race_past_or_beyond_the_calendar_offers_no_course(client, user, db):
    _race(db, user.id, -1)
    _race(db, user.id, 5)
    _race(db, user.id, 3, path=PATH[:1])
    assert _races(db, user.id) == []


def test_a_triathlon_with_no_whole_course_sends_its_leg_files(client, user, db):
    goal = _race(db, user.id, 2, path=None, name="Lake Tri", sport="triathlon",
                 legs={"bike": {"segments": [], "path": PATH}, "run": {"segments": [], "path": PATH[:10]}})
    items = _races(db, user.id)
    assert [i["id"] for i in items] == [-(goal.id * 4 + 2), -(goal.id * 4 + 3)]
    assert [_course(i)["course_mesgs"][0]["name"] for i in items] == ["Lake Tri · Bike", "Lake Tri · Run"]


def test_marking_a_race_course_sent_touches_no_track(client, user, db):
    """Its id is negative, so the course marks — a track's id, checked
    against its owner — can never take it for one of the user's tracks."""
    goal = _race(db, user.id, 1)
    track = CustomTrack(id=goal.id * 4, user_id=user.id, name="A track", geometry=[[7.4, 46.5], [7.41, 46.51]])
    db.add(track)
    db.commit()
    (item,) = _races(db, user.id)
    r = client.post("/device-sync/mark-uploaded", json={"items": [{"type": "course", "id": item["id"],
                                                                     "filename": item["filename"]}]})
    assert r.status_code == 200
    db.refresh(track)
    assert track.watch_uploaded_at is None


def test_another_accounts_race_is_not_offered(client, user, db):
    other = User(name="Other")
    db.add(other)
    db.flush()
    _race(db, other.id, 2)
    assert _races(db, user.id) == []
