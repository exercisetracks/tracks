# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Per-user envelope encryption.

Two keys per user:
  - dek:     symmetric AES-256-GCM key. Encrypts sensitive DB columns and
             FIT blobs at rest.
  - privkey: X25519 private key, scoped to ingestion only (not a general
             sharing key). Its public half is not secret — sync agents
             (garmin-sync, a future mobile app) fetch it and seal data for
             this user without ever holding their password.

Both are wrapped by a KEK (key-encryption key) derived from a secret via
Argon2id, and wrapped *twice*, under two independent secrets:
  - password path:  KEK = Argon2id(password, salt)
  - recovery path:  KEK = Argon2id(recovery_key, recovery_salt)
Either unlock path alone recovers both dek and privkey. Losing both the
password and the recovery key means the data is permanently unrecoverable —
there is no server-side escape hatch, by design. That's the whole point.

Nothing here is ever persisted unwrapped. Callers must discard raw key
material as soon as it's no longer needed — see app.services.crypto_context
for how the unwrapped session key is cached (encrypted, TTL-bound) between
requests.
"""

import base64
import binascii
import os
import secrets
from dataclasses import dataclass

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from mnemonic import Mnemonic
from nacl.public import PrivateKey, PublicKey, SealedBox

_NONCE_LEN = 12
_DEK_LEN = 32
_SALT_LEN = 16
# 16 bytes -> a standard 12-word BIP39 phrase (128 bits of entropy). Half the
# raw entropy of the old 32-byte/base32 encoding, deliberately: this only
# ever needs to survive an Argon2id-stretched brute-force attempt (~150-400ms
# per guess — see DEFAULT_KDF_PARAMS), which 128 bits already defeats by an
# absurd margin, and 12 words is a format people have actually seen before
# (crypto wallet backups) and can realistically write down and re-type
# correctly — a 52-character base32 blob is neither.
_RECOVERY_KEY_LEN = 16
_mnemonic = Mnemonic("english")

# Argon2id cost parameters. memory_cost is in KiB. ~150-400ms measured on
# ordinary dev hardware (128 MiB @ time_cost=3, parallelism=4) — deliberately
# expensive (this KDF only runs at login/setup/password-change, never
# per-request) but kept moderate deliberately: homelab operators run this on
# everything from a Raspberry Pi to a beefy NAS, and this also has to survive
# a login storm (e.g. every session re-authenticating after a Redis restart)
# without becoming the bottleneck. Must be run off the event loop by callers
# (see crypto_context). `version` lets the cost be bumped later without
# breaking accounts wrapped under the old params — always read kdf_params
# from the row, never hardcode at the call site.
DEFAULT_KDF_PARAMS = {
    "version": 1,
    "time_cost": 3,
    "memory_cost": 131072,  # 128 MiB
    "parallelism": 4,
}


class WrongSecret(Exception):
    """Password or recovery key didn't unwrap the stored key material
    (wrong secret, or the wrapped blob was corrupted/tampered)."""


@dataclass
class UserKeyMaterial:
    """Unwrapped, in-memory-only key material for one user's session."""
    dek: bytes
    privkey: PrivateKey


@dataclass
class GeneratedUserKeys:
    """Everything to persist in the `user_keys` row, plus the one-time
    recovery key to hand back to the client. The recovery key itself is
    never stored server-side in any form — only what it wraps."""
    public_key: bytes
    salt: bytes
    kdf_params: dict
    wrapped_dek: bytes
    wrapped_privkey: bytes
    recovery_key: bytes
    recovery_salt: bytes
    recovery_wrapped_dek: bytes
    recovery_wrapped_privkey: bytes


def _derive_kek(secret: bytes, salt: bytes, params: dict) -> bytes:
    return hash_secret_raw(
        secret=secret,
        salt=salt,
        time_cost=params["time_cost"],
        memory_cost=params["memory_cost"],
        parallelism=params["parallelism"],
        hash_len=32,
        type=Type.ID,
    )


def _wrap(kek: bytes, plaintext: bytes) -> bytes:
    nonce = os.urandom(_NONCE_LEN)
    return nonce + AESGCM(kek).encrypt(nonce, plaintext, None)


def _unwrap(kek: bytes, wrapped: bytes) -> bytes:
    nonce, ciphertext = wrapped[:_NONCE_LEN], wrapped[_NONCE_LEN:]
    try:
        return AESGCM(kek).decrypt(nonce, ciphertext, None)
    except Exception:
        raise WrongSecret("Could not unwrap key material — wrong password/recovery key, or corrupted data")


def generate_user_keys(password: str) -> GeneratedUserKeys:
    """Called once, at account setup (including admin-initiated creation —
    the admin-supplied initial password works the same way; see the plan's
    note that such an account isn't private from the admin until the user
    changes it). Generates a fresh DEK + X25519 keypair + recovery key, wraps
    the DEK/privkey under both the password and the recovery key."""
    dek = os.urandom(_DEK_LEN)
    keypair = PrivateKey.generate()
    privkey_bytes = bytes(keypair)

    salt = os.urandom(_SALT_LEN)
    kek = _derive_kek(password.encode(), salt, DEFAULT_KDF_PARAMS)

    recovery_key = secrets.token_bytes(_RECOVERY_KEY_LEN)
    recovery_salt = os.urandom(_SALT_LEN)
    recovery_kek = _derive_kek(recovery_key, recovery_salt, DEFAULT_KDF_PARAMS)

    return GeneratedUserKeys(
        public_key=bytes(keypair.public_key),
        salt=salt,
        kdf_params=DEFAULT_KDF_PARAMS,
        wrapped_dek=_wrap(kek, dek),
        wrapped_privkey=_wrap(kek, privkey_bytes),
        recovery_key=recovery_key,
        recovery_salt=recovery_salt,
        recovery_wrapped_dek=_wrap(recovery_kek, dek),
        recovery_wrapped_privkey=_wrap(recovery_kek, privkey_bytes),
    )


def unwrap_with_password(password: str, user_key) -> UserKeyMaterial:
    """`user_key` is a UserKey row (or anything exposing the same
    attributes). Raises WrongSecret on a wrong password."""
    kek = _derive_kek(password.encode(), user_key.salt, user_key.kdf_params)
    dek = _unwrap(kek, user_key.wrapped_dek)
    privkey_bytes = _unwrap(kek, user_key.wrapped_privkey)
    return UserKeyMaterial(dek=dek, privkey=PrivateKey(privkey_bytes))


def unwrap_with_recovery_key(recovery_key: bytes, user_key) -> UserKeyMaterial:
    kek = _derive_kek(recovery_key, user_key.recovery_salt, user_key.kdf_params)
    dek = _unwrap(kek, user_key.recovery_wrapped_dek)
    privkey_bytes = _unwrap(kek, user_key.recovery_wrapped_privkey)
    return UserKeyMaterial(dek=dek, privkey=PrivateKey(privkey_bytes))


# ── Device keys ──────────────────────────────────────────────────────────────
# A third way to unwrap the same DEK, alongside the password and the recovery
# phrase. It exists because the crypto session behind a JWT lapses on its own
# schedule (7 days sliding, and immediately if Redis restarts), and without this
# the only way back is the user typing their password — on a phone, roughly
# weekly, forever. An enrolled device re-unlocks silently instead.
#
# The security trade is explicit: a device secret is equivalent to the password
# for reading data, so it is only as safe as the platform keystore holding it.
# On Android that means hardware-backed storage gated behind device unlock. In
# exchange it is revocable server-side, which a password is not.
#
# Argon2id is deliberately NOT used here. Its cost exists to make guessing a
# low-entropy human secret expensive; a device secret is 32 bytes of CSPRNG
# output, so there is nothing to guess and no reason to burn 150-400 ms of
# server CPU on every cold start. HKDF-SHA256 over the same random salt is the
# right primitive for a high-entropy input.

_DEVICE_SECRET_LEN = 32
_DEVICE_HKDF_INFO = b"tracks-device-key-v1"


def _derive_device_kek(secret: bytes, salt: bytes) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=_DEVICE_HKDF_INFO,
    ).derive(secret)


@dataclass
class GeneratedDeviceKey:
    """What to persist in a `device_keys` row, plus the one-time secret to hand
    to the device. The secret is never stored server-side in any form — only
    what it wraps, exactly like the recovery key."""
    device_secret: bytes
    salt: bytes
    wrapped_dek: bytes
    wrapped_privkey: bytes


def wrap_for_device(material: UserKeyMaterial) -> GeneratedDeviceKey:
    """Wrap the live DEK/privkey under a fresh random device secret.

    Requires already-unwrapped material, so enrolment can only happen from a
    session that could already read the data — a device key never grants access
    that the enroling session did not already have.
    """
    device_secret = secrets.token_bytes(_DEVICE_SECRET_LEN)
    salt = os.urandom(_SALT_LEN)
    kek = _derive_device_kek(device_secret, salt)
    return GeneratedDeviceKey(
        device_secret=device_secret,
        salt=salt,
        wrapped_dek=_wrap(kek, material.dek),
        wrapped_privkey=_wrap(kek, bytes(material.privkey)),
    )


def unwrap_with_device_secret(device_secret: bytes, device_key) -> UserKeyMaterial:
    """`device_key` is a DeviceKey row. Raises WrongSecret on a bad secret."""
    kek = _derive_device_kek(device_secret, device_key.salt)
    dek = _unwrap(kek, device_key.wrapped_dek)
    privkey_bytes = _unwrap(kek, device_key.wrapped_privkey)
    return UserKeyMaterial(dek=dek, privkey=PrivateKey(privkey_bytes))


def format_device_secret(secret: bytes) -> str:
    """URL-safe text for transport. The device stores this verbatim."""
    return base64.urlsafe_b64encode(secret).decode().rstrip("=")


def parse_device_secret(text: str) -> bytes:
    padded = text + "=" * (-len(text) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded)
    except (ValueError, binascii.Error):
        raise WrongSecret("Malformed device secret")
    if len(raw) != _DEVICE_SECRET_LEN:
        raise WrongSecret("Device secret is the wrong length")
    return raw


def rewrap_on_password_change(new_password: str, material: UserKeyMaterial) -> tuple[bytes, bytes, bytes, dict]:
    """Re-wrap the existing DEK+privkey under a new password (fresh salt,
    current KDF params). Returns (salt, wrapped_dek, wrapped_privkey,
    kdf_params) to write back to the user_keys row. The recovery-wrapped
    copies are untouched — the recovery key doesn't change just because the
    password did."""
    salt = os.urandom(_SALT_LEN)
    kek = _derive_kek(new_password.encode(), salt, DEFAULT_KDF_PARAMS)
    wrapped_dek = _wrap(kek, material.dek)
    wrapped_privkey = _wrap(kek, bytes(material.privkey))
    return salt, wrapped_dek, wrapped_privkey, DEFAULT_KDF_PARAMS


def encrypt_bytes(dek: bytes, plaintext: bytes) -> bytes:
    """AES-256-GCM encrypt with the live session DEK. Used by the encrypted
    SQLAlchemy column types and FIT-blob-at-rest storage."""
    return _wrap(dek, plaintext)


def decrypt_bytes(dek: bytes, wrapped: bytes) -> bytes:
    return _unwrap(dek, wrapped)


def seal_for_user(public_key: bytes, plaintext: bytes) -> bytes:
    """Encrypt for a user's public key. Usable by anyone holding only the
    public key — a headless sync agent with no password/session. This is a
    sealed box (anonymous sender): the recipient can't tell who sealed it,
    which is fine here — authenticity of the *agent* comes from its own
    bearer token on the HTTP call, not from the seal itself."""
    return SealedBox(PublicKey(public_key)).encrypt(plaintext)


def unseal(material: UserKeyMaterial, sealed: bytes) -> bytes:
    return SealedBox(material.privkey).decrypt(sealed)


def format_recovery_key(recovery_key: bytes) -> str:
    """A standard BIP39 mnemonic phrase (12 space-separated English words)
    for display/download at setup time — the last word encodes a checksum
    of the rest, so parse_recovery_key can catch most typos/transposed
    words immediately instead of failing with an opaque wrong-key error."""
    return _mnemonic.to_mnemonic(recovery_key)


def parse_recovery_key(text: str) -> bytes:
    """Raises WrongSecret if the phrase doesn't check out — either because
    it's not what was issued, or a transcription error the BIP39 checksum
    catches (wrong word, two words swapped, etc.)."""
    normalized = " ".join(text.strip().lower().split())
    try:
        return bytes(_mnemonic.to_entropy(normalized))
    except Exception:
        raise WrongSecret("Recovery phrase is invalid or was mistyped")
