# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The exercise and stretch libraries are spec data the server loads verbatim.

The phone ships the same spec/library files, so anything the server's tables
hold beyond or instead of them would make a plan mean different things on the
two sides.
"""
from app.models.flexibility import StretchLibrary
from app.models.strength import ExerciseLibrary
from app.seed import seed_all, sync_library
from app.spec.library import EXERCISES, STRETCHES
from app.spec.muscle_groups import MUSCLE_LABELS


def _muscles(entries):
    for e in entries:
        yield e["name"], e.get("primary_muscles") or [], e.get("secondary_muscles") or []


def test_seeding_makes_the_tables_hold_exactly_the_spec(db):
    """A row the spec dropped must go, or the server would keep offering it."""
    db.add(ExerciseLibrary(name="Retired Exercise"))
    db.flush()
    seed_all(db.connection())
    names = {n for (n,) in db.query(ExerciseLibrary.name)}
    assert names == {e["name"] for e in EXERCISES}
    assert db.query(StretchLibrary).count() == len(STRETCHES)


def test_reseeding_updates_a_changed_row_in_place(db):
    """Boots are repeated; a corrected entry must replace the old one, not duplicate it."""
    first = dict(EXERCISES[0])
    sync_library(db.connection(), ExerciseLibrary, [dict(first, description="old")])
    sync_library(db.connection(), ExerciseLibrary, [first])
    rows = db.query(ExerciseLibrary).filter_by(name=first["name"]).all()
    assert len(rows) == 1 and rows[0].description == first.get("description")


def test_every_library_muscle_has_a_display_name():
    """Without a label, a raw key like `hip_external_rotators` reaches the screen."""
    missing = {m for _, p, s in _muscles([*EXERCISES, *STRETCHES]) for m in [*p, *s]} - set(MUSCLE_LABELS)
    assert not missing


def test_no_library_entry_lists_a_muscle_twice():
    """The same muscle twice showed as "calves calves" on the web."""
    for name, p, s in _muscles([*EXERCISES, *STRETCHES]):
        assert len(p) == len(set(p)), name
        assert len(s) == len(set(s)), name
        assert not set(p) & set(s), name
