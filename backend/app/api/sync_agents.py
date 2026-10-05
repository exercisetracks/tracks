# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Pairing endpoints for sync agents (garmin-sync, a future mobile app) — see
app.services.sync_agent_auth and app.models.sync_agents.SyncAgent.

A user pairs a new agent from the web UI while logged in, gets a one-time
bearer token back, and configures it into the agent (garmin-sync's .env, or
a mobile app's settings). Household agents (shared USB docks) require admin,
since they can act on behalf of any user in the instance once a device is
claimed against them.
"""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, field_validator
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.sync_agents import SyncAgent
from app.services.sync_agent_auth import generate_pairing_token

router = APIRouter(prefix="/sync-agents", tags=["sync-agents"])

_VALID_KINDS = {"garmin-usb", "mobile-app"}


def _require_admin(user: User) -> None:
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="Admin access required")


class CreateSyncAgentRequest(BaseModel):
    kind: str
    label: str
    household: bool = False

    @field_validator("kind")
    @classmethod
    def kind_valid(cls, v: str) -> str:
        if v not in _VALID_KINDS:
            raise ValueError(f"kind must be one of {sorted(_VALID_KINDS)}")
        return v

    @field_validator("label")
    @classmethod
    def label_not_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Label cannot be empty")
        return v


class SyncAgentOut(BaseModel):
    id: int
    kind: str
    label: str
    household: bool
    created_at: datetime | None = None
    last_seen_at: datetime | None = None

    @classmethod
    def from_row(cls, row: SyncAgent) -> "SyncAgentOut":
        return cls(
            id=row.id, kind=row.kind, label=row.label,
            household=row.user_id is None,
            created_at=row.created_at, last_seen_at=row.last_seen_at,
        )


class CreateSyncAgentResponse(SyncAgentOut):
    # Shown exactly once — never retrievable again. See generate_pairing_token.
    token: str


@router.post("/", response_model=CreateSyncAgentResponse, status_code=201)
def create_sync_agent(
    body: CreateSyncAgentRequest,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    if body.household:
        _require_admin(user)

    raw_token, token_hash = generate_pairing_token()
    agent = SyncAgent(
        user_id=None if body.household else user.id,
        created_by_user_id=user.id,
        kind=body.kind,
        label=body.label,
        token_hash=token_hash,
    )
    db.add(agent)
    db.commit()
    db.refresh(agent)

    return CreateSyncAgentResponse(**SyncAgentOut.from_row(agent).model_dump(), token=raw_token)


@router.get("/", response_model=list[SyncAgentOut])
def list_sync_agents(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Household agents are visible to everyone (any user benefits from
    knowing a shared dock exists); personal agents are visible only to their
    owner — except admins, who see everyone's, for account management."""
    agents = db.query(SyncAgent).filter(SyncAgent.revoked_at.is_(None)).order_by(SyncAgent.id).all()
    if user.is_admin:
        visible = agents
    else:
        visible = [a for a in agents if a.user_id is None or a.user_id == user.id]
    return [SyncAgentOut.from_row(a) for a in visible]


@router.delete("/{agent_id}", status_code=204)
def revoke_sync_agent(
    agent_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    agent = db.get(SyncAgent, agent_id)
    if agent is None or agent.revoked_at is not None:
        raise HTTPException(status_code=404, detail="Sync agent not found")

    is_owner = agent.user_id == user.id
    if not (is_owner or user.is_admin):
        raise HTTPException(status_code=403, detail="Not authorized to revoke this sync agent")

    agent.revoked_at = datetime.now(timezone.utc)
    db.commit()
