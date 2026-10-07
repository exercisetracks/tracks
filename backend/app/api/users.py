# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, field_validator
from sqlalchemy.orm import Session

from app.calculators.local_day import user_today
from app.auth import _BEARER, create_token, decode_token, hash_password, require_auth, verify_password
from app.calculators.user_stats import recalculate_auto_values
from app.database import get_db
from app.models.activity import User
from app.models.metrics import DailyMetric
from app.models import device_keys, refresh_tokens
from app.models.refresh_tokens import RefreshToken
from app.models.user_keys import UserKey
from app.models.user_settings import UserSettings
from app.schemas.user import CreateUserResponse, UserOut
from app.schemas.user_settings import UserSettingsOut, UserSettingsUpdate
from app.services import crypto_context, user_crypto
from app.services.encryption import encrypt
from app.services.global_dem import start_dem_download  # noqa: F401 — registers "dem" in global_download_tracker
from app.services.global_overview import start_map_download  # noqa: F401 — registers "overview" in global_download_tracker

router = APIRouter(prefix="/users", tags=["users"])


def _require_admin(current_user: User) -> None:
    if not current_user.is_admin:
        raise HTTPException(status_code=403, detail="Admin access required")


class CreateUserRequest(BaseModel):
    username: str
    name: str
    password: str

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


class UserUpdate(BaseModel):
    name: str

    @field_validator("name")
    @classmethod
    def name_not_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Name cannot be empty")
        return v


class PasswordChangeRequest(BaseModel):
    current_password: str
    new_password: str
    # A native client asks for a refresh token here as at login: the change
    # revokes every refresh token, the caller's own included, and without a
    # replacement the phone making the change would be signed out the next
    # time its access token needed renewing.
    issue_refresh_token: bool = False
    device_label: str | None = None

    @field_validator("new_password")
    @classmethod
    def password_min_length(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters")
        return v


@router.get("/me", response_model=UserOut)
def get_me(user: User = Depends(require_auth)):
    return user


@router.patch("/me", response_model=UserOut)
def update_me(
    body: UserUpdate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    user.name = body.name
    db.commit()
    db.refresh(user)
    return user


@router.post("/me/password")
def change_password(
    body: PasswordChangeRequest,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
    x_tracks_client: str | None = Header(None),
):
    us = _get_or_create_settings(db, user.id)
    if us.password_hash is None or not verify_password(body.current_password, us.password_hash):
        # 403, not 401: the caller is authenticated, and a 401 reads to the
        # phone's client as an expired token — it would refresh, retry, and
        # end up reporting the user as signed out over a typo.
        raise HTTPException(status_code=403, detail="Current password is incorrect")
    us.password_hash = hash_password(body.new_password)

    # Re-wrap the existing DEK/privkey under the new password — the DEK
    # itself never changes, only what unlocks it. The recovery-key path is
    # untouched (see user_crypto.rewrap_on_password_change).
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    if uk is not None:
        material = user_crypto.unwrap_with_password(body.current_password, uk)
        salt, wrapped_dek, wrapped_privkey, kdf_params = user_crypto.rewrap_on_password_change(
            body.new_password, material
        )
        uk.salt = salt
        uk.wrapped_dek = wrapped_dek
        uk.wrapped_privkey = wrapped_privkey
        uk.kdf_params = kdf_params
        uk.rewrapped_at = datetime.now(timezone.utc)

    # Changing a password is the universal user action for "I think I am
    # compromised", so it must close the other doors to the same data. The DEK
    # itself does not change here, so an enrolled device key would otherwise
    # keep working forever — a stolen phone surviving a password change would be
    # a poor surprise. Refresh tokens go too, for the same reason.
    revoked_devices = device_keys.revoke_all_for_user(db, user.id)
    revoked_sessions = (
        db.query(RefreshToken)
        .filter(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None))
        .update({RefreshToken.revoked_at: datetime.now(timezone.utc)},
                synchronize_session=False)
    )

    # And every access token already issued, including ones whose sessions
    # have gone idle and so are in no index — see User.tokens_valid_after.
    # The replacement for this session's token, minted below, is newer.
    user.tokens_valid_after = datetime.now(timezone.utc)

    db.commit()

    # And the sessions already open. Revoking refresh tokens stops a device
    # renewing; the decryption key it has cached in Redis would otherwise keep
    # working for as long as it stays in use, because the DEK it holds is the
    # one this change re-wraps rather than replaces. Every session but the one
    # making the change is ended — that one just proved it knows the password.
    try:
        current_sid = decode_token(credentials.credentials).get("sid") if credentials else None
    except Exception:
        current_sid = None
    crypto_context.drop_user_sessions(user.id, keep=current_sid)

    refresh = None
    if body.issue_refresh_token and current_sid:
        refresh, _ = refresh_tokens.issue(
            db, user.id, current_sid, device_label=body.device_label,
            client_version=x_tracks_client,
        )
        db.commit()

    return {
        "ok": True,
        # The cutoff above refused this session's own token too. Its
        # replacement keeps the same `sid`, so the vault stays unlocked; a
        # client that ignores it is simply asked to log in again.
        "access_token": create_token(user.id, sid=current_sid),
        "refresh_token": refresh,
        # Surfaced so the UI can say "your 2 devices were signed out" rather
        # than leaving the user to discover it on their phone later.
        "revoked_device_keys": revoked_devices,
        "revoked_sessions": revoked_sessions,
    }


@router.get("/me/settings", response_model=UserSettingsOut)
def get_settings(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    us = _get_or_create_settings(db, user.id)
    return us


_CLEARABLE_SETTINGS = frozenset({"ai_provider", "ai_model", "ai_endpoint"})


@router.patch("/me/settings", response_model=UserSettingsOut)
def update_settings(
    update: UserSettingsUpdate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    # For the plan rebuild below: injuries are encrypted
    # (crypto_context.optional_crypto_session).
    _key=Depends(crypto_context.optional_crypto_session),
):
    us = _get_or_create_settings(db, user.id)
    data = update.model_dump(exclude_none=True)
    # exclude_none reads a null as "not sent", which is right for nearly every
    # field — but it left AI coaching impossible to turn off: choosing "None"
    # sends ai_provider: null, and that was dropped. These take an explicit
    # null as "clear". The stored key is kept; turning coaching back on with
    # the same provider should not mean pasting it again.
    for field in _CLEARABLE_SETTINGS & update.model_fields_set:
        if getattr(update, field) is None:
            data[field] = None

    # Handle AI API key separately — encrypt before storing, never expose plaintext
    ai_api_key = data.pop("ai_api_key", None)
    if ai_api_key is not None:
        us.ai_api_key_enc = encrypt(ai_api_key)

    # What the plan reads, as it was: a setting written back unchanged (a
    # form saved whole) must not rebuild anything.
    from app.calculators.plan.staleness import setting_stales_plan
    before = {f: getattr(us, f, None) for f in data if setting_stales_plan(f)}
    zone_before = us.timezone
    for field, value in data.items():
        setattr(us, field, value)

    db.commit()
    db.refresh(us)

    # The cached dashboard and load series put each activity on its day in
    # this zone (calculators/local_day.py); a move re-buckets them.
    if us.timezone != zone_before:
        from app.api.metrics.caching import invalidate_dashboard_cache, invalidate_training_load_cache
        invalidate_training_load_cache()
        invalidate_dashboard_cache()

    # Two-way weight sync: when weight is set in Settings, also log it as today's metric
    if "weight_kg" in data and data["weight_kg"] is not None:
        today = user_today(db, user.id)
        existing = db.query(DailyMetric).filter_by(date=today, user_id=user.id).first()
        if existing:
            existing.weight_kg = data["weight_kg"]
        else:
            db.add(DailyMetric(date=today, user_id=user.id, weight_kg=data["weight_kg"]))
        db.commit()

    # Experience level feeds strength difficulty/volume/progression. Accepting
    # a change also clears the pending coach-note suggestion.
    if "strength_experience" in data:
        us.experience_suggestion = None
        us.experience_suggestion_reason = None
        us.experience_suggestion_dismissed = False
        db.commit()

    # Any setting the plan is built from — units (its notes), thresholds and
    # zones (its targets), equipment and experience (its strength), hidden
    # sports (the fitness it builds from) — rebuilds it now: there is no
    # Regenerate button to press (calculators/plan/staleness.py).
    if any(data[f] != v for f, v in before.items()):
        from app.api.training_plan import refresh_plans_for_user_force
        refresh_plans_for_user_force(user.id)

    # Trigger map tile download when the user first enables maps
    if data.get("map_enabled"):
        start_map_download()
        start_dem_download()

    return us


@router.post("/me/settings/recalculate", response_model=UserSettingsOut)
def recalculate_settings(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Re-derive threshold HR and FTP from activity history."""
    recalculate_auto_values(db, user.id)
    return _get_or_create_settings(db, user.id)


def _clear_data_for(db: Session, uid: int) -> None:
    """Delete one account's activity/training data, ingested FIT blobs and
    devices, keeping the account and its settings. Does not commit — shared by
    "clear my data" and the admin wipes, which commit once for every account."""
    from sqlalchemy import text
    from app.models.imports import PendingImport
    from app.models.sync import FitFile
    from app.services import object_storage
    from app.sync import store as sync_store

    # 0. Delete ciphertext blobs before the rows that reference them —
    # unlike the old plaintext-folder-per-user layout, these aren't cleaned
    # up just by wiping a directory.
    for (blob_id,) in db.query(PendingImport.blob_id).filter_by(user_id=uid):
        object_storage.delete_blob(blob_id)
    for (blob_id,) in db.query(FitFile.blob_id).filter(
            FitFile.user_id == uid, FitFile.sealed.is_(False), FitFile.blob_id.isnot(None)):
        object_storage.delete_blob(blob_id)

    # 1. Delete activity data first (removes FK refs to devices)
    for sql in [
        "DELETE FROM activities WHERE user_id = :uid",
        "DELETE FROM daily_metrics WHERE user_id = :uid",
        "DELETE FROM injuries WHERE user_id = :uid",
        "DELETE FROM training_goals WHERE user_id = :uid",
        "DELETE FROM user_ics_tokens WHERE user_id = :uid",
        "DELETE FROM fit_upload_jobs WHERE user_id = :uid",
        "DELETE FROM user_fitness_fingerprints WHERE user_id = :uid",
        "DELETE FROM pending_imports WHERE user_id = :uid",
        "DELETE FROM fit_files WHERE user_id = :uid",
    ]:
        db.execute(text(sql), {"uid": uid})

    # 2. Unclaim all devices, then remove device records with no remaining claims
    db.execute(text("DELETE FROM user_devices WHERE user_id = :uid"), {"uid": uid})
    # Not a device something still points at: another account's activities
    # or days can reference a watch this account no longer claims (a shared
    # family watch), and deleting it under them failed the whole clear.
    db.execute(text(
        "DELETE FROM devices WHERE id NOT IN (SELECT device_id FROM user_devices)"
        " AND id NOT IN (SELECT device_id FROM activities WHERE device_id IS NOT NULL)"
        " AND id NOT IN (SELECT device_id FROM daily_metrics WHERE device_id IS NOT NULL)"
    ))

    # 3. Tell phones their copy is void. The deletes above are raw SQL, so no
    # per-row tombstones were written and a pull would report nothing changed
    # — leaving a phone holding everything the server just dropped, ready to
    # push it all back. Bumping the account epoch makes every phone wipe, and
    # refuses pushes from any that has not yet.
    sync_store.record_wipe(db, uid)


@router.delete("/me/data", status_code=204)
def clear_user_data(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Delete all activity/training data, ingested FIT blobs, and devices for
    the current user — only theirs; the admin wipes below reach everyone's."""
    _clear_data_for(db, user.id)
    db.commit()


@router.get("/", response_model=list[UserOut])
def list_users(
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: list all user accounts."""
    _require_admin(current_user)
    return db.query(User).order_by(User.id).all()


@router.post("/", response_model=CreateUserResponse, status_code=201)
def create_user(
    body: CreateUserRequest,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: create a new user account."""
    _require_admin(current_user)

    if db.query(User).filter(User.username == body.username).first():
        raise HTTPException(status_code=409, detail="Username already taken")

    new_user = User(name=body.name, username=body.username, is_admin=False)
    db.add(new_user)
    db.flush()

    us = UserSettings(user_id=new_user.id)
    us.password_hash = hash_password(body.password)
    db.add(us)

    # The admin chose this initial password, so the admin knows it — this
    # account isn't private from them until the new user changes it
    # themselves (see change_password's re-wrap). Keys still need to be
    # generated now so the account has somewhere to encrypt data from the
    # start rather than being unusable until first login.
    generated = user_crypto.generate_user_keys(body.password)
    db.add(UserKey(
        user_id=new_user.id,
        public_key=generated.public_key,
        salt=generated.salt,
        kdf_params=generated.kdf_params,
        wrapped_dek=generated.wrapped_dek,
        wrapped_privkey=generated.wrapped_privkey,
        recovery_salt=generated.recovery_salt,
        recovery_wrapped_dek=generated.recovery_wrapped_dek,
        recovery_wrapped_privkey=generated.recovery_wrapped_privkey,
    ))

    db.commit()
    db.refresh(new_user)
    return CreateUserResponse(
        **UserOut.model_validate(new_user).model_dump(),
        recovery_key=user_crypto.format_recovery_key(generated.recovery_key),
    )


# ── Admin: other people's passwords, and the instance-wide wipes ─────────────


class AdminPasswordReset(BaseModel):
    new_password: str
    # The user's recovery key (shown once, when their account was made). With
    # it their data survives the reset; without it, it cannot — see below.
    recovery_key: str | None = None
    # Required, and true, when there is no recovery key: the admin has been
    # told the account's data goes with the old password, and agreed.
    discard_data: bool = False

    @field_validator("new_password")
    @classmethod
    def password_min_length(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters")
        return v


def _end_every_session(db: Session, user: User) -> None:
    """Sign an account out everywhere: device keys, refresh tokens, every
    access token already issued, and the cached keys behind open sessions."""
    device_keys.revoke_all_for_user(db, user.id)
    (db.query(RefreshToken)
       .filter(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None))
       .update({RefreshToken.revoked_at: datetime.now(timezone.utc)}, synchronize_session=False))
    user.tokens_valid_after = datetime.now(timezone.utc)


@router.post("/{user_id}/password")
def admin_reset_password(
    user_id: int,
    body: AdminPasswordReset,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: set another account's password.

    ## Why this can cost the account its data

    Each account's data is encrypted under a key that only its own password
    (or its recovery key) unwraps — by design, an admin cannot read anyone
    else's GPS tracks, files or injury notes. So an admin who does not know the
    old password cannot carry that key over to a new one. Two ways out:

    * **With the recovery key**, the key is unwrapped with it and re-wrapped
      under the new password. Nothing is lost.
    * **Without it** (``discard_data``), the account gets new keys, and the
      data under the old ones is deleted: kept, it would be ciphertext nobody
      can ever open, and every page reading it would fail. The account and
      its settings stay. The response carries the new recovery key, to hand
      to the user.

    Either way the account is signed out everywhere, as a self-service change
    signs out every other session. Your own password is changed under
    Security, with your current one.
    """
    _require_admin(current_user)
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="Change your own password with your current one")
    target = db.get(User, user_id)
    if target is None:
        raise HTTPException(status_code=404, detail="User not found")
    us = _get_or_create_settings(db, user_id)
    uk = db.query(UserKey).filter_by(user_id=user_id).first()

    new_recovery = None
    if uk is not None and body.recovery_key:
        try:
            material = user_crypto.unwrap_with_recovery_key(
                user_crypto.parse_recovery_key(body.recovery_key), uk)
        except Exception:
            raise HTTPException(status_code=403, detail="That recovery key does not unlock this account")
        salt, wrapped_dek, wrapped_privkey, kdf_params = user_crypto.rewrap_on_password_change(
            body.new_password, material)
        uk.salt, uk.wrapped_dek, uk.wrapped_privkey, uk.kdf_params = salt, wrapped_dek, wrapped_privkey, kdf_params
        uk.rewrapped_at = datetime.now(timezone.utc)
        kept = True
    elif uk is not None:
        if not body.discard_data:
            raise HTTPException(
                status_code=409,
                detail="Without the user's recovery key their data cannot be kept. "
                       "Confirm that it may be deleted, or enter the recovery key.")
        _clear_data_for(db, user_id)
        generated = user_crypto.generate_user_keys(body.new_password)
        for field in ("public_key", "salt", "kdf_params", "wrapped_dek", "wrapped_privkey",
                      "recovery_salt", "recovery_wrapped_dek", "recovery_wrapped_privkey"):
            setattr(uk, field, getattr(generated, field))
        uk.rewrapped_at = datetime.now(timezone.utc)
        new_recovery = user_crypto.format_recovery_key(generated.recovery_key)
        kept = False
    else:
        kept = True  # no keys yet: nothing encrypted to lose

    us.password_hash = hash_password(body.new_password)
    _end_every_session(db, target)
    db.commit()
    crypto_context.drop_user_sessions(user_id)
    return {"ok": True, "data_kept": kept, "recovery_key": new_recovery}


class WipeConfirm(BaseModel):
    # The phrase the dialog made the admin type, checked here too, so a stray
    # request (or a bug in the page) cannot run a wipe nobody typed.
    confirm: str


_WIPE_PHRASES = {
    "data": "delete everyone's data",
    "accounts": "delete all other accounts",
    "factory": "factory reset",
}


def _check_phrase(body: WipeConfirm, kind: str) -> None:
    if body.confirm.strip().lower() != _WIPE_PHRASES[kind]:
        raise HTTPException(status_code=400, detail=f'Type "{_WIPE_PHRASES[kind]}" to confirm')


@router.post("/admin/wipe-data")
def admin_wipe_all_data(
    body: WipeConfirm,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: every account's data, as "clear my data" does for one.
    Accounts, settings and the instance's map data stay."""
    _require_admin(current_user)
    _check_phrase(body, "data")
    users = db.query(User).all()
    for u in users:
        _clear_data_for(db, u.id)
    db.commit()
    return {"ok": True, "accounts": len(users)}


@router.post("/admin/wipe-accounts")
def admin_wipe_accounts(
    body: WipeConfirm,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: every account's data, and every account but the caller's."""
    _require_admin(current_user)
    _check_phrase(body, "accounts")
    users = db.query(User).all()
    for u in users:
        _clear_data_for(db, u.id)
    others = [u for u in users if u.id != current_user.id]
    for u in others:
        crypto_context.drop_user_sessions(u.id)
        db.delete(u)
    db.commit()
    return {"ok": True, "deleted_accounts": len(others)}


@router.post("/admin/factory-reset")
def admin_factory_reset(
    body: WipeConfirm,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: every account and its data, the caller's included. The
    server is back at first-run setup afterwards — the setup-complete flag is
    cleared, and the next visitor creates the admin. Map tiles are not
    personal and are kept (Settings has its own button for them)."""
    from app.auth import _reset_setup_cache

    _require_admin(current_user)
    _check_phrase(body, "factory")
    users = db.query(User).all()
    for u in users:
        _clear_data_for(db, u.id)
    for u in users:
        crypto_context.drop_user_sessions(u.id)
        db.delete(u)
    db.commit()
    _reset_setup_cache()
    return {"ok": True, "deleted_accounts": len(users)}


@router.delete("/{user_id}", status_code=204)
def delete_user(
    user_id: int,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: delete a user account."""
    _require_admin(current_user)
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="Cannot delete your own account")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    db.delete(user)
    db.commit()


@router.patch("/{user_id}/admin", response_model=UserOut)
def set_admin(
    user_id: int,
    current_user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Admin only: grant or revoke admin status for another user."""
    _require_admin(current_user)
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="Cannot change your own admin status")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    user.is_admin = not user.is_admin
    db.commit()
    db.refresh(user)
    return user


def _get_or_create_settings(db: Session, user_id: int) -> UserSettings:
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    if us is None:
        us = UserSettings(user_id=user_id)
        db.add(us)
        db.commit()
        db.refresh(us)
    return us
