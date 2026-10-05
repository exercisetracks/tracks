# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Startup reference data: the exercise and stretch libraries.

The libraries are spec data (spec/library/*.json, generated into
app.spec.library) and ship inside the phone app too, so the server's copy must
be exactly that data — not a result of its own seed history. Each boot makes
the two tables match the spec: every entry upserted by name, and any row the
spec no longer has removed. Nothing joins to these tables by id, so replacing
rows is invisible to the rest of the schema; they exist only so the library
endpoints can filter and search in SQL.

This used to be a chain of SQL fixtures and idempotent corrections (seed, then
expansions, Garmin mappings, animation backfills), plus two backfills that
rewrote planned workouts and custom exercises in bulk. Those are gone on
purpose: the corrections are baked into the spec, and a raw bulk UPDATE of a
synced table bypasses sync stamping, so phones would never hear of it.

``seed_all`` is the only name imported elsewhere (``app.main``).
"""
import logging

from sqlalchemy import delete, inspect
from sqlalchemy.dialects.postgresql import insert

from app.models.flexibility import StretchLibrary
from app.models.strength import ExerciseLibrary
from app.spec.library import EXERCISES, STRETCHES

logger = logging.getLogger(__name__)

__all__ = ["seed_all", "sync_library"]


def sync_library(conn, model, entries: list[dict]) -> None:
    """Make `model`'s table hold exactly `entries`, keyed by name."""
    columns = {c.key for c in inspect(model).columns} - {"id", "created_at"}
    unknown = {k for e in entries for k in e} - columns
    if unknown:
        # A spec field with no column would be dropped silently; fail loudly so
        # the model and the spec are brought back into step instead.
        raise RuntimeError(f"{model.__tablename__}: spec fields with no column: {sorted(unknown)}")
    names = [e["name"] for e in entries]
    for e in entries:
        stmt = insert(model).values(**e)
        stmt = stmt.on_conflict_do_update(
            index_elements=["name"],
            set_={k: stmt.excluded[k] for k in e if k != "name"},
        )
        conn.execute(stmt)
    conn.execute(delete(model).where(model.name.notin_(names)))


def seed_all(conn) -> None:
    sync_library(conn, ExerciseLibrary, EXERCISES)
    sync_library(conn, StretchLibrary, STRETCHES)
    conn.commit()
    logger.info("libraries: %d exercises, %d stretches", len(EXERCISES), len(STRETCHES))
