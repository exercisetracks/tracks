# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for the transparent-encryption SQLAlchemy column types
(app.services.encrypted_columns), exercised against the real Injury model —
the first table these were applied to (see the branch plan, stage 3).
"""

from datetime import date

import pytest
from sqlalchemy import text
from sqlalchemy.exc import StatementError

from app.models.health import Injury
from app.services import crypto_context, user_crypto


@pytest.fixture
def key_material():
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    from types import SimpleNamespace
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    return user_crypto.unwrap_with_password("correct horse battery staple", row)


@pytest.fixture
def active_key(key_material):
    """Sets the contextvar for the duration of the test."""
    token = crypto_context.set_current_key(key_material)
    yield key_material
    crypto_context.reset_current_key(token)


def test_write_without_active_key_raises(db, user):
    inj = Injury(
        user_id=user.id, body_part="knee", injury_type="strain",
        severity=3, start_date=date(2026, 1, 1), notes="tweaked on a run",
    )
    db.add(inj)
    # SQLAlchemy wraps whatever a bind-param processor raises in a
    # StatementError — the original MissingDecryptionKey survives as .orig.
    with pytest.raises(StatementError) as exc_info:
        db.commit()
    assert isinstance(exc_info.value.orig, crypto_context.MissingDecryptionKey)


def test_write_and_read_round_trip(db, user, active_key):
    inj = Injury(
        user_id=user.id, body_part="knee", injury_type="strain",
        severity=3, start_date=date(2026, 1, 1), notes="tweaked on a run",
    )
    db.add(inj)
    db.commit()
    db.refresh(inj)

    assert inj.body_part == "knee"
    assert inj.injury_type == "strain"
    assert inj.notes == "tweaked on a run"

    # Fresh read from the DB (not just the in-memory object) decrypts too.
    db.expire(inj)
    reloaded = db.query(Injury).filter_by(id=inj.id).first()
    assert reloaded.body_part == "knee"
    assert reloaded.notes == "tweaked on a run"


def test_ciphertext_at_rest_does_not_contain_plaintext(db, user, active_key):
    inj = Injury(
        user_id=user.id, body_part="shoulder", injury_type="impingement",
        severity=5, start_date=date(2026, 1, 1), notes="very distinctive marker text",
    )
    db.add(inj)
    db.commit()

    raw = db.execute(
        text("SELECT body_part, injury_type, notes FROM injuries WHERE id = :id"),
        {"id": inj.id},
    ).first()

    assert b"shoulder" not in bytes(raw[0])
    assert b"impingement" not in bytes(raw[1])
    assert b"very distinctive marker text" not in bytes(raw[2])


def test_read_without_active_key_raises(db, user, active_key):
    inj = Injury(
        user_id=user.id, body_part="ankle", injury_type="sprain",
        severity=4, start_date=date(2026, 1, 1),
    )
    db.add(inj)
    db.commit()
    injury_id = inj.id
    db.expire(inj)

    # Key was only active for the fixture's yield; drop it explicitly here
    # to simulate a request/task with no crypto session.
    token = crypto_context.set_current_key(None)
    try:
        with pytest.raises(crypto_context.MissingDecryptionKey):
            db.query(Injury).filter_by(id=injury_id).first().body_part
    finally:
        crypto_context.reset_current_key(token)


def test_null_notes_do_not_require_a_key():
    """None never touches the crypto path either way — not every injury has
    notes, and that shouldn't force a key requirement for the whole row...
    (it still does, because body_part/injury_type are NOT NULL — this test
    documents that a NULL encrypted column itself is a no-op, independent of
    sibling columns)."""
    from app.services.encrypted_columns import EncryptedString
    col = EncryptedString()
    assert col.process_bind_param(None, None) is None
    assert col.process_result_value(None, None) is None
