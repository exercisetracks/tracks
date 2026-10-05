# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Track/activity merge — `POST /courses/merge`.

Concatenates several tracks/activities (in order) into one new Custom Track, and
optionally a non-contributing "merged trip" Activity. Kept whole because it's one
cohesive routine: gather sources → build geometry → create track → create the
isolated activity → hide sources.
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, User
from app.models.custom_track import CustomTrack
from app.services.crypto_context import require_crypto_session

from .helpers import _MAX_NAME, _activity_coords, _apply_stats, _detail, _owned_or_404, _random_color

router = APIRouter()


class MergeSource(BaseModel):
    kind: str            # "track" | "activity"
    id: int
    reverse: bool = False


class CourseMerge(BaseModel):
    name: str | None = None
    color: str | None = None
    sport: str | None = None
    sources: list[MergeSource]
    create_activity: bool = True   # also produce a non-contributing "merged trip" activity
    hide_sources: bool = True      # hide source tracks (kept, just removed from the map)


@router.post("/courses/merge", status_code=201)
def merge_courses(body: CourseMerge, user: User = Depends(require_auth),
                  db: Session = Depends(get_db),
                  _key=Depends(require_crypto_session)):
    """Concatenate several tracks/activities (in order) into one new Custom Track.

    Optionally also creates a "merged trip" Activity carrying only the combined
    totals — it has device_id=NULL and is_merged=True so it shows in the activity
    list but is excluded from every training metric. Source tracks are hidden
    (not deleted) so the originals remain available.
    """
    if not body.sources or len(body.sources) < 2:
        raise HTTPException(400, "Merging needs at least 2 sources")

    merged: list[list[float]] = []
    src_tracks: list[CustomTrack] = []
    src_activity_ids: list[int] = []
    starts: list[datetime] = []
    total_duration = 0
    fallback_sport = None

    for s in body.sources:
        if s.kind == "track":
            t = _owned_or_404(s.id, user, db)
            coords = [list(c) for c in (t.geometry or [])]
            src_tracks.append(t)
            fallback_sport = fallback_sport or t.sport
        elif s.kind == "activity":
            act = db.get(Activity, s.id)
            if act is None or (act.user_id is not None and act.user_id != user.id):
                raise HTTPException(404, f"Activity {s.id} not found")
            coords = _activity_coords(s.id, db)
            src_activity_ids.append(s.id)
            if act.started_at:
                starts.append(act.started_at)
            if act.duration_seconds:
                total_duration += int(act.duration_seconds)
            fallback_sport = fallback_sport or (act.sport or "").lower() or None
        else:
            raise HTTPException(400, f"Unknown source kind: {s.kind}")

        if s.reverse:
            coords = list(reversed(coords))
        merged.extend(coords)

    if len([c for c in merged if c and c[0] is not None and c[1] is not None]) < 2:
        raise HTTPException(400, "Sources have no usable geometry to merge")

    track = CustomTrack(
        user_id=user.id,
        name=(body.name or "Merged Track").strip()[:_MAX_NAME] or "Merged Track",
        color=body.color or _random_color(),
        sport=(body.sport or fallback_sport or "hiking").lower(),
        source="merged",
    )
    _apply_stats(track, merged)
    db.add(track)
    db.flush()   # need track.id for the activity link

    activity_id = None
    if body.create_activity:
        merged_act = Activity(
            user_id=user.id,
            device_id=None,                 # ← structural metric isolation
            is_merged=True,
            name=track.name,
            sport=track.sport,
            started_at=min(starts) if starts else datetime.now(timezone.utc),
            duration_seconds=total_duration or None,
            distance_meters=track.distance_m,
            total_ascent=track.ascent_m,
            total_descent=track.descent_m,
            extra={"custom_track_id": track.id,
                   "source_activity_ids": src_activity_ids,
                   "source_track_ids": [t.id for t in src_tracks]},
        )
        db.add(merged_act)
        db.flush()
        activity_id = merged_act.id

    if body.hide_sources:
        for t in src_tracks:
            t.hidden = True

    db.commit()
    db.refresh(track)
    return {"track": _detail(track), "activity_id": activity_id}
