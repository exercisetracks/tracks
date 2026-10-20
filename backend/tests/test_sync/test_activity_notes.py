# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""An activity's notes, written on one client and read on the other.

The web edits through PATCH /activities/{id} and the phone through the sync
feed; both now show the note beside a pencil on the activity. These pin that
the two routes meet — a note that stayed on the side it was written on would
look, to the user, like the other app losing it."""
from datetime import datetime, timedelta, timezone

from app.models.activity import Activity

from .helpers import Phone, latest, pull_all, push, setup_admin

PHONE = Phone("00000000000000dd")


def _activity(db, user) -> Activity:
    a = Activity(
        user_id=user.id, device_id=1, sport="running",
        started_at=datetime.now(timezone.utc) - timedelta(days=1), duration_seconds=1800,
    )
    db.add(a)
    db.commit()
    db.refresh(a)
    return a


class TestActivityNotes:
    def test_a_note_written_on_the_web_reaches_the_phone(self, client, db, user):
        """The web's PATCH must stamp the field, or the phone's pull never carries it."""
        h = setup_admin(client)
        a = _activity(db, user)
        resp = client.patch(f"/activities/{a.id}", json={"notes": "Windy on the ridge"}, headers=h)
        assert resp.status_code == 200, resp.text
        changes, _ = pull_all(client, h)
        assert latest(changes, "activity", a.uid)["fields"]["notes"][0] == "Windy on the ridge"

    def test_a_note_written_on_the_phone_is_on_the_web(self, client, db, user):
        h = setup_admin(client)
        a = _activity(db, user)
        res = push(client, h, PHONE.change("activity", uid=a.uid, notes="Legs heavy"))
        assert res["results"][0]["status"] == "applied"
        assert client.get(f"/activities/{a.id}", headers=h).json()["notes"] == "Legs heavy"

    def test_a_note_cleared_on_the_web_is_cleared_on_the_phone(self, client, db, user):
        """The web clears with an empty string — the API reads null as "unchanged"."""
        h = setup_admin(client)
        a = _activity(db, user)
        push(client, h, PHONE.change("activity", uid=a.uid, notes="Legs heavy"))
        client.patch(f"/activities/{a.id}", json={"notes": ""}, headers=h)
        changes, _ = pull_all(client, h)
        assert not latest(changes, "activity", a.uid)["fields"]["notes"][0]
