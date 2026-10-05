# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""/sync/push and /sync/pull against the real database.

The corpus (test_scenarios.py) pins the merge rules as pure functions; these
pin that the Postgres path makes the same decisions, and the things only a
database has: sequence numbers, tombstones, staged rows, foreign keys, and
more than one account.
"""
import re

from app.models.flexibility import FlowStretch, UserFlexibilityFlow
from app.models.sync import SyncTombstone
from app.models.waypoint import Waypoint

from .helpers import Phone, latest, pull_all, push, second_account, setup_admin

HLC = re.compile(r"^\d{16}-[0-9a-f]{4}-[0-9a-f]{16}$")
A = Phone("000000000000000a")
B = Phone("000000000000000b")


class TestAuth:
    def test_push_needs_an_open_vault(self, client, user):
        """Rows carry encrypted columns; a push with the vault shut would have
        to either drop them or fail half-way."""
        resp = client.post("/sync/push", json={"changes": []})
        assert resp.status_code == 401

    def test_pull_needs_an_open_vault(self, client, user):
        assert client.get("/sync/pull").status_code == 401


class TestPushAndPull:
    def test_a_pushed_row_is_created_and_pulled_back_with_its_stamps(self, client, user, db):
        h = setup_admin(client)
        c = A.change("waypoint", name="Camp 2", lat=46.5, lng=7.9)
        res = push(client, h, c)
        assert res["results"][0]["status"] == "applied"
        assert HLC.match(res["clock"])

        row = db.query(Waypoint).filter_by(uid=c["uid"]).one()
        assert (row.name, row.lat, row.lng) == ("Camp 2", 46.5, 7.9)

        changes, page = pull_all(client, h)
        got = latest(changes, "waypoint", c["uid"])
        assert got["fields"]["name"] == c["fields"]["name"]
        assert got["deleted"] is None

    def test_an_older_edit_loses_and_says_so(self, client, user, db):
        """An edit queued on a plane must not clobber one made since."""
        h = setup_admin(client)
        uid = A.change("waypoint")["uid"]
        old = A.stamp()
        push(client, h, {"entity": "waypoint", "uid": uid,
                         "fields": {"name": ["New", A.stamp()], "lat": [1.0, A.stamp()],
                                    "lng": [2.0, A.stamp()]}})
        res = push(client, h, {"entity": "waypoint", "uid": uid, "fields": {"name": ["Old", old]}})
        assert res["results"][0] == {"entity": "waypoint", "uid": uid, "status": "stale",
                                     "refused": {"name": "stale"}}
        assert db.query(Waypoint).filter_by(uid=uid).one().name == "New"

    def test_replaying_a_push_changes_nothing(self, client, user, db):
        """A push retried after a dropped connection must be harmless."""
        h = setup_admin(client)
        c = A.change("waypoint", name="X", lat=1.0, lng=2.0)
        push(client, h, c)
        _, page = pull_all(client, h)
        res = push(client, h, c)
        assert res["results"][0]["status"] == "stale"
        again, _ = pull_all(client, h, since=page["next"])
        assert again == []

    def test_a_partial_row_waits_until_it_is_complete(self, client, user, db):
        """A waypoint needs lat and lng. Arriving without them it is staged,
        not refused — the rest may be in the next push — and still syncs to
        other phones in the meantime."""
        h = setup_admin(client)
        c = A.change("waypoint", name="Half")
        assert push(client, h, c)["results"][0]["status"] == "applied"
        assert db.query(Waypoint).filter_by(uid=c["uid"]).first() is None
        changes, _ = pull_all(client, h)
        assert latest(changes, "waypoint", c["uid"])["fields"]["name"][0] == "Half"

        push(client, h, A.change("waypoint", uid=c["uid"], lat=1.0, lng=2.0))
        row = db.query(Waypoint).filter_by(uid=c["uid"]).one()
        assert (row.name, row.lat) == ("Half", 1.0)

    def test_a_malformed_value_is_rejected_without_failing_the_batch(self, client, user):
        """One bad row must not wedge a phone's whole outbox."""
        h = setup_admin(client)
        bad = A.change("waypoint", name="Bad", lat="north", lng=2.0)
        good = A.change("waypoint", name="Good", lat=1.0, lng=2.0)
        res = push(client, h, bad, good)
        assert res["results"][0]["status"] == "rejected"
        assert res["results"][0]["reason"] == "invalid"
        assert res["results"][1]["status"] == "applied"

    def test_rejection_reasons_are_the_contracts(self, client, user):
        h = setup_admin(client)
        far = f"{9_999_999_999_999:016d}-0000-000000000000000a"
        res = push(client, h,
                   {"entity": "nonsense", "uid": "u", "fields": {}},
                   A.change("coaching_note", ai_response="hi"),
                   {"entity": "waypoint", "uid": "u2", "fields": {"name": ["x", far]}})
        assert [r["reason"] for r in res["results"]] == ["unknown_entity", "readonly", "clock_skew"]

    def test_a_field_outside_the_contract_is_refused(self, client, user, db):
        """A newer client must not be able to write columns the server does not sync."""
        h = setup_admin(client)
        c = A.change("waypoint", name="P", lat=1.0, lng=2.0, watch_filename="x.fit")
        c["fields"]["user_id"] = [999, A.stamp()]
        res = push(client, h, c)
        assert res["results"][0]["refused"] == {"user_id": "unknown"}
        assert db.query(Waypoint).filter_by(uid=c["uid"]).one().user_id != 999

    def test_a_natural_key_row_must_carry_its_own_uid(self, client, user):
        """Two phones creating the same day's weight offline must land on one
        row; a phone inventing its own uid for it would fork the day."""
        h = setup_admin(client)
        res = push(client, h, A.change("daily_entry", uid="not-the-right-one",
                                       date="2026-09-24", weight_kg=70.0))
        assert res["results"][0]["reason"] == "invalid"


class TestDeletes:
    def test_a_pushed_delete_removes_the_row_and_pulls_as_a_tombstone(self, client, user, db):
        h = setup_admin(client)
        c = A.change("waypoint", name="Gone", lat=1.0, lng=2.0)
        push(client, h, c)
        d = {"entity": "waypoint", "uid": c["uid"], "deleted": A.stamp()}
        assert push(client, h, d)["results"][0]["status"] == "applied"
        assert db.query(Waypoint).filter_by(uid=c["uid"]).first() is None
        changes, _ = pull_all(client, h)
        assert latest(changes, "waypoint", c["uid"]) == {
            "entity": "waypoint", "uid": c["uid"], "fields": {}, "deleted": d["deleted"]}

    def test_an_edit_after_a_delete_does_not_resurrect(self, client, user, db):
        """Delete wins: someone who removed a thing does not expect it back."""
        h = setup_admin(client)
        c = A.change("waypoint", name="Gone", lat=1.0, lng=2.0)
        push(client, h, c)
        push(client, h, {"entity": "waypoint", "uid": c["uid"], "deleted": A.stamp()})
        res = push(client, h, B.change("waypoint", uid=c["uid"], name="Back?"))
        assert res["results"][0]["refused"] == {"name": "deleted"}
        assert db.query(Waypoint).filter_by(uid=c["uid"]).first() is None

    def test_deleting_a_flow_through_the_web_tombstones_its_stretches(self, client, user, db):
        """The database cascades the children invisibly to the ORM; without
        tombstones for them a phone would keep a deleted flow's stretches."""
        h = setup_admin(client)
        f = client.post("/flexibility/flows", headers=h, json={
            "name": "Hips", "stretches": [{"exercise_name": "Pigeon"}, {"exercise_name": "Lizard"}]})
        assert f.status_code in (200, 201), f.text
        stretch_uids = {s.uid for s in db.query(FlowStretch).all()}
        assert len(stretch_uids) == 2
        client.delete(f"/flexibility/flows/{f.json()['id']}", headers=h)
        tombs = {t.uid for t in db.query(SyncTombstone).filter_by(entity="flow_stretch")}
        assert tombs == stretch_uids

    def test_a_child_pushed_under_a_deleted_parent_is_deleted_on_arrival(self, client, user, db):
        """Arrival order is free: a stretch added on one phone must not outlive
        the flow another phone deleted."""
        h = setup_admin(client)
        flow = A.change("flow", name="Hips")
        push(client, h, flow)
        gone = A.stamp()
        push(client, h, {"entity": "flow", "uid": flow["uid"], "deleted": gone})
        child = B.change("flow_stretch", flow_uid=flow["uid"], exercise_name="Pigeon", order="V")
        push(client, h, child)
        assert db.query(FlowStretch).filter_by(uid=child["uid"]).first() is None
        t = db.query(SyncTombstone).filter_by(entity="flow_stretch", uid=child["uid"]).one()
        assert t.deleted == gone

    def test_a_child_cannot_move_to_another_parent(self, client, user, db):
        """A move racing a delete of the old parent cannot converge."""
        h = setup_admin(client)
        f1, f2 = A.change("flow", name="One"), A.change("flow", name="Two")
        push(client, h, f1, f2)
        s = A.change("flow_stretch", flow_uid=f1["uid"], exercise_name="Pigeon", order="V")
        push(client, h, s)
        res = push(client, h, A.change("flow_stretch", uid=s["uid"], flow_uid=f2["uid"]))
        assert res["results"][0]["refused"] == {"flow_uid": "immutable"}

    def test_a_log_entry_cannot_be_edited_but_can_be_retracted(self, client, user, db):
        """A dose taken is a fact; changing it is a retraction and a new entry."""
        h = setup_admin(client)
        med = A.change("medication", name="Ibuprofen")
        push(client, h, med)
        log = A.change("medication_log", medication_uid=med["uid"], status="taken",
                       logged_at="2026-09-24T08:00:00Z")
        push(client, h, log)
        res = push(client, h, A.change("medication_log", uid=log["uid"], status="skipped"))
        assert res["results"][0]["refused"] == {"status": "immutable"}
        replay = push(client, h, A.change("medication_log", uid=log["uid"], status="taken"))
        assert replay["results"][0]["refused"] == {"status": "stale"}
        assert push(client, h, {"entity": "medication_log", "uid": log["uid"],
                                "deleted": A.stamp()})["results"][0]["status"] == "applied"


class TestReferences:
    def test_a_child_arriving_before_its_parent_is_created_when_the_parent_lands(self, client, user, db):
        """Two phones deliver in either order; neither may be refused for it."""
        h = setup_admin(client)
        flow_uid = A.change("flow")["uid"]
        child = A.change("flow_stretch", flow_uid=flow_uid, exercise_name="Pigeon", order="V")
        assert push(client, h, child)["results"][0]["status"] == "applied"
        assert db.query(FlowStretch).filter_by(uid=child["uid"]).first() is None

        push(client, h, A.change("flow", uid=flow_uid, name="Hips"))
        stretch = db.query(FlowStretch).filter_by(uid=child["uid"]).one()
        flow = db.query(UserFlexibilityFlow).filter_by(uid=flow_uid).one()
        assert stretch.flow_id == flow.id
        assert stretch.order_index == "V"

    def test_a_nullable_reference_to_a_missing_row_is_kept_and_resolved_later(self, client, user, db):
        """A track's folder may arrive after the track."""
        from app.models.custom_track import CustomTrack, CustomTrackFolder
        h = setup_admin(client)
        folder_uid = A.change("track_folder")["uid"]
        track = A.change("track", name="Loop", folder_uid=folder_uid, geometry={"type": "LineString",
                                                                               "coordinates": []})
        push(client, h, track)
        row = db.query(CustomTrack).filter_by(uid=track["uid"]).one()
        assert row.folder_id is None
        changes, _ = pull_all(client, h)
        assert latest(changes, "track", track["uid"])["fields"]["folder_uid"][0] == folder_uid

        push(client, h, A.change("track_folder", uid=folder_uid, name="Alps"))
        db.refresh(row)
        assert row.folder_id == db.query(CustomTrackFolder).filter_by(uid=folder_uid).one().id
        assert row.pending_refs is None


class TestWebEditsSync:
    def test_a_web_edit_is_stamped_and_pulled(self, client, user, db):
        """The web app knows nothing about clocks; its edits must still reach phones."""
        h = setup_admin(client)
        c = A.change("waypoint", name="Camp", lat=1.0, lng=2.0)
        push(client, h, c)
        _, page = pull_all(client, h)
        wid = db.query(Waypoint).filter_by(uid=c["uid"]).one().id
        resp = client.patch(f"/maps/waypoints/{wid}", headers=h, json={"name": "Camp 3"})
        assert resp.status_code == 200, resp.text
        changes, _ = pull_all(client, h, since=page["next"])
        got = latest(changes, "waypoint", c["uid"])
        assert got["fields"]["name"][0] == "Camp 3"
        assert got["fields"]["name"][1] > c["fields"]["name"][1]

    def test_a_web_delete_is_pulled_as_a_tombstone(self, client, user, db):
        h = setup_admin(client)
        c = A.change("waypoint", name="Camp", lat=1.0, lng=2.0)
        push(client, h, c)
        wid = db.query(Waypoint).filter_by(uid=c["uid"]).one().id
        client.delete(f"/maps/waypoints/{wid}", headers=h)
        changes, _ = pull_all(client, h)
        assert latest(changes, "waypoint", c["uid"])["deleted"] is not None

    def test_the_cursor_never_passes_a_row_stamped_but_not_yet_committed(self, client, user, db):
        """Sequence numbers are taken at commit. A row stamped in an open
        transaction is pending and invisible to pull, and gets a number above
        anything already pulled once it commits."""
        from app.models.sync import SEQ_PENDING
        h = setup_admin(client)
        push(client, h, A.change("waypoint", name="One", lat=1.0, lng=1.0))
        w = Waypoint(user_id=user.id, name="Uncommitted", lat=2.0, lng=2.0)
        db.add(w)
        db.flush()
        assert w.server_seq == SEQ_PENDING
        changes, page = pull_all(client, h)
        assert all(c["uid"] != w.uid for c in changes)
        db.commit()
        later, _ = pull_all(client, h, since=page["next"])
        assert [c["uid"] for c in later] == [w.uid]


class TestPlans:
    def test_regenerating_a_plan_keeps_its_uid_and_starts_a_new_generation(
            self, client, user, db, monkeypatch):
        """The plan's uid is derived from its goal; deleting and recreating it
        would tombstone the only uid it can have, and a delete wins."""
        # Creating an event goal refreshes plans on a thread, which would share
        # this test's connection and race it (and outlive it).
        from app.api import training_plan as training_plan_api
        monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)
        from app.models.training_plan import PlannedWorkout, TrainingPlan
        from datetime import date, timedelta
        h = setup_admin(client)
        goal = client.post("/coaching/goals", headers=h, json={
            "goal_type": "event", "event_name": "10K", "event_sport": "running",
            "event_date": (date.today() + timedelta(days=70)).isoformat(),
            "event_distance_meters": 10000,
        })
        assert goal.status_code == 201, goal.text
        gid = goal.json()["id"]
        assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200
        plan = db.query(TrainingPlan).filter_by(goal_id=gid).one()
        uid, gen = plan.uid, plan.generation
        first = {w.uid for w in db.query(PlannedWorkout).filter_by(plan_id=plan.id)}

        assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200
        db.refresh(plan)
        assert plan.uid == uid and plan.generation != gen
        assert all(w.generation == plan.generation
                   for w in db.query(PlannedWorkout).filter_by(plan_id=plan.id))
        tombs = {t.uid for t in db.query(SyncTombstone).filter_by(entity="planned_workout")}
        assert first <= tombs

    def test_a_phones_stale_generation_is_reaped_after_a_push(self, client, user, db):
        """Two phones regenerating offline converge on one set of workouts."""
        from app.models.training_plan import PlannedWorkout
        h = setup_admin(client)
        goal = A.change("goal", goal_type="event", is_active=True)
        push(client, h, goal)
        from app.sync.uids import natural_uid
        plan_uid = natural_uid("plan:{goal_uid}", goal_uid=goal["uid"])
        push(client, h, A.change("plan", uid=plan_uid, goal_uid=goal["uid"], generation="g1"))
        old = A.change("planned_workout", plan_uid=plan_uid, generation="g1",
                       scheduled_date="2026-10-01", sport="running", workout_type="easy", title="Old")
        push(client, h, old)
        push(client, h, B.change("plan", uid=plan_uid, generation="g2"))
        assert db.query(PlannedWorkout).filter_by(uid=old["uid"]).first() is None
        assert db.query(SyncTombstone).filter_by(entity="planned_workout", uid=old["uid"]).one()


class TestAccounts:
    def test_two_accounts_never_see_each_others_rows(self, client, user, db):
        """Uids are unique per account, not globally — natural keys collide on
        purpose — so every lookup must be scoped, or one person's push edits
        another person's row."""
        admin = setup_admin(client)
        partner = second_account(client, admin)
        day = {"date": "2026-09-24"}
        from app.sync.uids import natural_uid
        uid = natural_uid("daily:{date}", **day)
        push(client, admin, A.change("daily_entry", uid=uid, weight_kg=80.0, **day))
        push(client, partner, B.change("daily_entry", uid=uid, weight_kg=55.0, **day))
        w = A.change("waypoint", name="Mine", lat=1.0, lng=2.0)
        push(client, admin, w)
        push(client, partner, {"entity": "waypoint", "uid": w["uid"], "deleted": B.stamp()})

        mine, _ = pull_all(client, admin)
        theirs, _ = pull_all(client, partner)
        assert latest(mine, "daily_entry", uid)["fields"]["weight_kg"][0] == 80.0
        assert latest(theirs, "daily_entry", uid)["fields"]["weight_kg"][0] == 55.0
        assert latest(mine, "waypoint", w["uid"])["deleted"] is None
        assert latest(theirs, "waypoint", w["uid"])["fields"] == {}
        assert db.query(Waypoint).filter_by(uid=w["uid"]).count() == 1

    def test_the_account_uid_is_stable_and_exposed(self, client, user):
        """A phone records which account its data belongs to by this uid."""
        h = setup_admin(client)
        first = client.get("/users/me", headers=h).json()["uid"]
        assert first == client.get("/users/me", headers=h).json()["uid"]


class TestServerAndEpoch:
    def test_every_pull_says_which_server_and_epoch(self, client, user):
        h = setup_admin(client)
        _, page = pull_all(client, h)
        assert re.match(r"^[0-9a-f]{16}$", page["server_id"])
        assert page["epoch"] == 0
        assert "full_resync_required" not in page

    def test_deleting_my_data_bumps_the_epoch_and_refuses_older_pushes(self, client, user):
        """A phone still holding the data would quietly undo the deletion."""
        h = setup_admin(client)
        push(client, h, A.change("waypoint", name="X", lat=1.0, lng=2.0), epoch=0)
        assert client.delete("/users/me/data", headers=h).status_code == 204
        _, page = pull_all(client, h)
        assert page["epoch"] == 1
        res = push(client, h, A.change("waypoint", name="Y", lat=1.0, lng=2.0), epoch=0)
        assert res["results"][0]["reason"] == "wiped"
        res = push(client, h, A.change("waypoint", name="Z", lat=1.0, lng=2.0), epoch=1)
        assert res["results"][0]["status"] == "applied"


class TestTheme:
    def test_the_colour_scheme_is_an_account_setting_that_syncs(self, client, user):
        """The phone and the web must look the same; a scheme kept per browser
        never reached the phone."""
        h = setup_admin(client)
        resp = client.patch("/users/me/settings", headers=h, json={"theme_mode": "dark"})
        assert resp.status_code == 200, resp.text
        assert resp.json()["theme_mode"] == "dark"
        changes, _ = pull_all(client, h)
        settings = [c for c in changes if c["entity"] == "settings"][-1]
        assert settings["fields"]["theme_mode"][0] == "dark"
        assert client.patch("/users/me/settings", headers=h,
                            json={"theme_mode": "sepia"}).status_code == 422
