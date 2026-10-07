# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""app.tasks.imports.process_pending_imports — the Celery task that drains a
user's PendingImport backlog after login. Runs eagerly in tests (see
conftest.py), so these call the task function directly; behavior under a
real worker is identical since eager mode still goes through Celery's
argument (de)serialization.
"""

from types import SimpleNamespace
from unittest.mock import patch

from app.services import crypto_context, user_crypto
from app.tasks.imports import process_pending_imports


def _store_session(sid: str):
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    material = user_crypto.unwrap_with_password("correct horse battery staple", row)
    crypto_context.store_session_key(sid, material)
    return material


class TestProcessPendingImportsTask:
    def test_missing_session_is_a_no_op_not_a_crash(self):
        """The session (Redis, sliding TTL) can expire between being queued
        at login and the worker picking the task up — must degrade to
        "nothing to do this run", not raise and get stuck retrying forever
        on a key that's gone for good."""
        with patch("app.tasks.imports.fit_import.process_pending_imports_for_user") as mock_process:
            process_pending_imports(user_id=1, sid="does-not-exist")
        mock_process.assert_not_called()

    def test_valid_session_delegates_with_material_and_sid(self, user):
        sid = "a-real-session-id"
        material = _store_session(sid)

        with patch("app.tasks.imports.fit_import.process_pending_imports_for_user") as mock_process:
            process_pending_imports(user_id=user.id, sid=sid)

        mock_process.assert_called_once()
        called_user_id, called_material = mock_process.call_args.args
        assert called_user_id == user.id
        assert called_material.dek == material.dek
        assert mock_process.call_args.kwargs == {"sid": sid}
