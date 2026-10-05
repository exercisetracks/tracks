# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for app.services.fit_import.process_pending_imports_for_user — the
unseal -> parse -> insert pipeline that drains PendingImport rows once a
user's key becomes available (at login). The FIT *parser* itself is
pre-existing and untested elsewhere in this suite (no fixture .fit files
exist), so these mock app.services.fit_import._parse_bytes with a
canned parsed dict shaped like ActivityParser/DailyHealthParser's real
output, and focus on what's new here: unsealing, dedup, error isolation,
and the actual DB writes.
"""

import base64
import hashlib
from datetime import date, datetime, timezone
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from app.models.activity import Activity, Device
from app.models.imports import Import, PendingImport
from app.services import fit_import, object_storage, user_crypto


def _key_material():
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    material = user_crypto.unwrap_with_password("correct horse battery staple", row)
    return material, gen.public_key


_ACTIVITY_PARSED = {
    "type": "activity",
    "device": {
        "serial_number": "12345", "manufacturer": "garmin", "manufacturer_id": 1,
        "product_name": "Fenix", "product_id": 1,
    },
    "activity": {
        "sport": "running", "sub_sport": "generic",
        "started_at": datetime(2026, 1, 1, tzinfo=timezone.utc),
        "duration_seconds": 1800, "distance_meters": 5000.0,
        "avg_heart_rate": 150, "max_heart_rate": 175,
    },
    "data_points": [],
    "laps": [],
    "strength_sets": [],
    "climb_splits": [],
    "power_curve": {},
    "pace_curve": {},
}

_DAILY_PARSED = {
    "type": "daily",
    "device": {"serial_number": "12345", "manufacturer": "garmin", "manufacturer_id": 1},
    "date": date(2026, 1, 2),
    "metrics": {"resting_hr": 52, "hrv": 60},
}


def _queue_pending_import(db, user, public_key, plaintext=b"raw fit bytes", filename="a.fit") -> PendingImport:
    sealed = user_crypto.seal_for_user(public_key, plaintext)
    blob_id = object_storage.store_blob(sealed)
    p = PendingImport(
        user_id=user.id, blob_id=blob_id,
        content_hash=hashlib.sha256(plaintext).hexdigest(),
        filename=filename,
    )
    db.add(p)
    db.commit()
    return p


class TestProcessPendingImports:
    def test_no_pending_imports_is_a_no_op(self, db, user):
        material, _ = _key_material()
        fit_import.process_pending_imports_for_user(user.id, material)  # must not raise

    def test_activity_import_is_unsealed_parsed_and_inserted(self, db, user):
        material, pubkey = _key_material()
        pending = _queue_pending_import(db, user, pubkey)

        with patch.object(fit_import, "_parse_bytes", return_value=_ACTIVITY_PARSED):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        reloaded = db.query(PendingImport).filter_by(id=pending.id).first()
        assert reloaded.processed_at is not None
        assert reloaded.error is None

        activity = db.query(Activity).filter_by(user_id=user.id).first()
        assert activity is not None
        assert activity.sport == "running"
        assert activity.distance_meters == 5000.0

        device = db.query(Device).filter_by(serial_number="12345").first()
        assert device is not None

        imp = db.query(Import).filter_by(source="sync", source_id=pending.content_hash).first()
        assert imp is not None
        assert imp.activity_id == activity.id

    def test_the_watchs_post_workout_answers_are_kept(self, db, user):
        """Feel and perceived effort were in every prompted FIT file and dropped
        on import, so no screen could show them."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey)
        parsed = {**_ACTIVITY_PARSED,
                  "activity": {**_ACTIVITY_PARSED["activity"], "workout_feel": 75, "workout_rpe": 60}}

        with patch.object(fit_import, "_parse_bytes", return_value=parsed):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        activity = db.query(Activity).filter_by(user_id=user.id).first()
        assert (activity.workout_feel, activity.workout_rpe) == (75, 60)

    def test_daily_metrics_import(self, db, user):
        from app.models.metrics import DailyMetric

        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey, plaintext=b"daily health bytes")

        with patch.object(fit_import, "_parse_bytes", return_value=_DAILY_PARSED):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        dm = db.query(DailyMetric).filter_by(user_id=user.id, date=date(2026, 1, 2)).first()
        assert dm is not None
        assert dm.resting_hr == 52

    def test_unparseable_file_is_recorded_and_does_not_block_others(self, db, user):
        material, pubkey = _key_material()
        bad = _queue_pending_import(db, user, pubkey, plaintext=b"bad 1", filename="bad.fit")
        good = _queue_pending_import(db, user, pubkey, plaintext=b"good 1", filename="good.fit")

        def fake_parse(raw, filename):
            if filename == "bad.fit":
                raise ValueError("corrupt FIT file")
            return _ACTIVITY_PARSED

        with patch.object(fit_import, "_parse_bytes", side_effect=fake_parse):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        bad_row = db.query(PendingImport).filter_by(id=bad.id).first()
        good_row = db.query(PendingImport).filter_by(id=good.id).first()
        assert bad_row.processed_at is None
        assert "corrupt FIT file" in bad_row.error
        assert good_row.processed_at is not None

    def test_already_processed_rows_are_not_reprocessed(self, db, user):
        material, pubkey = _key_material()
        pending = _queue_pending_import(db, user, pubkey)
        pending.processed_at = datetime.now(timezone.utc)
        db.commit()

        with patch.object(fit_import, "_parse_bytes") as mock_parse:
            fit_import.process_pending_imports_for_user(user.id, material)
        mock_parse.assert_not_called()

    def test_unrecognised_type_is_recorded_without_inserting_an_activity(self, db, user):
        material, pubkey = _key_material()
        pending = _queue_pending_import(db, user, pubkey)

        with patch.object(fit_import, "_parse_bytes", return_value=None):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        assert db.query(Activity).filter_by(user_id=user.id).count() == 0
        assert db.query(PendingImport).filter_by(id=pending.id).first().processed_at is not None
        assert db.query(Import).filter_by(source="sync", source_id=pending.content_hash).first() is not None

    def test_duplicate_content_hash_is_skipped_not_a_hard_failure(self, db, user):
        """A sync agent can re-deliver a file the server already has an
        Import record for under the SAME source — its own local "already
        synced" state can get reset independent of what the server already
        knows. This must resolve as "already imported", not an
        IntegrityError on the imports table's (source, source_id) unique
        constraint that permanently strands the PendingImport row as
        errored (nothing un-queues or retries it differently, so it would
        fail identically forever)."""
        material, pubkey = _key_material()
        pending = _queue_pending_import(db, user, pubkey, plaintext=b"already imported bytes")
        db.add(Import(user_id=user.id, source="sync", source_id=pending.content_hash,
                      extra={"filename": "earlier-import.fit"}))
        db.commit()

        with patch.object(fit_import, "_parse_bytes", return_value=_ACTIVITY_PARSED):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        assert db.query(Activity).filter_by(user_id=user.id).count() == 0  # not re-inserted
        row = db.query(PendingImport).filter_by(id=pending.id).first()
        assert row.processed_at is not None
        assert row.error is None

    def test_duplicate_under_a_different_source_is_also_skipped(self, db, user):
        """The real incident this regression-tests: the same file content
        already imported under source="directory" (the now-deleted
        directory watcher's label) must still be recognised as a duplicate
        when a sync agent later delivers the identical bytes under
        source="sync" — the imports table's unique constraint is scoped to
        (source, source_id), so a source-scoped duplicate check let this
        through and silently created a second Activity + DataPoints for
        data already on file, under a different source label."""
        material, pubkey = _key_material()
        pending = _queue_pending_import(db, user, pubkey, plaintext=b"already imported bytes")
        db.add(Import(user_id=user.id, source="directory", source_id=pending.content_hash,
                      extra={"filename": "2026-01-01.fit"}))
        db.commit()

        with patch.object(fit_import, "_parse_bytes", return_value=_ACTIVITY_PARSED):
            fit_import.process_pending_imports_for_user(user.id, material)

        db.expire_all()
        assert db.query(Activity).filter_by(user_id=user.id).count() == 0  # not re-inserted
        row = db.query(PendingImport).filter_by(id=pending.id).first()
        assert row.processed_at is not None
        assert row.error is None
        assert row.error is None

    def test_sid_given_renews_the_session_after_each_item(self, db, user):
        """A large backlog processed by the Celery worker (app.tasks.imports)
        could run past the crypto session's own sliding Redis TTL with no
        HTTP activity to keep it alive — sid, when given, keeps it alive."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey, plaintext=b"one", filename="one.fit")
        _queue_pending_import(db, user, pubkey, plaintext=b"two", filename="two.fit")

        with patch.object(fit_import, "_parse_bytes", return_value=_ACTIVITY_PARSED), \
             patch.object(fit_import.crypto_context, "renew_session_key") as mock_renew:
            fit_import.process_pending_imports_for_user(user.id, material, sid="test-sid")

        assert mock_renew.call_count == 2
        mock_renew.assert_called_with("test-sid")

    def test_no_sid_never_touches_the_session_cache(self, db, user):
        """Direct/test callers that already have `material` shouldn't need a
        real Redis-backed session just to process an import."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey)

        with patch.object(fit_import, "_parse_bytes", return_value=_ACTIVITY_PARSED), \
             patch.object(fit_import.crypto_context, "renew_session_key") as mock_renew:
            fit_import.process_pending_imports_for_user(user.id, material)

        mock_renew.assert_not_called()
