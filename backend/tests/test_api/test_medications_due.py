# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A dose's time is the account's wall clock.

``/medications/due`` built each dose as ``datetime.combine(today, time_of_day,
tzinfo=utc)``, so "08:00" was 08:00 UTC — 01:00 in California, which is when
the web's reminder fired and from when the dose showed overdue. The web's
reminders are driven entirely by this endpoint, so these pin it.

The clock is stopped rather than read: what is due depends on the time of day.
"""
from datetime import datetime, timezone

import app.api.medications as medications_api
import app.calculators.local_day as local_day
from app.models.user_settings import UserSettings

# 07:30 PDT on Wednesday 30 September 2026 — 14:30 UTC.
MORNING_IN_LA = datetime(2026, 9, 30, 14, 30, tzinfo=timezone.utc)


def _clock(monkeypatch, at):
    class _Stopped(datetime):
        @classmethod
        def now(cls, tz=None):
            return at.astimezone(tz) if tz else at.replace(tzinfo=None)

    monkeypatch.setattr(local_day, "datetime", _Stopped)
    monkeypatch.setattr(medications_api, "datetime", _Stopped)


def _in_la(db, user):
    db.query(UserSettings).filter_by(user_id=user.id).one().timezone = "America/Los_Angeles"
    db.commit()


def _med(client, time_of_day):
    r = client.post("/medications", json={
        "name": "Levothyroxine",
        "schedules": [{"time_of_day": time_of_day, "notify": True}],
    })
    assert r.status_code == 201
    return r.json()


def test_a_dose_at_eight_is_due_at_eight_where_the_user_is(client, user, db, monkeypatch):
    _in_la(db, user)
    _clock(monkeypatch, MORNING_IN_LA)
    _med(client, "08:00")
    [dose] = client.get("/medications/due").json()
    assert datetime.fromisoformat(dose["scheduled_for"]) == datetime(2026, 9, 30, 15, 0, tzinfo=timezone.utc)


def test_a_dose_not_yet_due_is_not_overdue(client, user, db, monkeypatch):
    """At 07:30 an 08:00 dose is half an hour away. Read as UTC it had been
    overdue since one in the morning."""
    _in_la(db, user)
    _clock(monkeypatch, MORNING_IN_LA)
    _med(client, "08:00")
    [dose] = client.get("/medications/due").json()
    assert dose["is_overdue"] is False


def test_a_dose_taken_this_evening_counts_for_today(client, user, db, monkeypatch):
    """Today starts at local midnight. From UTC midnight, a dose logged at
    18:00 in California (01:00 UTC tomorrow) was already the next day's."""
    _in_la(db, user)
    evening = datetime(2026, 10, 1, 1, 30, tzinfo=timezone.utc)  # 18:30 PDT on the 30th
    _clock(monkeypatch, evening)
    med = _med(client, "18:00")
    schedule = med["schedules"][0]["id"]
    [dose] = client.get("/medications/due").json()
    client.post("/medications/log", json={
        "medication_id": med["id"], "schedule_id": schedule,
        "status": "taken", "scheduled_for": dose["scheduled_for"],
    })
    [dose] = client.get("/medications/due").json()
    assert dose["status"] == "taken"
