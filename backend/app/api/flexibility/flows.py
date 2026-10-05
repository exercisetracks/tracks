# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""User flexibility flow CRUD.

A flow is a saved, ordered list of stretches (UserFlexibilityFlow +
FlowStretch rows) that the user can include in their plan and/or sync to the
watch. The static `/flows` collection routes are registered before the dynamic
`/flows/{flow_id}` routes — FastAPI matches in registration order.
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.flexibility import FlowStretch, UserFlexibilityFlow
from app.sync.lists import write_list

router = APIRouter()


def _in_order(items: list[dict]) -> list[dict]:
    """The submitted list, ordered by any `order_index` positions it carries.

    Stable, so a list without positions keeps the order it was sent in.
    """
    return sorted(items, key=lambda s: s.get("order_index") if isinstance(
        s.get("order_index"), int) else 0) if any(
        isinstance(s.get("order_index"), int) and s.get("order_index") for s in items) else list(items)


# Per-item block fields — see spec/sync.yaml, flow_stretch.
BLOCK_FIELDS = ("item_kind", "group_uid", "group_kind", "group_rounds", "group_rest_seconds")


def _stretch_item(s: dict) -> dict:
    """One submitted stretch or block row, as the columns it writes.

    A rest block (item_kind "rest") names no stretch; anything else must.
    """
    if s.get("item_kind") != "rest" and not s.get("exercise_name"):
        raise HTTPException(status_code=422, detail="A stretch needs an exercise_name")
    return {
        "exercise_name": s.get("exercise_name"),
        "sets": s.get("sets"),
        "duration_seconds": s.get("duration_seconds"),
        "rest_seconds": s.get("rest_seconds"),
        "coaching_note": s.get("coaching_note"),
        **{k: s.get(k) for k in BLOCK_FIELDS},
    }


def _flow_to_dict(f: UserFlexibilityFlow, stretches: list[FlowStretch]) -> dict:
    return {
        "id": f.id,
        "name": f.name,
        "description": f.description,
        "include_in_plan": f.include_in_plan,
        "sync_to_watch": f.sync_to_watch,
        "tags": f.tags or [],
        # order_index is the position here, as the web app has always sent and
        # read it; the stored value is a fractional key (app.sync.order).
        "stretches": [
            {
                "exercise_name": s.exercise_name,
                "order_index": i,
                "sets": s.sets,
                "duration_seconds": s.duration_seconds,
                "rest_seconds": s.rest_seconds,
                "coaching_note": s.coaching_note,
                **{k: getattr(s, k) for k in BLOCK_FIELDS},
            }
            for i, s in enumerate(stretches)
        ],
        "created_at": f.created_at.isoformat() if f.created_at else None,
        "updated_at": f.updated_at.isoformat() if f.updated_at else None,
    }


@router.get("/flows")
def get_flows(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    flows = db.query(UserFlexibilityFlow).filter_by(user_id=user.id).order_by(UserFlexibilityFlow.name).all()
    result = []
    for f in flows:
        stretches = (
            db.query(FlowStretch)
            .filter_by(flow_id=f.id)
            .order_by(FlowStretch.order_index)
            .all()
        )
        result.append(_flow_to_dict(f, stretches))
    return result


@router.get("/flows/{flow_id}")
def get_flow(flow_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = db.query(UserFlexibilityFlow).filter_by(id=flow_id, user_id=user.id).first()
    if not f:
        raise HTTPException(status_code=404, detail="Not found")
    stretches = db.query(FlowStretch).filter_by(flow_id=f.id).order_by(FlowStretch.order_index).all()
    return _flow_to_dict(f, stretches)


@router.post("/flows")
def create_flow(body: dict, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = UserFlexibilityFlow(
        user_id=user.id,
        name=body["name"],
        description=body.get("description"),
        include_in_plan=body.get("include_in_plan", True),
        sync_to_watch=body.get("sync_to_watch", True),
        tags=body.get("tags", []),
    )
    db.add(f)
    db.flush()

    write_list(db, [], [
_stretch_item(s)
        for s in _in_order(body.get("stretches", []))
    ], lambda: FlowStretch(flow_id=f.id, user_id=user.id))

    db.commit()
    db.refresh(f)
    stretches = db.query(FlowStretch).filter_by(flow_id=f.id).order_by(FlowStretch.order_index).all()
    return _flow_to_dict(f, stretches)


@router.put("/flows/{flow_id}")
def update_flow(flow_id: int, body: dict, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = db.query(UserFlexibilityFlow).filter_by(id=flow_id, user_id=user.id).first()
    if not f:
        raise HTTPException(status_code=404, detail="Not found")

    for key in ("name", "description", "include_in_plan", "sync_to_watch", "tags"):
        if key in body:
            setattr(f, key, body[key])

    if "stretches" in body:
        existing = (db.query(FlowStretch).filter_by(flow_id=f.id)
                    .order_by(FlowStretch.order_index).all())
        write_list(db, existing, [
_stretch_item(s)
            for s in _in_order(body["stretches"])
        ], lambda: FlowStretch(flow_id=f.id, user_id=user.id))

    db.commit()
    db.refresh(f)
    stretches = db.query(FlowStretch).filter_by(flow_id=f.id).order_by(FlowStretch.order_index).all()
    return _flow_to_dict(f, stretches)


@router.delete("/flows/{flow_id}", status_code=204)
def delete_flow(flow_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = db.query(UserFlexibilityFlow).filter_by(id=flow_id, user_id=user.id).first()
    if not f:
        raise HTTPException(status_code=404, detail="Not found")
    db.delete(f)
    db.commit()
