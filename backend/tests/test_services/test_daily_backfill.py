# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""app.services.fit_import.backfill_daily_health_for_user — intensity minutes
and floors, read back into days whose health files were imported before the
parser knew them. Same pattern as test_summary_backfill.py: `_parse_bytes` is
patched, and an "older" file is one whose Import row lacks the marker."""

from datetime import date, timedelta
from unittest.mock import patch

from app.models.imports import Import
from app.models.metrics import DailyMetric
from app.services import fit_import

from .test_fit_import import _DAILY_PARSED, _key_material, _queue_pending_import


def _day_parsed(**extra):
    day = date.today() - timedelta(days=2)
    return {**_DAILY_PARSED, "date": day, "metrics": {"steps": 1000}, **extra}


def _older_daily(db, user, pubkey, material):
    pending = _queue_pending_import(db, user, pubkey, plaintext=b"older day", filename="older.fit")
    with patch.object(fit_import, "_parse_bytes", return_value=_day_parsed()):
        fit_import.process_pending_imports_for_user(user.id, material)
    db.expire_all()
    imp = db.query(Import).filter_by(user_id=user.id, source_id=pending.content_hash).one()
    imp.extra = {k: v for k, v in imp.extra.items() if k != "daily_fields"}
    db.commit()
    return imp


class TestDailyBackfill:

    def test_an_older_day_gets_its_intensity_minutes_and_floors(self, db, user):
        material, pubkey = _key_material()
        imp = _older_daily(db, user, pubkey, material)
        newer = _day_parsed(
            metrics={"steps": 1000, "floors_climbed": 12},
            moderate_series=[[600, 1], [601, 2]], vigorous_series=[[700, 3]],
        )
        with patch.object(fit_import, "_parse_bytes", return_value=newer):
            result = fit_import.backfill_daily_health_for_user(user.id, material)
        db.expire_all()
        row = db.query(DailyMetric).filter_by(user_id=user.id).one()
        assert (row.intensity_moderate_min, row.intensity_vigorous_min, row.floors_climbed) == (3, 3, 12)
        assert result["written"] == 1
        assert (db.get(Import, imp.id).extra or {}).get("daily_fields") == fit_import.DAILY_FIELDS_VERSION

    def test_a_finished_backfill_reads_nothing(self, db, user):
        material, pubkey = _key_material()
        _older_daily(db, user, pubkey, material)
        fit_import.backfill_daily_health_for_user(user.id, material)
        with patch.object(fit_import, "_parse_bytes") as parse:
            assert fit_import.backfill_daily_health_for_user(user.id, material)["files"] == 0
        parse.assert_not_called()
