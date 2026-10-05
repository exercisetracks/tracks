# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, LargeBinary, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base, PJson


class UserKey(Base):
    """
    Per-user key material for envelope encryption at rest.

    Two keys are wrapped independently by two different KEKs (key-encryption
    keys), so either path alone is enough to recover both:
      - password path:  KEK = Argon2id(password, salt, kdf_params)
      - recovery path:  KEK = Argon2id(recovery_key, recovery_salt, kdf_params)

    What's wrapped:
      - dek:     symmetric AES-256-GCM key, encrypts sensitive DB columns / blobs.
      - privkey: X25519 private key. public_key (not secret) lets sync agents
                 (garmin-sync, future mobile app) seal data for this user
                 without ever holding the password. Scoped to ingestion only —
                 not a general-purpose sharing key.

    See app.services.user_crypto for the wrap/unwrap implementation.
    """

    __tablename__ = "user_keys"

    user_id      = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)

    public_key   = Column(LargeBinary, nullable=False)

    salt              = Column(LargeBinary, nullable=False)
    kdf_params        = Column(PJson, nullable=False)  # {"version": int, "time_cost", "memory_cost", "parallelism"}
    wrapped_dek       = Column(LargeBinary, nullable=False)      # nonce || ciphertext || tag
    wrapped_privkey   = Column(LargeBinary, nullable=False)      # nonce || ciphertext || tag

    recovery_salt            = Column(LargeBinary, nullable=False)
    recovery_wrapped_dek     = Column(LargeBinary, nullable=False)
    recovery_wrapped_privkey = Column(LargeBinary, nullable=False)

    created_at   = Column(DateTime(timezone=True), server_default=func.now())
    rewrapped_at = Column(DateTime(timezone=True), nullable=True)
