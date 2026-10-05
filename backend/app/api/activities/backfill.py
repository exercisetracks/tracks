# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Idempotent metric backfill endpoints.

`/activities/backfill-metrics` recomputes EF, aerobic decoupling, effective TSS
(power- or HR-derived, with MTB/indoor multipliers), and the power/pace "best"
curves for every stored activity. Streams activities in batches to bound memory
and is safe to call repeatedly.

`/activities/summaries/backfill` queues the re-read of retained files for the
session fields an older parser missed (feel and perceived effort), which login
also queues.
"""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from sqlalchemy.orm import Session

from app.auth import _BEARER, decode_token, require_auth
from app.calculators.activity_metrics import (
    compute_aerobic_decoupling,
    compute_efficiency_factor,
    compute_power_tss,
)
from app.database import get_db
from app.models.activity import Activity, User
from app.models.user_settings import UserSettings
from app.schemas.activity import BackfillResult
from app.services.crypto_context import require_crypto_session
from app.tasks.imports import backfill_activity_summaries

from .helpers import (
    _effective_ftp,
    _effective_threshold_hr,
    _hr_tss,
    _load_dp_dicts,
    _recompute_bests,
)

import logging

log = logging.getLogger(__name__)

router = APIRouter()


@router.post("/backfill-metrics", response_model=BackfillResult)
def backfill_metrics(user: User = Depends(require_auth), db: Session = Depends(get_db),
                     _key=Depends(require_crypto_session)):
    """
    Recompute EF, aerobic decoupling, effective_tss, and power/pace bests for
    all of `user`'s own activities. Safe to call multiple times — fully idempotent.
    """
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    ftp = _effective_ftp(us)
    threshold_hr = _effective_threshold_hr(us)

    # Stream activities in batches rather than loading all into memory at once.
    # This keeps the working set bounded as the activity count grows.
    activities = (
        db.query(Activity)
        .filter(Activity.user_id == user.id, Activity.started_at.isnot(None))
        .yield_per(200)
    )
    processed = skipped = errors = 0

    for activity in activities:
        try:
            dp_dicts = _load_dp_dicts(db, activity.id)
            if not dp_dicts:
                skipped += 1
                continue

            sport = activity.sport

            activity.efficiency_factor = compute_efficiency_factor(
                sport=sport,
                avg_hr=activity.avg_heart_rate,
                normalized_power=activity.normalized_power,
                avg_speed=activity.avg_speed,
            )
            activity.aerobic_decoupling = compute_aerobic_decoupling(
                sport=sport, data_points=dp_dicts
            )

            if activity.training_stress_score is None:
                power_tss = compute_power_tss(
                    normalized_power=activity.normalized_power,
                    ftp=ftp,
                    duration_seconds=activity.duration_seconds,
                )
                activity.effective_tss = power_tss if power_tss is not None else _hr_tss(activity, threshold_hr)

            # Stored unscaled, so running this twice gives the same answer: the
            # sport multipliers are applied when load is read (see
            # training_load.scale_tss). Scaling the stored value here compounded
            # it on every run.

            _recompute_bests(db, activity, dp_dicts)
            db.commit()
            processed += 1

        except Exception:
            db.rollback()
            log.exception(f"Backfill failed for activity {activity.id}")
            errors += 1

    return BackfillResult(processed=processed, skipped=skipped, errors=errors)


@router.post("/summaries/backfill", status_code=202)
def backfill_summaries(credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER)):
    """Fill feel and perceived effort into activities imported before the
    parser read them (app.services.fit_import.backfill_activity_summaries_for_user).

    Login queues this already; the endpoint is for running it again without
    one, as /health/daily/reparse is for daily metrics. Only null columns are
    filled and nothing else on an activity changes, so it is safe to repeat,
    and a repeat with nothing left to read is one query. Queued rather than
    run inline, because the first run unseals a file per older activity — and
    by session id, never key material, as every such task is.
    """
    if credentials is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    try:
        payload = decode_token(credentials.credentials)
        user_id = int(payload["sub"])
        sid = payload["sid"]
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    backfill_activity_summaries.delay(user_id, sid)
    return {"queued": True}
