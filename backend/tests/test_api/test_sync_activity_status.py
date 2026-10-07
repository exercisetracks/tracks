# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The sidebar's status: only what is actually under way, and only one's own.

It used to spin "Importing…" for days over files the vault could not open, and
spin every account's sidebar whenever any watch was docked.
"""
from app.models.imports import PendingImport
from app.services import activity_status

STATUS = "/training-plan/sync/status"


def _queue(db, user, n, error=None):
    for i in range(n):
        db.add(PendingImport(user_id=user.id, blob_id=f"b{i}", content_hash=f"h{i}",
                             filename=f"{i}.fit", error=error))
    db.commit()


def test_files_the_vault_cannot_open_are_waiting_not_importing(client, user, db):
    """The 2026-10-07 bug: 54 files queued since the 3rd, spinner on throughout."""
    _queue(db, user, 3)
    a = client.get(STATUS).json()["activity"]
    assert a["importing"] is None
    assert a["waiting"] == 3


def test_a_file_that_failed_to_parse_is_not_counted_as_waiting(client, user, db):
    _queue(db, user, 2, error="not a FIT file")
    a = client.get(STATUS).json()["activity"]
    assert a["waiting"] == 0 and a["failed"] == 2


def test_a_running_import_reports_its_progress(client, user):
    activity_status.mark(user.id, "import", done=4, total=10)
    assert client.get(STATUS).json()["activity"]["importing"]["done"] == 4


def test_a_watch_docked_for_another_account_does_not_spin_this_one(client, user):
    activity_status.mark(user.id + 1, "watch")
    body = client.get(STATUS).json()
    assert body["activity"]["watch"] is False and body["is_syncing"] is False
    activity_status.mark(user.id, "watch")
    assert client.get(STATUS).json()["activity"]["watch"] is True


def test_a_phone_talking_to_the_server_shows_as_syncing_and_leaves_a_time(client, user):
    activity_status.mark(user.id, "phone")
    a = client.get(STATUS).json()["activity"]
    assert a["phone"] is True and a["phone_last_at"] is not None
    activity_status.clear(user.id, "phone")
    a = client.get(STATUS).json()["activity"]
    assert a["phone"] is False and a["phone_last_at"] is not None
