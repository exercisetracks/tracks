# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Unit tests for per-user envelope encryption (app.services.user_crypto).

No DB/FastAPI involved — these exercise the crypto primitives directly
against a lightweight stand-in for a UserKey row.
"""

from types import SimpleNamespace

import pytest

from app.services import user_crypto as uc


def _row(gen: uc.GeneratedUserKeys):
    """Build a UserKey-row-shaped object from what generate_user_keys() returns."""
    return SimpleNamespace(
        salt=gen.salt,
        kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek,
        wrapped_privkey=gen.wrapped_privkey,
        recovery_salt=gen.recovery_salt,
        recovery_wrapped_dek=gen.recovery_wrapped_dek,
        recovery_wrapped_privkey=gen.recovery_wrapped_privkey,
    )


def test_password_unwrap_round_trip():
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)

    material = uc.unwrap_with_password("correct horse battery staple", row)

    assert len(material.dek) == 32
    assert bytes(material.privkey.public_key) == gen.public_key


def test_wrong_password_rejected():
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)

    with pytest.raises(uc.WrongSecret):
        uc.unwrap_with_password("not the password", row)


def test_recovery_key_recovers_identical_material():
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)

    via_password = uc.unwrap_with_password("correct horse battery staple", row)
    via_recovery = uc.unwrap_with_recovery_key(gen.recovery_key, row)

    assert via_password.dek == via_recovery.dek
    assert bytes(via_password.privkey) == bytes(via_recovery.privkey)


def test_wrong_recovery_key_rejected():
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)

    with pytest.raises(uc.WrongSecret):
        uc.unwrap_with_recovery_key(b"\x00" * 32, row)


def test_sealed_box_round_trip_without_password():
    """A sync agent holding only the public key can seal data for the user —
    unsealing requires the unwrapped privkey (i.e. an active session)."""
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)
    material = uc.unwrap_with_password("correct horse battery staple", row)

    sealed = uc.seal_for_user(gen.public_key, b"raw fit bytes")
    assert uc.unseal(material, sealed) == b"raw fit bytes"


def test_column_encryption_round_trip():
    gen = uc.generate_user_keys("correct horse battery staple")
    row = _row(gen)
    material = uc.unwrap_with_password("correct horse battery staple", row)

    ciphertext = uc.encrypt_bytes(material.dek, b"lat=42.1,lng=-71.05")
    assert ciphertext != b"lat=42.1,lng=-71.05"
    assert uc.decrypt_bytes(material.dek, ciphertext) == b"lat=42.1,lng=-71.05"


def test_password_change_rewraps_and_invalidates_old_password():
    gen = uc.generate_user_keys("old password 123")
    row = _row(gen)
    material = uc.unwrap_with_password("old password 123", row)

    salt, wrapped_dek, wrapped_privkey, params = uc.rewrap_on_password_change(
        "new password 456", material
    )
    row.salt, row.wrapped_dek, row.wrapped_privkey, row.kdf_params = (
        salt, wrapped_dek, wrapped_privkey, params,
    )

    # New password works and recovers the *same* dek.
    after = uc.unwrap_with_password("new password 456", row)
    assert after.dek == material.dek

    # Old password no longer works.
    with pytest.raises(uc.WrongSecret):
        uc.unwrap_with_password("old password 123", row)

    # Recovery key (untouched by the password change) still works.
    via_recovery = uc.unwrap_with_recovery_key(gen.recovery_key, row)
    assert via_recovery.dek == material.dek


def test_kdf_version_bump_does_not_break_existing_accounts():
    """An account wrapped under old, weaker kdf_params must still unwrap
    correctly regardless of what DEFAULT_KDF_PARAMS currently is — unwrap
    always reads params from the row, never a hardcoded default."""
    old_params = {"version": 0, "time_cost": 2, "memory_cost": 8192, "parallelism": 1}
    assert old_params != uc.DEFAULT_KDF_PARAMS

    salt = b"0123456789abcdef"
    kek = uc._derive_kek(b"correct horse battery staple", salt, old_params)
    row = SimpleNamespace(salt=salt, kdf_params=old_params, wrapped_dek=uc._wrap(kek, b"x" * 32))

    dek = uc._unwrap(uc._derive_kek(b"correct horse battery staple", row.salt, row.kdf_params), row.wrapped_dek)
    assert dek == b"x" * 32


def test_recovery_key_display_format_round_trips():
    gen = uc.generate_user_keys("correct horse battery staple")
    displayed = uc.format_recovery_key(gen.recovery_key)

    assert uc.parse_recovery_key(displayed) == gen.recovery_key
    # Tolerates the whitespace/casing a user might introduce copying it by hand.
    assert uc.parse_recovery_key(f" {displayed.lower()} ") == gen.recovery_key


def test_recovery_key_is_a_standard_12_word_bip39_phrase():
    gen = uc.generate_user_keys("correct horse battery staple")
    displayed = uc.format_recovery_key(gen.recovery_key)

    words = displayed.split()
    assert len(words) == 12
    assert all(w.isalpha() and w.islower() for w in words)


def test_mistyped_recovery_word_is_caught_by_checksum():
    """BIP39's checksum word catches most single-word transcription errors
    immediately, rather than failing later with an opaque wrong-key error.

    "Most" is doing real work in that sentence: the checksum is only
    entropy_bits/32 = 4 bits for a 16-byte recovery key, so roughly 1 in 16
    single-word substitutions produce a different-but-still-valid phrase (a
    false negative) — inherent to BIP39, not a bug here. Using
    generate_user_keys's random recovery_key (via secrets.token_bytes) would
    make this test's outcome a coin flip across runs. Using a fixed all-zero
    entropy instead makes both the resulting phrase and the corruption's
    effect on the checksum deterministic — "ability" swapped in for
    "abandon" is verified (see git history) to always fail the checksum for
    this specific entropy."""
    words = uc.format_recovery_key(bytes(16)).split()
    assert words[0] == "abandon"
    words[0] = "ability"
    corrupted = " ".join(words)

    with pytest.raises(uc.WrongSecret):
        uc.parse_recovery_key(corrupted)
