# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import base64
from datetime import date, datetime, timedelta, timezone
from unittest.mock import patch

import httpx

from app.models.coaching import RacePlan, TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan
from app.models.user_settings import UserSettings


class TestDeviceSyncEndpoints:
    def test_upload_list_empty(self, client, user):
        resp = client.get("/device-sync/upload-list")
        assert resp.status_code == 200
        assert resp.json() == {"items": []}

    def test_delete_list_empty(self, client, user):
        resp = client.get("/device-sync/delete-list")
        assert resp.status_code == 200
        assert resp.json() == {"items": []}

    def test_schedule_fit_empty(self, client, user):
        resp = client.get("/device-sync/schedule-fit")
        assert resp.status_code == 200
        data = resp.json()
        assert data["count"] == 0
        assert data["filename"] == "SCHEDULE.fit"
        assert data["folder"] == "GARMIN/NewFiles"
        # With no uploaded workouts the payload may be empty; clients gate on count.
        assert isinstance(data["fit_b64"], str)

    def test_schedule_bundle_workouts_carry_every_key_the_phone_requires(
        self, client, user, db
    ):
        """The phone's PushItem has no default for `folder`, so a bundle entry
        that omits it does not arrive folder-less — the whole response fails to
        decode and the schedule is never pushed at all.

        That is not hypothetical. Between 2026-08-25 21:19 and the fix, every
        BLE sync threw on `$.workouts[0]`, logged one line that read like a
        network error, and sent nothing; the empty training calendar it left
        behind was read for a fortnight as a file-format problem. Asserting the
        required keys rather than the whole shape, because the failure mode is a
        missing key and extra keys are ignored by the client.
        """
        goal = TrainingGoal(user_id=user.id, goal_type="event",
                            event_name="Race", event_sport="running",
                            event_date=date(2026, 10, 1))
        db.add(goal)
        db.flush()
        plan = TrainingPlan(goal_id=goal.id, user_id=user.id, sport="running")
        db.add(plan)
        db.flush()
        db.add(PlannedWorkout(
            plan_id=plan.id, user_id=user.id,
            scheduled_date=date.today() + timedelta(days=1),
            sport="running", workout_type="tempo", title="Tempo",
        ))
        db.commit()

        data = client.get("/device-sync/schedule-bundle").json()
        assert data["workouts"], "expected the scheduled workout in the bundle"

        # Exactly the fields com.tracks.core.api.PushItem declares without a
        # default. Keep this list in step with that class.
        required = {"type", "filename", "folder"}
        for entry in data["workouts"]:
            assert required <= set(entry), \
                f"bundle entry is missing {sorted(required - set(entry))}"
        assert data["folder"] == "GARMIN/NewFiles"

    def test_mark_uploaded_tolerates_unknown_ids(self, client, user):
        resp = client.post("/device-sync/mark-uploaded", json={
            "items": [
                {"type": "workout", "id": 999999, "filename": "W1.fit"},
                {"type": "course", "id": 999999, "filename": "TRK_9.fit"},
            ]
        })
        assert resp.status_code == 200

    def test_mark_deleted_tolerates_unknown_ids(self, client, user):
        resp = client.post("/device-sync/mark-deleted", json={
            "items": [
                {"type": "workout", "id": 999999},
                {"type": "pending", "id": 999999},
                {"type": "course", "id": 999999},
            ]
        })
        assert resp.status_code == 200


class TestBrowserAgps:
    def _settings(self, db, user):
        return db.query(UserSettings).filter_by(user_id=user.id).first()

    def test_not_due_when_disabled(self, client, user):
        resp = client.get("/device-sync/agps")
        assert resp.status_code == 200
        assert resp.json() == {"due": False}

    def test_not_due_when_fresh(self, client, user, db):
        us = self._settings(db, user)
        us.agps_enabled = True
        us.agps_source = "garmin"
        us.agps_last_synced_at = datetime.now(timezone.utc)
        db.commit()
        resp = client.get("/device-sync/agps")
        assert resp.json() == {"due": False}

    def test_due_downloads_and_encodes(self, client, user, db):
        us = self._settings(db, user)
        us.agps_enabled = True
        us.agps_source = "garmin"
        us.agps_epo_path = "GARMIN/REMOTESW/CPE.bin"
        db.commit()
        with patch("app.api.device_sync.public_fetch.fetch_public", return_value=b"CPEDATA"):
            resp = client.get("/device-sync/agps")
        assert resp.status_code == 200
        data = resp.json()
        assert data["due"] is True
        assert data["folder"] == "GARMIN/REMOTESW"
        assert data["filename"] == "CPE.bin"
        assert base64.b64decode(data["data_b64"]) == b"CPEDATA"

    def test_download_failure_is_502(self, client, user, db):
        us = self._settings(db, user)
        us.agps_enabled = True
        us.agps_source = "garmin"
        db.commit()
        with patch("app.api.device_sync.public_fetch.fetch_public",
                   side_effect=httpx.ConnectError("boom")):
            resp = client.get("/device-sync/agps")
        assert resp.status_code == 502

    def test_a_file_url_cannot_be_saved(self, client, user):
        """The download comes back to the caller, so a file:// URL would be a
        way to read the server's own files — its secrets among them."""
        resp = client.patch("/users/me/settings", json={
            "agps_source": "custom", "agps_custom_url": "file:///proc/self/environ"})
        assert resp.status_code == 422

    def test_a_stored_file_url_is_never_read(self, client, user, db, tmp_path):
        """A row saved before the settings check existed must not be fetched
        either: the download path refuses on its own."""
        secret = tmp_path / "secrets.env"
        secret.write_text("JWT_SECRET=do-not-return-me\n")
        us = self._settings(db, user)
        us.agps_enabled = True
        us.agps_source = "custom"
        us.agps_custom_url = f"file://{secret}"
        db.commit()
        resp = client.get("/device-sync/agps")
        assert resp.status_code == 400
        assert "do-not-return-me" not in resp.text

    def test_a_loopback_url_is_refused(self, client, user, db):
        """In the all-in-one image the database, Redis and the API all listen
        on 127.0.0.1, so a loopback URL is a request into the server itself."""
        us = self._settings(db, user)
        us.agps_enabled = True
        us.agps_source = "custom"
        us.agps_custom_url = "http://127.0.0.1:8000/openapi.json"
        db.commit()
        resp = client.get("/device-sync/agps")
        assert resp.status_code == 400

    def test_agps_synced_records_timestamp(self, client, user, db):
        resp = client.post("/device-sync/agps-synced")
        assert resp.status_code == 200
        db.expire_all()
        assert self._settings(db, user).agps_last_synced_at is not None


class TestBrowserSynced:
    def test_records_watch_last_synced_at(self, client, user, db):
        resp = client.post("/device-sync/synced")
        assert resp.status_code == 200
        db.expire_all()
        us = db.query(UserSettings).filter_by(user_id=user.id).first()
        assert us.watch_last_synced_at is not None


class TestGoalDeletionQueuesWatchDeletes:
    """Deleting a goal cascades away the plan/workout/race-plan rows, so the
    watch files they reference must be queued in WatchPendingDelete first —
    otherwise the delete-list can never tell a sync to remove them."""

    def test_delete_goal_queues_uploaded_files(self, client, user, db):
        goal = TrainingGoal(user_id=user.id, goal_type="event",
                            event_name="Race", event_sport="running",
                            event_date=date(2026, 10, 1))
        db.add(goal)
        db.flush()
        plan = TrainingPlan(goal_id=goal.id, user_id=user.id, sport="running")
        db.add(plan)
        db.flush()
        now = datetime.now(timezone.utc)
        db.add(PlannedWorkout(
            plan_id=plan.id, user_id=user.id, scheduled_date=date(2026, 9, 1),
            sport="running", workout_type="tempo", title="Tempo",
            watch_filename="W123.fit", watch_uploaded_at=now,
        ))
        db.add(RacePlan(goal_id=goal.id, user_id=user.id,
                        watch_filename="RACE_1.fit", watch_uploaded_at=now))
        db.commit()

        resp = client.delete(f"/coaching/goals/{goal.id}")
        assert resp.status_code == 204

        items = client.get("/device-sync/delete-list").json()["items"]
        pending = {i["filename"] for i in items if i["type"] == "pending"}
        assert "W123.fit" in pending
        assert "RACE_1.fit" in pending

    def test_delete_goal_ignores_never_uploaded_files(self, client, user, db):
        goal = TrainingGoal(user_id=user.id, goal_type="event")
        db.add(goal)
        db.flush()
        plan = TrainingPlan(goal_id=goal.id, user_id=user.id, sport="running")
        db.add(plan)
        db.flush()
        db.add(PlannedWorkout(
            plan_id=plan.id, user_id=user.id, scheduled_date=date(2026, 9, 1),
            sport="running", workout_type="tempo", title="Tempo",
        ))
        db.commit()

        assert client.delete(f"/coaching/goals/{goal.id}").status_code == 204
        assert client.get("/device-sync/delete-list").json()["items"] == []
