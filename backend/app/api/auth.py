# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging
import secrets
import time
from datetime import datetime, timezone

import redis
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, field_validator
from sqlalchemy.orm import Session

from app.auth import (
    _BEARER, _TOKEN_EXPIRE_DAYS, create_token, decode_token, hash_password, require_auth,
    verify_password,
)
from app.config import settings
from app.database import get_db
from app.models import refresh_tokens
from app.models.activity import User
from app.models.device_keys import DeviceKey
from app.models.refresh_tokens import RefreshToken
from app.models.user_keys import UserKey
from app.models.user_settings import UserSettings
from app.services import crypto_context, sync_agent_auth, user_crypto
from app.services.crypto_context import require_crypto_session
from app.services.redis_client import get_redis
from app.tasks.imports import backfill_activity_summaries, process_pending_imports

log = logging.getLogger(__name__)
router = APIRouter(prefix="/auth", tags=["auth"])

# ── Brute-force guard ─────────────────────────────────────────────────────────
# Failed login attempts are tracked in Redis, shared by every backend
# worker/replica — a per-process store would only rate-limit whichever
# worker/replica happens to handle a given request, so an attacker spread
# across N of them would get N× the attempts. One sorted set per IP (score =
# attempt timestamp) with a TTL equal to the window: old attempts age out via
# ZREMRANGEBYSCORE on each check, and an IP with no recent activity just
# expires — no separate bookkeeping needed to bound how many IPs are tracked.
#
# Fail-OPEN: any Redis error allows the request rather than blocking. This
# guard is defence-in-depth, never a reason to lock the legitimate (single) user
# out of their own instance.
_MAX_ATTEMPTS = 5
_WINDOW_SECONDS = 900  # 15 minutes


def _client_ip(request: Request) -> str:
    """The address to rate-limit against, honouring settings.trusted_proxy_hops.

    Behind a proxy the socket peer is the proxy, so limiting on it turns a
    per-client counter into a global one: five bad passwords from anywhere lock
    out every client for fifteen minutes. A mobile app retrying a stale token in
    the background can trip that on its own.

    X-Forwarded-For is append-only left-to-right, so the entry our outermost
    trusted proxy added is `trusted_proxy_hops` from the right. Counting from
    the right rather than the left is what makes this safe to trust: a client
    that sends its own X-Forwarded-For only prepends values, which stay left of
    the position we read. If the header is missing or too short to satisfy the
    configured hop count, fall back to the peer address rather than guessing.
    """
    hops = settings.trusted_proxy_hops
    if hops > 0:
        forwarded = request.headers.get("x-forwarded-for", "")
        chain = [part.strip() for part in forwarded.split(",") if part.strip()]
        if len(chain) >= hops:
            return chain[-hops]
    return request.client.host if request.client else "unknown"


def _attempts_key(ip: str) -> str:
    return f"tracks:login_attempts:{ip}"


def _check_rate_limit(ip: str) -> None:
    try:
        r = get_redis()
        key = _attempts_key(ip)
        r.zremrangebyscore(key, 0, time.time() - _WINDOW_SECONDS)
        count = r.zcard(key)
    except redis.RedisError:
        return  # fail open
    if count >= _MAX_ATTEMPTS:
        raise HTTPException(
            status_code=429,
            detail=f"Too many failed login attempts — try again in {_WINDOW_SECONDS // 60} minutes",
        )


def _record_failure(ip: str) -> None:
    try:
        r = get_redis()
        key = _attempts_key(ip)
        now = time.time()
        r.zremrangebyscore(key, 0, now - _WINDOW_SECONDS)
        # Member must be unique per attempt — plain timestamps could collide
        # if two failures land in the same instant.
        r.zadd(key, {f"{now}:{secrets.token_hex(4)}": now})
        r.expire(key, _WINDOW_SECONDS)
    except redis.RedisError:
        pass  # fail open


def _clear_failures(ip: str) -> None:
    try:
        get_redis().delete(_attempts_key(ip))
    except redis.RedisError:
        pass


def _reset_all() -> None:
    """Wipe all tracked attempts (test/maintenance helper)."""
    try:
        r = get_redis()
        keys = list(r.scan_iter(match="tracks:login_attempts:*"))
        if keys:
            r.delete(*keys)
    except redis.RedisError:
        pass


class LoginRequest(BaseModel):
    username: str
    password: str
    # Opt-in so the web app's behaviour is unchanged — a browser has nowhere
    # safe to keep a 90-day credential, and localStorage is not it. Native
    # clients that can use the platform keystore ask for one explicitly.
    issue_refresh_token: bool = False
    device_label: str | None = None


class SetupRequest(BaseModel):
    username: str
    name: str
    password: str
    # Default on — see app.config.Settings.garmin_sync_bootstrap_token and
    # app.services.sync_agent_auth.provision_host_garmin_agent. A no-op if
    # no Garmin device is configured (bootstrap token unset), so this is
    # safe to default True for operators without one too.
    enable_garmin_sync: bool = True

    @field_validator("username")
    @classmethod
    def username_valid(cls, v: str) -> str:
        v = v.strip().lower()
        if not v:
            raise ValueError("Username cannot be empty")
        if len(v) < 3:
            raise ValueError("Username must be at least 3 characters")
        if not all(c.isalnum() or c in ("_", "-") for c in v):
            raise ValueError("Username may only contain letters, numbers, hyphens, and underscores")
        return v

    @field_validator("name")
    @classmethod
    def name_not_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Name cannot be empty")
        return v

    @field_validator("password")
    @classmethod
    def password_min_length(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters")
        return v


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    # Present only when the client asked for one at login, or on /auth/refresh.
    # Every refresh returns a NEW value and invalidates the old one — a client
    # that keeps using the previous token will have its whole session revoked
    # as a suspected replay.
    refresh_token: str | None = None


class RefreshRequest(BaseModel):
    refresh_token: str


class SessionOut(BaseModel):
    id: int
    device_label: str | None
    client_version: str | None = None
    created_at: datetime | None
    last_used_at: datetime | None
    expires_at: datetime | None


class DeviceKeyCreate(BaseModel):
    label: str | None = None


class DeviceKeyOut(BaseModel):
    id: int
    label: str | None
    created_at: datetime | None
    last_used_at: datetime | None


class DeviceKeyCreated(DeviceKeyOut):
    # Returned exactly once, at enrolment. Never stored server-side in any
    # recoverable form — the same contract as the recovery phrase.
    device_secret: str
    created_at: datetime | None = None
    last_used_at: datetime | None = None


class DeviceUnlockRequest(BaseModel):
    device_key_id: int
    device_secret: str
    issue_refresh_token: bool = False


class SetupResponse(TokenResponse):
    # Shown to the client exactly once — never persisted server-side in any
    # recoverable form, never re-fetchable. The client must surface it to the
    # user immediately (download/copy) — losing it (and the password) means
    # permanent data loss, by design. See app.services.user_crypto.
    recovery_key: str


class StatusResponse(BaseModel):
    configured: bool  # True when an admin account with a password exists


@router.get("/status", response_model=StatusResponse)
def status(db: Session = Depends(get_db)):
    """Return whether the app has a configured admin account."""
    admin = db.query(User).filter_by(is_admin=True).first()
    if admin is None:
        return StatusResponse(configured=False)
    us = db.query(UserSettings).filter_by(user_id=admin.id).first()
    return StatusResponse(configured=bool(us and us.password_hash))


def _create_authenticated_token(db: Session, user: User, password: str) -> tuple[str, str]:
    """Common to login() and setup(): mints a JWT carrying a fresh session id
    (`sid`), and seeds the Redis-cached crypto session behind it by
    unwrapping this user's DEK/privkey with the just-verified password. Every
    request bearing this token can decrypt this user's data until that
    cached session's TTL lapses (see app.services.crypto_context).

    Returns (jwt, sid). The caller needs the `sid` to pin a refresh token to
    this same crypto session — see app.models.refresh_tokens."""
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    if uk is None:
        # Every account gets a UserKey row at setup/creation time (stage 1)
        # — reaching this means that invariant broke, not a user error.
        log.error("User %s has a password but no UserKey row", user.id)
        raise HTTPException(status_code=500, detail="Account key material is missing — contact your admin")

    material = user_crypto.unwrap_with_password(password, uk)
    sid = secrets.token_urlsafe(32)
    crypto_context.store_session_key(sid, material, user_id=user.id)

    # Any FIT files a sync agent ingested while this user had no active
    # session are sitting sealed, unprocessed — this is the first moment a
    # key capable of opening them exists. Queued onto the Celery worker
    # (app.tasks.imports) rather than run inline, so login itself doesn't
    # block on however many files are queued — and rather than a bare
    # background thread, so it isn't lost/re-run inconsistently across
    # multiple backend replicas. Passes `sid`, never `material`: the task
    # re-derives the key itself from the same Redis-cached session, so raw
    # DEK/privkey bytes never ride the Celery broker.
    process_pending_imports.delay(user.id, sid)
    # Older activities' feel and effort, from the same sealed files and for the
    # same reason now; a no-op once every file has been read.
    backfill_activity_summaries.delay(user.id, sid)

    return create_token(user.id, sid=sid), sid


@router.post("/login", response_model=TokenResponse)
def login(
    body: LoginRequest,
    request: Request,
    db: Session = Depends(get_db),
    x_tracks_client: str | None = Header(None),
):
    ip = _client_ip(request)
    _check_rate_limit(ip)

    # Collapse all failure cases into a single generic error.
    invalid = HTTPException(status_code=401, detail="Invalid credentials")

    user = db.query(User).filter(
        User.username == body.username.strip().lower()
    ).first()
    if user is None:
        _record_failure(ip)
        raise invalid

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None or us.password_hash is None:
        _record_failure(ip)
        raise invalid

    if not verify_password(body.password, us.password_hash):
        _record_failure(ip)
        raise invalid

    _clear_failures(ip)
    access, sid = _create_authenticated_token(db, user, body.password)

    refresh = None
    if body.issue_refresh_token:
        refresh, _ = refresh_tokens.issue(
            db, user.id, sid, device_label=body.device_label,
            client_version=x_tracks_client,
        )
        db.commit()

    return TokenResponse(access_token=access, refresh_token=refresh)


@router.post("/setup", response_model=SetupResponse)
def setup(body: SetupRequest, db: Session = Depends(get_db)):
    """
    Create the initial admin account. Only works when no admin with a password
    exists — once configured, all new accounts must be created by the admin.
    """
    existing_admin = db.query(User).filter_by(is_admin=True).first()
    if existing_admin is not None:
        us = db.query(UserSettings).filter_by(user_id=existing_admin.id).first()
        if us and us.password_hash:
            raise HTTPException(
                status_code=400,
                detail="Admin account already configured — use /auth/login",
            )

    # Check username uniqueness.
    if db.query(User).filter(User.username == body.username).first():
        raise HTTPException(status_code=409, detail="Username already taken")

    # A user may already exist (created by the file watcher before setup). An
    # admin without a password yet is the one being set up; otherwise the
    # oldest account, deterministically — "first" with no order is whatever
    # row Postgres returns, which is not a choice to make about an account.
    user = existing_admin or db.query(User).order_by(User.id).first()
    if user is not None:
        user.name     = body.name
        user.username = body.username
        user.is_admin = True
    else:
        user = User(name=body.name, username=body.username, is_admin=True)
        db.add(user)
        db.flush()

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        us = UserSettings(user_id=user.id)
        db.add(us)

    us.password_hash = hash_password(body.password)
    us.setup_complete = True

    # A prior, incomplete setup attempt (e.g. a crash between the User row
    # and this point) could leave a UserKey row already in place — replace
    # it rather than conflict on the primary key.
    generated = user_crypto.generate_user_keys(body.password)
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    if uk is None:
        uk = UserKey(user_id=user.id)
        db.add(uk)
    uk.public_key = generated.public_key
    uk.salt = generated.salt
    uk.kdf_params = generated.kdf_params
    uk.wrapped_dek = generated.wrapped_dek
    uk.wrapped_privkey = generated.wrapped_privkey
    uk.recovery_salt = generated.recovery_salt
    uk.recovery_wrapped_dek = generated.recovery_wrapped_dek
    uk.recovery_wrapped_privkey = generated.recovery_wrapped_privkey

    if body.enable_garmin_sync:
        sync_agent_auth.provision_host_garmin_agent(db, user.id)
    else:
        sync_agent_auth.revoke_host_garmin_agent(db, user.id)
    db.commit()

    access, _sid = _create_authenticated_token(db, user, body.password)
    return SetupResponse(
        access_token=access,
        recovery_key=user_crypto.format_recovery_key(generated.recovery_key),
    )


@router.post("/device-keys", response_model=DeviceKeyCreated, status_code=201)
async def enrol_device_key(
    body: DeviceKeyCreate,
    user: User = Depends(require_auth),
    material=Depends(require_crypto_session),
    db: Session = Depends(get_db),
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
):
    """Wrap this user's DEK under a fresh device secret, returned exactly once.

    Requires a live crypto session, not just a token — the key material has to
    be unwrapped to be re-wrapped, so a device key can never grant access the
    enroling session did not already have.

    The secret is the whole credential. It is never stored server-side in any
    recoverable form, exactly like the recovery phrase, so a caller that loses
    it enrols again rather than recovering it.
    """
    generated = user_crypto.wrap_for_device(material)
    row = DeviceKey(
        user_id=user.id,
        label=(body.label or "").strip()[:100] or None,
        salt=generated.salt,
        wrapped_dek=generated.wrapped_dek,
        wrapped_privkey=generated.wrapped_privkey,
    )
    db.add(row)
    db.flush()
    # The enrolling session's own family is this device's: link it, so the
    # device's first device-key unlock ends it rather than leaving it listed
    # (RefreshToken.device_key_id).
    try:
        sid = decode_token(credentials.credentials).get("sid") if credentials else None
    except Exception:
        sid = None
    if sid:
        db.query(RefreshToken).filter(
            RefreshToken.user_id == user.id, RefreshToken.sid == sid,
            RefreshToken.revoked_at.is_(None),
        ).update({RefreshToken.device_key_id: row.id}, synchronize_session=False)
    db.commit()
    db.refresh(row)

    return DeviceKeyCreated(
        id=row.id,
        label=row.label,
        device_secret=user_crypto.format_device_secret(generated.device_secret),
    )


@router.get("/device-keys", response_model=list[DeviceKeyOut])
def list_device_keys(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    rows = (
        db.query(DeviceKey)
        .filter(DeviceKey.user_id == user.id, DeviceKey.revoked_at.is_(None))
        .order_by(DeviceKey.created_at.desc())
        .all()
    )
    return [
        DeviceKeyOut(id=r.id, label=r.label, created_at=r.created_at,
                     last_used_at=r.last_used_at)
        for r in rows
    ]


@router.delete("/device-keys/{key_id}", status_code=204)
def revoke_device_key(
    key_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Stop a device being able to unlock the vault. This is the answer to a
    lost phone, and it is why device keys are worth their risk — a password
    cannot be revoked without changing it everywhere."""
    row = db.query(DeviceKey).filter_by(id=key_id, user_id=user.id).first()
    if row is None or not row.is_active:
        raise HTTPException(status_code=404, detail="Device key not found")
    row.revoked_at = datetime.now(timezone.utc)
    db.commit()


@router.post("/device-unlock", response_model=TokenResponse)
def device_unlock(
    body: DeviceUnlockRequest,
    request: Request,
    db: Session = Depends(get_db),
    x_tracks_client: str | None = Header(None),
):
    """Open a fresh crypto session using an enrolled device secret.

    Equivalent to logging in with a password, minus the password. A client whose
    vault has locked (401 `session_expired`) calls this and retries, so the user
    sees nothing.

    Rate-limited on the same counter as password login: this endpoint accepts a
    credential that decrypts data, so it gets the same brute-force treatment
    even though guessing 32 random bytes is not a realistic attack.
    """
    ip = _client_ip(request)
    _check_rate_limit(ip)

    invalid = HTTPException(status_code=401, detail="Invalid device key")

    row = db.query(DeviceKey).filter_by(id=body.device_key_id).first()
    if row is None or not row.is_active:
        _record_failure(ip)
        raise invalid

    try:
        secret = user_crypto.parse_device_secret(body.device_secret)
        material = user_crypto.unwrap_with_device_secret(secret, row)
    except user_crypto.WrongSecret:
        _record_failure(ip)
        raise invalid

    user = db.get(User, row.user_id)
    if user is None:
        _record_failure(ip)
        raise invalid

    _clear_failures(ip)

    sid = secrets.token_urlsafe(32)
    crypto_context.store_session_key(sid, material, user_id=user.id)
    # Same reasoning as login: this is the first moment a key capable of opening
    # sealed sync-agent blobs exists again.
    process_pending_imports.delay(user.id, sid)
    backfill_activity_summaries.delay(user.id, sid)

    row.last_used_at = datetime.now(timezone.utc)

    refresh = None
    if body.issue_refresh_token:
        # A refresh token is pinned to the sid it was issued under, and this
        # call minted a new one — the caller's existing token now points at a
        # dead session. Issuing a replacement here is what keeps the two
        # mechanisms coherent; the client must store this one.
        # The device's previous family is dead weight now — pinned to a sid
        # that has lapsed, and the client stores this one in its place. Left
        # live, it is a second entry for the same phone in Signed-in devices.
        refresh_tokens.revoke_device_families(db, row.id)
        refresh, _ = refresh_tokens.issue(
            db, user.id, sid, device_label=row.label,
            client_version=x_tracks_client, device_key_id=row.id,
        )
    db.commit()

    return TokenResponse(access_token=create_token(user.id, sid=sid),
                         refresh_token=refresh)


@router.post("/refresh", response_model=TokenResponse)
def refresh(
    body: RefreshRequest,
    db: Session = Depends(get_db),
    x_tracks_client: str | None = Header(None),
):
    """Exchange a refresh token for a fresh access token and a new refresh token.

    Rotation is mandatory: the presented token is consumed. Presenting an
    already-consumed one is treated as a replay and revokes the whole family
    (see app.models.refresh_tokens) — there is no way from here to distinguish
    a stolen token from a duplicated request, and only one of those is safe to
    guess wrong about.

    Note what this does NOT do: it does not unlock the vault. The new access
    token carries the original `sid`, so if that crypto session is still cached
    the client keeps decrypting seamlessly; if it has lapsed, encrypted
    endpoints keep returning `session_expired` until the user enters their
    password. Refreshing an access token cannot re-derive a DEK, because that
    requires the password itself.
    """
    invalid = HTTPException(status_code=401, detail="Invalid or expired refresh token")

    row = (
        db.query(RefreshToken)
        .filter_by(token_hash=refresh_tokens.hash_token(body.refresh_token))
        .first()
    )
    if row is None:
        raise invalid

    if row.replaced_at is not None:
        # Already rotated away, yet here it is again. Assume compromise.
        log.warning(
            "Refresh token reuse detected for user %s (family %s) — revoking family",
            row.user_id, row.family_id,
        )
        refresh_tokens.revoke_family(db, row.family_id)
        db.commit()
        raise invalid

    if not row.is_usable:
        raise invalid

    user = db.get(User, row.user_id)
    if user is None:
        raise invalid

    now = datetime.now(timezone.utc)
    row.replaced_at = now
    row.last_used_at = now
    new_token, _ = refresh_tokens.issue(
        db, row.user_id, row.sid,
        device_label=row.device_label,
        family_id=row.family_id,
        # The app may have been updated since the last rotation; a client
        # that sends nothing keeps what was last recorded.
        client_version=x_tracks_client or row.client_version,
        device_key_id=row.device_key_id,
    )
    db.commit()

    return TokenResponse(
        access_token=create_token(row.user_id, sid=row.sid),
        refresh_token=new_token,
    )


@router.get("/sessions", response_model=list[SessionOut])
def list_sessions(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Devices holding a live refresh token for this account.

    Only the newest token of each family is shown — the rotated-away ones are
    the same device, and listing every rotation would turn "my phone" into a
    scrolling history of itself.
    """
    rows = (
        db.query(RefreshToken)
        .filter(
            RefreshToken.user_id == user.id,
            RefreshToken.revoked_at.is_(None),
            RefreshToken.replaced_at.is_(None),
            RefreshToken.expires_at > datetime.now(timezone.utc),
        )
        .order_by(RefreshToken.created_at.desc())
        .all()
    )
    return [
        SessionOut(
            id=r.id, device_label=r.device_label, client_version=r.client_version,
            created_at=r.created_at,
            last_used_at=r.last_used_at, expires_at=r.expires_at,
        )
        for r in rows
    ]


@router.delete("/sessions/{session_id}", status_code=204)
def revoke_session(
    session_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Sign a device out. Revokes the whole rotation chain, not just the leaf —
    otherwise the device's next refresh would simply mint a replacement.

    Also drops the crypto session the chain was pinned to, which is the half
    that actually protects anything. Revoking refresh tokens alone only stops
    the device minting a *new* access token; the one already in its hands stays
    valid for up to _TOKEN_EXPIRE_DAYS, and while its `sid` was still cached
    that token could keep decrypting health data. A user pressing "sign out" on
    a phone they have lost means "stop reading my data", not "stop renewing".

    And it lists the `sid` as revoked, which is what ends the access token the
    device already holds: on its own, a stateless JWT stays valid until it
    expires and kept reaching every endpoint that touches no encrypted data —
    medications, meals, settings. See app.auth.token_is_live.
    """
    row = (
        db.query(RefreshToken)
        .filter_by(id=session_id, user_id=user.id)
        .first()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    refresh_tokens.revoke_family(db, row.family_id)
    if row.sid:
        refresh_tokens.revoke_sid(db, row.sid, lifetime_days=_TOKEN_EXPIRE_DAYS)
    db.commit()
    if row.sid:
        crypto_context.drop_session_key(row.sid)


@router.post("/logout", status_code=204)
def logout(
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
    db: Session = Depends(get_db),
):
    """Ends this session: drops its cached decryption key from Redis, lists
    its `sid` as revoked so the access token stops authenticating anywhere
    (app.auth.token_is_live), and revokes any refresh chain pinned to it —
    otherwise that chain could mint fresh tokens for the sid once its
    revocation row has aged out. Best-effort: an invalid/missing/
    already-expired token is not an error, there's simply nothing to end."""
    if credentials is None:
        return
    try:
        payload = decode_token(credentials.credentials)
    except Exception:
        return
    sid = payload.get("sid")
    if not sid:
        return
    families = {
        family for (family,) in
        db.query(RefreshToken.family_id).filter(RefreshToken.sid == sid).distinct()
    }
    for family in families:
        refresh_tokens.revoke_family(db, family)
    refresh_tokens.revoke_sid(db, sid, lifetime_days=_TOKEN_EXPIRE_DAYS)
    db.commit()
    crypto_context.drop_session_key(sid)


@router.post("/sync-pending-imports", status_code=202)
def sync_pending_imports(credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER)):
    """Queue processing of this user's not-yet-processed PendingImport rows,
    same as login/setup already do (see _create_authenticated_token) — but
    callable again without a fresh login. A sync agent's ingest isn't tied
    to any particular browser session, so a user who stays logged in while
    their watch syncs would otherwise see nothing new until they log out
    and back in; the frontend polls this periodically while authenticated
    (see AuthContext.jsx) to close that gap. Cheap to poll: just a JWT
    decode plus an enqueue — the task itself no-ops fast when there's
    nothing pending (see app.services.fit_import.process_pending_imports_for_user)."""
    if credentials is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    try:
        payload = decode_token(credentials.credentials)
        user_id = int(payload["sub"])
        sid = payload["sid"]
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    process_pending_imports.delay(user_id, sid)
    return {"queued": True}
