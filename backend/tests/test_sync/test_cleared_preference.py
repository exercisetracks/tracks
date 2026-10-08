# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A phone clears an exercise or stretch preference by syncing it as null.

The server's columns were NOT NULL, so the push failed on the constraint — and
a push commits whole or not at all, so one cleared preference held back every
other edit that phone made after it, on every retry.
"""
import pytest

from app.models.flexibility import UserFlexibilityPreference
from app.models.strength import UserExercisePreference

from app.sync.uids import natural_uid

from .helpers import Phone, pull_all, push, setup_admin


def _all_applied(result):
    assert all(r["status"] != "rejected" for r in result["results"]), result


@pytest.mark.parametrize("entity, model", [
    ("exercise_preference", UserExercisePreference),
    ("stretch_preference", UserFlexibilityPreference),
])
def test_a_cleared_preference_syncs_and_does_not_hold_back_the_rest(client, db, entity, model):
    headers = setup_admin(client)
    phone = Phone("a" * 16)
    uid = natural_uid("exercise_pref:{exercise_name}" if entity == "exercise_preference"
                      else "stretch_pref:{exercise_name}", exercise_name="Squat")
    pref = phone.change(entity, uid=uid, exercise_name="Squat", preference="excluded")
    _all_applied(push(client, headers, pref))

    cleared = phone.change(entity, uid=pref["uid"], preference=None)
    goal = phone.change("goal", name="Spring marathon")
    result = push(client, headers, cleared, goal)

    _all_applied(result)
    db.expire_all()
    assert db.query(model).filter_by(exercise_name="Squat").one().preference is None


def test_a_cleared_stretch_reads_as_neutral(client, db):
    headers = setup_admin(client)
    phone = Phone("b" * 16)
    pref = phone.change("stretch_preference", exercise_name="Pigeon Pose", preference="preferred",
                        uid=natural_uid("stretch_pref:{exercise_name}", exercise_name="Pigeon Pose"))
    push(client, headers, pref)
    push(client, headers, phone.change("stretch_preference", uid=pref["uid"], preference=None))

    from app.api.flexibility.library import _attach_preferences
    from app.models.activity import User
    items = [{"name": "Pigeon Pose"}]
    _attach_preferences(db, db.query(User).first().id, items)
    assert items[0]["preference"] == "neutral"


_WEB = {
    "exercise_preference": ("/strength/preferences/Squat", "exercise_pref:{exercise_name}"),
    "stretch_preference": ("/flexibility/preferences/Squat", "stretch_pref:{exercise_name}"),
}


def _pulled(client, headers, uid):
    changes, _ = pull_all(client, headers)
    return [c for c in changes if c["uid"] == uid][-1]


@pytest.mark.parametrize("entity", list(_WEB))
def test_the_web_and_the_phone_each_change_what_the_other_set(client, db, entity):
    """Clearing on the web used to delete the row. Its uid is the exercise's
    name, and a delete is a tombstone that outranks every later edit — so the
    phone's next change to that exercise came back "stale", forever."""
    headers = setup_admin(client)
    path, key = _WEB[entity]
    uid = natural_uid(key, exercise_name="Squat")
    phone = Phone("d" * 16)

    _all_applied(push(client, headers, phone.change(
        entity, uid=uid, exercise_name="Squat", preference="excluded")))

    # The web overrides the phone's choice, and the phone sees it.
    assert client.put(path, headers=headers, json={"preference": "preferred"}).status_code == 200
    assert _pulled(client, headers, uid)["fields"]["preference"][0] == "preferred"

    # The web clears it: the phone sees a neutral row, not a deletion.
    assert client.delete(path, headers=headers).status_code in (200, 204)
    cleared = _pulled(client, headers, uid)
    assert cleared["deleted"] is None
    assert cleared["fields"]["preference"][0] is None

    # And the phone can still set it afterwards.
    _all_applied(push(client, headers, phone.change(entity, uid=uid, preference="excluded")))
    assert _pulled(client, headers, uid)["fields"]["preference"][0] == "excluded"


def test_setting_a_stretch_to_neutral_on_the_web_clears_rather_than_deletes(client, db):
    headers = setup_admin(client)
    uid = natural_uid("stretch_pref:{exercise_name}", exercise_name="Squat")
    client.put("/flexibility/preferences/Squat", headers=headers, json={"preference": "excluded"})
    client.put("/flexibility/preferences/Squat", headers=headers, json={"preference": "neutral"})
    assert _pulled(client, headers, uid)["deleted"] is None
