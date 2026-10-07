# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Version status — this server, the newest release, and the caller's own app.

Behind the version panels on the phone and the web. `/capabilities` already
carries the server version, but it is public and polled before login, and it
promises never to touch the database or the network (see its docstring). This
does both, so it is a separate, authenticated endpoint:

- it reports the newest GitHub release (app.services.update_check), cached;
- it records the calling app's version from its `X-Tracks-Client` header on
  that device's refresh token, which is how the web can list each signed-in
  phone with the version it runs. The phone calls this on every sync, so the
  list is as fresh as the last sync rather than the last 30-day token refresh.

Nothing here is admin-only: knowing the server is behind is useful to anyone
on it, and the command that updates it is shown by the web to admins alone
because only they can run it — not because it is secret.
"""

import jwt
from fastapi import APIRouter, Depends, Header
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import _BEARER, decode_token, require_auth
from app.database import get_db
from app.models.activity import User
from app.models.refresh_tokens import RefreshToken, clean_client_version
from app.services import update_check
from app.version import API_VERSION, SERVER_VERSION

router = APIRouter(tags=["capabilities"])


class ReleaseOut(BaseModel):
    version: str
    url: str
    published_at: str | None = None


class VersionStatusOut(BaseModel):
    server_version: str
    api_version: int
    # False when the operator set UPDATE_CHECK=false: `latest` is then always
    # null, and a client should say "not checked" rather than "unknown".
    update_check: bool
    latest: ReleaseOut | None
    checked_at: str | None = None
    check_error: str | None = None
    # Computed here so the three clients cannot disagree on what "newer" means.
    server_update_available: bool
    releases_url: str


def _record_client_version(
    db: Session, user: User, credentials: HTTPAuthorizationCredentials | None, header: str | None,
) -> None:
    """Stamp the caller's live refresh token(s) with the version it reports.

    Matched on the access token's `sid`, which is what ties an access token to
    the refresh chain it came from (app.models.refresh_tokens). A browser has
    no refresh token and sends no header, so for it this does nothing.
    """
    version = clean_client_version(header)
    if version is None or credentials is None:
        return
    try:
        sid = decode_token(credentials.credentials).get("sid")
    except jwt.InvalidTokenError:
        return
    if not sid:
        return
    changed = (
        db.query(RefreshToken)
        .filter(
            RefreshToken.user_id == user.id,
            RefreshToken.sid == sid,
            RefreshToken.replaced_at.is_(None),
            RefreshToken.revoked_at.is_(None),
            RefreshToken.client_version.is_distinct_from(version),
        )
        .update({"client_version": version}, synchronize_session=False)
    )
    if changed:
        db.commit()


@router.get("/version", response_model=VersionStatusOut)
def version_status(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
    x_tracks_client: str | None = Header(None),
):
    _record_client_version(db, user, credentials, x_tracks_client)
    check = update_check.latest_release()
    latest = check.get("latest")
    return VersionStatusOut(
        server_version=SERVER_VERSION,
        api_version=API_VERSION,
        update_check=check["enabled"],
        latest=latest,
        checked_at=check.get("checked_at"),
        check_error=check.get("error"),
        server_update_available=update_check.is_newer(
            latest and latest["version"], SERVER_VERSION),
        releases_url=update_check.RELEASES_PAGE,
    )
