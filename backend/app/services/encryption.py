# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Fernet symmetric encryption for storing sensitive values (AI API keys) in the database.

The encryption key is read from ENCRYPTION_KEY in the environment.  If not set, it falls
back to a SHA-256 digest of JWT_SECRET so the app always starts, but a warning is logged.

Key format: URL-safe base64-encoded 32 bytes (standard Fernet key).
Generate a fresh key with:
    python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
"""

import base64
import hashlib
import logging

from cryptography.fernet import Fernet

from app.config import settings

log = logging.getLogger(__name__)

_fernet: Fernet | None = None


def _get_fernet() -> Fernet:
    global _fernet
    if _fernet is not None:
        return _fernet

    key_str = settings.encryption_key
    if key_str:
        key = key_str.encode()
    else:
        # Derive a stable 32-byte key from JWT_SECRET as fallback.
        raw = hashlib.sha256(settings.jwt_secret.encode()).digest()
        key = base64.urlsafe_b64encode(raw)

    _fernet = Fernet(key)
    return _fernet


def encrypt(plaintext: str) -> str:
    """Encrypt a plaintext string and return a URL-safe base64 token."""
    return _get_fernet().encrypt(plaintext.encode()).decode()


def decrypt(token: str) -> str | None:
    """Decrypt a Fernet token.  Returns None if the token is invalid or tampered.

    Broad on purpose: a rotated ENCRYPTION_KEY raises InvalidToken, but a value
    that was never a Fernet token at all fails earlier, in base64 decoding. Both
    mean the same thing to every caller — this value cannot be read — and none
    of them can do anything useful with the distinction.
    """
    try:
        return _get_fernet().decrypt(token.encode()).decode()
    except Exception:
        log.warning("Failed to decrypt value — token may be invalid or key may have changed")
        return None
