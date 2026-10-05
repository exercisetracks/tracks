# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
_get_active_injuries must degrade to "no known active injuries" rather than
crash when no decryption key is available (e.g. plan generation triggered
outside a request/session) — Injury.body_part/injury_type/notes are
encrypted, and SQLAlchemy hydrates every mapped column on load regardless of
which ones the caller reads. See app/api/training_plan/injectors.py.
"""
from datetime import date
from types import SimpleNamespace

from app.api.training_plan.injectors import _get_active_injuries
from app.models.health import Injury
from app.services import crypto_context, user_crypto


def _key_material():
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    return user_crypto.unwrap_with_password("correct horse battery staple", row)


def test_returns_active_injuries_when_key_available(db, user):
    material = _key_material()
    token = crypto_context.set_current_key(material)
    try:
        db.add(Injury(
            user_id=user.id, body_part="knee", injury_type="strain",
            severity=5, start_date=date(2026, 1, 1),
        ))
        db.commit()

        result = _get_active_injuries(db, user.id)
        assert len(result) == 1
        assert result[0].body_part == "knee"
    finally:
        crypto_context.reset_current_key(token)


def test_degrades_to_empty_list_without_a_key(db, user):
    # No injuries even need to exist — the point is that a missing key
    # never propagates as an unhandled exception out of this function.
    token = crypto_context.set_current_key(None)
    try:
        assert _get_active_injuries(db, user.id) == []
    finally:
        crypto_context.reset_current_key(token)
