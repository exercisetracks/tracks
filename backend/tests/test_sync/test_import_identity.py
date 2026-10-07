# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How a FIT file becomes a synced identity: dedupe, activity uids, the file
registry, and edits that arrive before the file does."""
import copy
import hashlib
from datetime import datetime, timezone
from unittest.mock import patch

from app.models.activity import Activity, User
from app.models.imports import Import
from app.models.sync import FitFile, SyncPendingRow, SyncTombstone
from app.models.user_settings import UserSettings
from app.services import fit_import
from app.sync.registry import activity_uid

from .helpers import Phone, latest, pull_all, push, setup_admin
from ..test_services.test_fit_import import _ACTIVITY_PARSED, _key_material, _queue_pending_import

PHONE = Phone("00000000000000cc")


def _import(user, material):
    fit_import.process_pending_imports_for_user(user.id, material)


def _parsed(**activity):
    p = copy.deepcopy(_ACTIVITY_PARSED)
    p["activity"].update(activity)
    return p


class TestDedupe:
    def test_the_same_file_for_two_accounts_imports_twice(self, db, user):
        """A shared ride on a shared watch is in both people's history. A
        global hash check silently dropped the second person's copy."""
        other = User(name="Partner")
        db.add(other)
        db.flush()
        db.add(UserSettings(user_id=other.id))
        db.commit()
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey, plaintext=b"shared ride")
        _queue_pending_import(db, other, pubkey, plaintext=b"shared ride")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            _import(user, material)
            _import(other, material)
        assert db.query(Activity).filter_by(user_id=user.id).count() == 1
        assert db.query(Activity).filter_by(user_id=other.id).count() == 1

    def test_the_same_activity_in_different_bytes_is_one_activity(self, db, user):
        """Same watch, same start second: a re-export must not double the ride."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey, plaintext=b"copy one", filename="a.fit")
        _queue_pending_import(db, user, pubkey, plaintext=b"copy two", filename="b.fit")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            _import(user, material)
        acts = db.query(Activity).filter_by(user_id=user.id).all()
        assert len(acts) == 1
        assert acts[0].uid == activity_uid("12345", datetime(2026, 1, 1, tzinfo=timezone.utc), None)
        assert db.query(FitFile).filter_by(user_id=user.id).count() == 2

    def test_the_lowest_hash_copy_is_the_one_parsed_whichever_arrives_first(self, db, user):
        """Every replica must show the same numbers for an activity, so the
        winner cannot depend on arrival order."""
        material, pubkey = _key_material()
        low, high = sorted([b"copy one", b"copy two"], key=lambda b: hashlib.sha256(b).hexdigest())
        _queue_pending_import(db, user, pubkey, plaintext=high, filename="high.fit")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(distance_meters=1.0)):
            _import(user, material)
        _queue_pending_import(db, user, pubkey, plaintext=low, filename="low.fit")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(distance_meters=2.0)):
            _import(user, material)
        act = db.query(Activity).filter_by(user_id=user.id).one()
        assert act.distance_meters == 2.0
        winner = db.query(Import).filter_by(activity_id=act.id).one()
        assert winner.source_id == hashlib.sha256(low).hexdigest()

    def test_a_deleted_activity_is_not_brought_back_by_another_copy(self, db, user, client):
        """Delete wins; the file is kept, the activity stays gone."""
        material, pubkey = _key_material()
        _queue_pending_import(db, user, pubkey, plaintext=b"copy one")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            _import(user, material)
        act = db.query(Activity).filter_by(user_id=user.id).one()
        client.delete(f"/activities/{act.id}")
        assert db.query(SyncTombstone).filter_by(entity="activity", uid=act.uid).one()
        _queue_pending_import(db, user, pubkey, plaintext=b"copy two")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            _import(user, material)
        assert db.query(Activity).filter_by(user_id=user.id).count() == 0


class TestRegistry:
    def test_every_ingested_file_is_registered_before_it_is_parsed(self, client, db, user):
        """Other phones learn a file exists (and fetch it) without waiting for
        the vault to open and the parse to run."""
        from app.models.sync_agents import SyncAgent
        from app.services.sync_agent_auth import _hash_token
        db.add(SyncAgent(user_id=user.id, kind="phone", label="p", token_hash=_hash_token("tok")))
        db.commit()
        resp = client.post("/sync/ingest", headers={"Authorization": "Bearer tok"}, json={
            "filename": "ride.fit", "content_hash": "ab" * 32, "sealed_b64": "AAAA"})
        assert resp.status_code == 200, resp.text
        f = db.query(FitFile).filter_by(user_id=user.id).one()
        assert (f.sha256, f.filename, f.sealed) == ("ab" * 32, "ride.fit", True)
        assert f.clock.get("sha256")

    def test_a_file_is_downloadable_by_its_owner_only(self, client, db, user):
        """Blobs are looked up by (user, hash): a household can share bytes."""
        h = setup_admin(client)
        from app.services.crypto_context import load_session_key  # noqa: F401
        from app.auth import decode_token
        from app.services.crypto_context import load_session_key as load
        sid = decode_token(h["Authorization"].split()[1])["sid"]
        material = load(sid)
        uk_pub = __import__("app.models.user_keys", fromlist=["UserKey"]).UserKey
        pub = db.query(uk_pub).filter_by(user_id=user.id).one().public_key
        pending = _queue_pending_import(db, user, pub, plaintext=b"my ride")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed()):
            _import(user, material)
        resp = client.get(f"/sync/blobs/{pending.content_hash}", headers=h)
        assert resp.status_code == 200
        assert resp.content == b"my ride"
        assert client.get(f"/sync/blobs/{'0' * 64}", headers=h).status_code == 404

        changes, _ = pull_all(client, h)
        files = [c for c in changes if c["entity"] == "fit_file"]
        assert [c["fields"]["sha256"][0] for c in files] == [pending.content_hash]
        assert files[0]["fields"]["file_kind"][0] == "activity"


class TestEditsBeforeTheFile:
    def test_a_rename_pushed_before_the_file_is_kept_and_applied_on_import(self, client, db, user):
        """A phone names the ride offline, then uploads it. The server's import
        must not outrank that name with the file's own."""
        h = setup_admin(client)
        uid = activity_uid("12345", datetime(2026, 1, 1, tzinfo=timezone.utc), None)
        res = push(client, h, PHONE.change("activity", uid=uid, name="Summit day"))
        assert res["results"][0]["status"] == "applied"
        assert db.query(SyncPendingRow).filter_by(entity="activity", uid=uid).one()

        from app.auth import decode_token
        from app.services.crypto_context import load_session_key
        from app.models.user_keys import UserKey
        material = load_session_key(decode_token(h["Authorization"].split()[1])["sid"])
        pub = db.query(UserKey).filter_by(user_id=user.id).one().public_key
        _queue_pending_import(db, user, pub, plaintext=b"the ride")
        with patch.object(fit_import, "_parse_bytes", return_value=_parsed(name="Morning Run")):
            _import(user, material)

        act = db.query(Activity).filter_by(user_id=user.id).one()
        assert act.name == "Summit day"
        assert db.query(SyncPendingRow).filter_by(entity="activity", uid=uid).first() is None
        changes, _ = pull_all(client, h)
        assert latest(changes, "activity", uid)["fields"]["name"][0] == "Summit day"
