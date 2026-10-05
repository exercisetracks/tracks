# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""FIT re-parse backfills for lap / strength-set / climb-split sub-resources.

These re-read every stored .fit file and populate sub-resource rows for
activities that have none yet. The lap/set/climb-split variants are identical
apart from the parsed key and target model, so they share `_reparse_backfill`;
climb-grade backfill differs (it updates existing rows) and stays separate.
All are idempotent — activities that already have rows are skipped.
"""

import hashlib
import logging
from pathlib import Path

from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.config import settings as app_settings
from app.database import get_db
from app.models.activity import ClimbSplit, Lap, StrengthSet, User
from app.models.imports import Import
from app.parsers.activity import ActivityParser

log = logging.getLogger(__name__)

router = APIRouter()


def _reparse_backfill(db: Session, *, parsed_key: str, model) -> dict:
    """Re-parse every stored FIT file and bulk-insert `parsed_key` rows for any
    activity that has none of `model` yet. Shared body for the lap/set/climb-split
    backfills (identical apart from the parsed key + target model)."""
    fit_dir = app_settings.fit_files_dir
    parser = ActivityParser()
    processed = skipped = errors = 0

    fit_files = list(Path(fit_dir).glob("*.fit")) if Path(fit_dir).exists() else []
    for fit_path in fit_files:
        try:
            sha256 = hashlib.sha256(fit_path.read_bytes()).hexdigest()
            imp = db.query(Import).filter_by(source="directory", source_id=sha256).first()
            if imp is None or imp.activity_id is None:
                skipped += 1
                continue

            existing = db.query(func.count(model.id)).filter_by(activity_id=imp.activity_id).scalar()
            if existing:
                skipped += 1
                continue

            rows = parser.parse(fit_path).get(parsed_key, [])
            if not rows:
                skipped += 1
                continue

            db.bulk_insert_mappings(model, [
                {"activity_id": imp.activity_id, **row} for row in rows
            ])
            db.commit()
            processed += 1
        except Exception:
            db.rollback()
            log.exception("%s backfill failed for %s", parsed_key, fit_path.name)
            errors += 1

    return {"processed": processed, "skipped": skipped, "errors": errors}


@router.post("/backfill-laps")
def backfill_laps(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Re-parse all stored FIT files to extract and store lap data."""
    return _reparse_backfill(db, parsed_key="laps", model=Lap)


@router.post("/backfill-strength-sets")
def backfill_strength_sets(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Re-parse all stored FIT files to extract and store strength set data."""
    return _reparse_backfill(db, parsed_key="strength_sets", model=StrengthSet)


@router.post("/backfill-climb-splits")
def backfill_climb_splits(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Re-parse all stored FIT files to extract and store climb split data."""
    return _reparse_backfill(db, parsed_key="climb_splits", model=ClimbSplit)


@router.post("/backfill-climb-grades")
def backfill_climb_grades(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Re-parse FIT files to populate grade_level and climb_result on existing climb splits."""
    fit_dir = app_settings.fit_files_dir
    parser = ActivityParser()
    processed = skipped = errors = 0

    fit_files = list(Path(fit_dir).glob("*.fit")) if Path(fit_dir).exists() else []
    for fit_path in fit_files:
        try:
            sha256 = hashlib.sha256(fit_path.read_bytes()).hexdigest()
            imp = db.query(Import).filter_by(source="directory", source_id=sha256).first()
            if imp is None or imp.activity_id is None:
                skipped += 1
                continue

            splits = db.query(ClimbSplit).filter_by(activity_id=imp.activity_id).order_by(ClimbSplit.split_number).all()
            if not splits:
                skipped += 1
                continue

            # Skip if grades already populated
            if any(s.grade_level is not None for s in splits):
                skipped += 1
                continue

            parsed = parser.parse(fit_path)
            parsed_active = [s for s in parsed.get("climb_splits", []) if s.get("split_type") == "climb_active"]
            active_db     = [s for s in splits if s.split_type == "climb_active"]

            if not parsed_active:
                skipped += 1
                continue

            for db_split, p in zip(active_db, parsed_active):
                db_split.grade_level  = p.get("grade_level")
                db_split.climb_result = p.get("climb_result")

            db.commit()
            processed += 1
        except Exception:
            db.rollback()
            log.exception("Climb grade backfill failed for %s", fit_path.name)
            errors += 1

    return {"processed": processed, "skipped": skipped, "errors": errors}
