# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""app.scripts.repair_datapoint_plaintext — the one-off repair for the real
incident where the GPS-encryption migration silently left pre-existing
data_points rows as plaintext-in-bytea instead of AES-256-GCM ciphertext
(see that migration's docstring). Exercises the classification logic
(_repair_column in dry-run mode, so no real Postgres write path is needed)
and the plaintext-parsing helper directly.
"""

from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from app.scripts.repair_datapoint_plaintext import _plaintext_float, _repair_column
from app.services import user_crypto


def _key_material():
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    return user_crypto.unwrap_with_password("correct horse battery staple", row)


class TestPlaintextFloat:
    def test_parses_a_plaintext_float(self):
        assert _plaintext_float(b"12.345678901234567") == 12.345678901234567

    def test_rejects_non_numeric_text(self):
        assert _plaintext_float(b"not a float") is None

    def test_rejects_invalid_utf8(self):
        assert _plaintext_float(b"\xff\xfe\x00\x01") is None


class TestRepairColumn:
    def test_classifies_correctly_encrypted_plaintext_corrupted_and_garbage_rows(self):
        material = _key_material()
        real_ciphertext = user_crypto.encrypt_bytes(material.dek, repr(40.0).encode("utf-8"))
        corrupted_plaintext = b"12.345678901234567"
        garbage = b"\xff\xfe\x00\x01not-recoverable"

        cursor = MagicMock()
        cursor.fetchall.return_value = [
            (1, real_ciphertext),
            (2, corrupted_plaintext),
            (3, garbage),
        ]

        repaired, already_ok, unrecoverable = _repair_column(cursor, material, "lat", apply=False, user_id=1)

        assert repaired == 1
        assert already_ok == 1
        assert unrecoverable == 1
        # Dry run: the read happened, but nothing was written back.
        cursor.execute.assert_called_once()

    def test_apply_writes_only_the_repaired_rows(self):
        material = _key_material()
        real_ciphertext = user_crypto.encrypt_bytes(material.dek, repr(40.0).encode("utf-8"))
        corrupted_plaintext = b"12.345678901234567"

        cursor = MagicMock()
        cursor.fetchall.return_value = [(1, real_ciphertext), (2, corrupted_plaintext)]

        with patch("app.scripts.repair_datapoint_plaintext.execute_values") as mock_execute_values:
            repaired, already_ok, _ = _repair_column(cursor, material, "lng", apply=True, user_id=1)

        assert repaired == 1
        assert already_ok == 1
        mock_execute_values.assert_called_once()
        batch = mock_execute_values.call_args.args[2]
        assert len(batch) == 1
        assert batch[0][0] == 2  # only the corrupted row's id was queued for update
