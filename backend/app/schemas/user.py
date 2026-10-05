# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime
from pydantic import BaseModel, ConfigDict


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    # The account's sync identity — what a phone records to know which account
    # its data belongs to. See User.uid.
    uid: str
    name: str
    username: str | None = None
    is_admin: bool = False
    created_at: datetime | None = None


class CreateUserResponse(UserOut):
    # Shown once, at creation time, to the admin doing the creating (who
    # already knows the account's initial password) — never re-fetchable
    # afterward. The admin should hand it to the new user along with their
    # password; encouraging the new user to change their password (which
    # re-wraps but does not regenerate this recovery key) is what makes the
    # account private from the admin going forward. See user_crypto.
    recovery_key: str
