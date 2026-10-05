# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""app.services.fit_import.backfill_activity_summaries_for_user — the watch's
feel and perceived effort, read back into activities imported before the
parser knew those fields.

As in test_fit_import.py, `_parse_bytes` is patched with a parsed dict shaped
like ActivityParser's output: the parser's reading of fields 192/193 is its own
concern, checked against real files when it was added. What matters here is
which activities are read, what they may have written to them, and that a
finished backfill stays finished.

An "older" activity is made the way one really came to exist: imported by the
pipeline, then stripped of the marker an import made before the marker
existed would never have had.
"""

import hashlib
from unittest.mock import patch

from app.models.activity import Activity
from app.models.imports import Import
from app.services import fit_import

from .test_fit_import import _ACTIVITY_PARSED, _key_material, _queue_pending_import


def _parsed(**session):
    return {**_ACTIVITY_PARSED, "activity": {**_ACTIVITY_PARSED["activity"], **session}}


def _older_activity(db, user, pubkey, material, plaintext=b"older run", **stored):
    """An activity imported before feel and effort were read: its stored row
    lacks them and its Import row carries no summary_fields marker."""
    pending = _queue_pending_import(db, user, pubkey, plaintext=plaintext,
                                    filename=f"{plaintext.decode()}.fit")
    # A start of its own, so each is its own activity rather than a copy.
    started = _ACTIVITY_PARSED["activity"]["started_at"].replace(minute=sum(plaintext) % 60)
    with patch.object(fit_import, "_parse_bytes",
                      return_value=_parsed(started_at=started, **stored)):
        fit_import.process_pending_imports_for_user(user.id, material)
    db.expire_all()
    imp = db.query(Import).filter_by(user_id=user.id, source_id=pending.content_hash).one()
    imp.extra = {k: v for k, v in imp.extra.items() if k != "summary_fields"}
    db.commit()
    return db.get(Activity, imp.activity_id), imp


def _marker(db, imp):
    db.expire_all()
    return (db.get(Import, imp.id).extra or {}).get("summary_fields")


class TestSummaryBackfill:

    def test_an_older_activity_gets_its_feel_and_effort_from_its_file(self, db, user):
        material, pubkey = _key_material()
        activity, imp = _older_activity(db, user, pubkey, material)

        with patch.object(fit_import, "_parse_bytes",
                          return_value=_parsed(workout_feel=75, workout_rpe=60)) as parse:
            result = fit_import.backfill_activity_summaries_for_user(user.id, material)

        db.expire_all()
        row = db.get(Activity, activity.id)
        assert (row.workout_feel, row.workout_rpe) == (75, 60)
        assert result == {"files": 1, "filled": 1, "failed": 0}
        # The file it opened is the one that activity was parsed from.
        raw, _filename = parse.call_args.args
        assert hashlib.sha256(raw).hexdigest() == imp.source_id
        assert _marker(db, imp) == fit_import.SUMMARY_FIELDS_VERSION

    def test_nothing_but_those_two_columns_changes(self, db, user):
        """The file says 5 km; the row says what it was stored as. Anything a
        re-parse would now compute differently — a newer parser, a changed
        threshold — is not this job's to rewrite."""
        material, pubkey = _key_material()
        activity, _ = _older_activity(db, user, pubkey, material)
        activity.distance_meters = 4321.0
        activity.name = "Renamed on the phone"
        db.commit()
        before = {c.name: getattr(activity, c.name) for c in Activity.__table__.columns
                  if c.name not in ("workout_feel", "workout_rpe")}

        with patch.object(fit_import, "_parse_bytes",
                          return_value=_parsed(workout_feel=50, workout_rpe=80, distance_meters=9999.0,
                                               duration_seconds=1)):
            fit_import.backfill_activity_summaries_for_user(user.id, material)

        db.expire_all()
        row = db.get(Activity, activity.id)
        assert {c: getattr(row, c) for c in before} == before
        assert (row.workout_feel, row.workout_rpe) == (50, 80)

    def test_a_value_already_stored_is_never_replaced(self, db, user):
        """One answer present means the file was read for this already; the
        file is not opened, and the empty one stays empty."""
        material, pubkey = _key_material()
        activity, imp = _older_activity(db, user, pubkey, material, workout_rpe=50)

        with patch.object(fit_import, "_parse_bytes",
                          return_value=_parsed(workout_feel=100, workout_rpe=90)) as parse:
            fit_import.backfill_activity_summaries_for_user(user.id, material)

        parse.assert_not_called()
        db.expire_all()
        row = db.get(Activity, activity.id)
        assert (row.workout_feel, row.workout_rpe) == (None, 50)
        assert _marker(db, imp) == fit_import.SUMMARY_FIELDS_VERSION

    def test_a_skipped_prompt_is_read_once_and_not_at_every_login(self, db, user):
        """Both columns null is also what a skipped prompt leaves, so the
        columns cannot say whether a file has been read. The marker does."""
        material, pubkey = _key_material()
        activity, imp = _older_activity(db, user, pubkey, material)

        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()) as parse:
            fit_import.backfill_activity_summaries_for_user(user.id, material)
            fit_import.backfill_activity_summaries_for_user(user.id, material)

        assert parse.call_count == 1
        db.expire_all()
        row = db.get(Activity, activity.id)
        assert (row.workout_feel, row.workout_rpe) == (None, None)
        assert _marker(db, imp) == fit_import.SUMMARY_FIELDS_VERSION

    def test_a_finished_backfill_reads_nothing(self, db, user):
        material, pubkey = _key_material()
        _older_activity(db, user, pubkey, material)
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(workout_feel=25)):
            fit_import.backfill_activity_summaries_for_user(user.id, material)

        with patch.object(fit_import, "_parse_bytes") as parse:
            result = fit_import.backfill_activity_summaries_for_user(user.id, material)
        parse.assert_not_called()
        assert result == {"files": 0, "filled": 0, "failed": 0}

    def test_an_activity_imported_by_this_build_is_never_reread(self, db, user):
        """The import already read every field it knows, so it marks its own
        row; otherwise every new activity would be parsed a second time at the
        next login."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey)
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            fit_import.process_pending_imports_for_user(user.id, material)

        with patch.object(fit_import, "_parse_bytes") as parse:
            fit_import.backfill_activity_summaries_for_user(user.id, material)
        parse.assert_not_called()

    def test_a_file_that_fails_to_open_is_tried_again_and_blocks_nothing(self, db, user):
        material, pubkey = _key_material()
        bad, bad_imp = _older_activity(db, user, pubkey, material, plaintext=b"bad")
        good, _ = _older_activity(db, user, pubkey, material, plaintext=b"good")

        def parse(raw, filename):
            if filename == "bad.fit":
                raise ValueError("corrupt FIT file")
            return _parsed(workout_feel=75)

        with patch.object(fit_import, "_parse_bytes", side_effect=parse):
            result = fit_import.backfill_activity_summaries_for_user(user.id, material)

        assert result["failed"] == 1
        db.expire_all()
        assert db.get(Activity, good.id).workout_feel == 75
        assert _marker(db, bad_imp) is None   # read again at the next login
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(workout_feel=25)):
            fit_import.backfill_activity_summaries_for_user(user.id, material)
        db.expire_all()
        assert db.get(Activity, bad.id).workout_feel == 25

    def test_a_browser_uploads_copy_is_read_too(self, db, user):
        """Uploads are kept encrypted with the DEK rather than sealed, and are
        not in pending_imports at all."""
        material, _ = _key_material()
        content = b"uploaded run"
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            fit_import.import_uploaded_bytes(user.id, content, "upload.fit", material)
        imp = db.query(Import).filter_by(user_id=user.id, source="upload").one()
        imp.extra = {k: v for k, v in imp.extra.items() if k != "summary_fields"}
        db.commit()

        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(workout_rpe=70)) as parse:
            fit_import.backfill_activity_summaries_for_user(user.id, material)

        assert parse.call_args.args[0] == content
        db.expire_all()
        assert db.get(Activity, imp.activity_id).workout_rpe == 70

    def test_a_file_the_server_no_longer_holds_is_marked_and_left(self, db, user):
        """Nothing will ever be readable from it, so it must not cost a lookup
        at every login."""
        material, pubkey = _key_material()
        activity, imp = _older_activity(db, user, pubkey, material)
        with patch.object(fit_import, "retained_plaintext", return_value=None), \
                patch.object(fit_import, "_parse_bytes") as parse:
            result = fit_import.backfill_activity_summaries_for_user(user.id, material)
        parse.assert_not_called()
        assert result == {"files": 0, "filled": 0, "failed": 0}
        assert _marker(db, imp) == fit_import.SUMMARY_FIELDS_VERSION

    def test_another_users_activities_are_not_touched(self, db, user):
        from app.models.activity import User as UserModel
        material, pubkey = _key_material()
        activity, imp = _older_activity(db, user, pubkey, material)
        other = UserModel(name="Someone else")
        db.add(other)
        db.commit()

        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(workout_feel=75)) as parse:
            fit_import.backfill_activity_summaries_for_user(other.id, material)
        parse.assert_not_called()
        assert _marker(db, imp) is None

    def test_the_session_is_renewed_after_each_file(self, db, user):
        """A first run unseals one file per older activity; the crypto
        session's sliding timeout must not run out under it."""
        material, pubkey = _key_material()
        _older_activity(db, user, pubkey, material, plaintext=b"one")
        _older_activity(db, user, pubkey, material, plaintext=b"two")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()), \
                patch.object(fit_import.crypto_context, "renew_session_key") as renew:
            fit_import.backfill_activity_summaries_for_user(user.id, material, sid="s")
        assert renew.call_count == 2


class TestTheTriggers:

    def test_the_task_with_no_session_is_a_no_op(self):
        from app.tasks.imports import backfill_activity_summaries
        with patch("app.tasks.imports.fit_import.backfill_activity_summaries_for_user") as run:
            backfill_activity_summaries(user_id=1, sid="does-not-exist")
        run.assert_not_called()

    def test_the_task_reads_its_key_from_the_session(self, user):
        from app.services import crypto_context
        from app.tasks.imports import backfill_activity_summaries
        material, _ = _key_material()
        crypto_context.store_session_key("sid-1", material)
        with patch("app.tasks.imports.fit_import.backfill_activity_summaries_for_user") as run:
            backfill_activity_summaries(user_id=user.id, sid="sid-1")
        assert run.call_args.args[0] == user.id
        assert run.call_args.args[1].dek == material.dek
        assert run.call_args.kwargs == {"sid": "sid-1"}

    def test_login_queues_it_with_the_new_session(self, client, user):
        """Login is the first moment a key that opens the files exists."""
        assert client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        }).status_code == 200
        with patch("app.api.auth.backfill_activity_summaries") as task:
            resp = client.post("/auth/login", json={"username": "admin", "password": "originalpass1"})
        assert resp.status_code == 200
        (user_id, sid), _ = task.delay.call_args
        assert user_id == user.id and sid

    def test_the_endpoint_queues_it_for_the_callers_session(self, client, user):
        from app.auth import create_token
        token = create_token(user.id, sid="sid-7")
        with patch("app.api.activities.backfill.backfill_activity_summaries") as task:
            resp = client.post("/activities/summaries/backfill",
                               headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 202
        task.delay.assert_called_once_with(user.id, "sid-7")

    def test_the_endpoint_refuses_an_anonymous_caller(self, client, user):
        assert client.post("/activities/summaries/backfill").status_code == 401

