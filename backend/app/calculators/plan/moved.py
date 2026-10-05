# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Keeping workouts the user moved where they put them, through a regeneration.

A plan is rebuilt often — on activity import, on a goal edit, on the button —
and every rebuild used to put each session back on the day the generator
chose. Somebody who moved Tuesday's tempo to Thursday because Tuesday is a
work trip found it back on Tuesday the next morning. So a workout the user
dragged (``moved_by_user``) is never deleted or re-dated by a rebuild.

Its *content* is still the plan's, though: as fitness changes, the tempo's
duration and steps should change with it. So each moved workout is matched to
the regenerated session that plays its part that week, takes that session's
content on its own day, and the session is dropped from the day the generator
put it on — otherwise the week holds the tempo twice.

The matching rule
-----------------
For each moved workout, in (date, uid) order:

- Only those dated today or later take part. A missed one in the past keeps
  what it was and claims nothing: a session still to do this week is not the
  one that was skipped.
- Candidates are the regenerated sessions in the same Monday-to-Sunday week as
  the moved workout's (new) date, not already claimed, with the same role
  (``ROLES``: long, easy, quality, strength, mobility, test, race; an
  unknown type is a role of its own).
- The best candidate is, in order: the same ``workout_type``, then the same
  sport, then the nearest date, then the earlier date, then the first built.
- A completed moved workout still claims its counterpart (the week has had
  that session) but keeps its own content: what was done is not rewritten.
- With no candidate, the moved workout stays exactly as it is.

Completed workouts
------------------
A rebuild replaces the unfinished sessions from today on, and keeps what is
already done — rebuilding a run you finished this morning would erase it. A
completed workout (not moved; moved ones are above) dated today or later
claims the regenerated session on *its own day* with the same role, by the
same preference (type, then sport, then first built), so the day does not get
that session a second time; the rest of the day's sessions are still written.
Blocking the whole day instead, as the server once did, dropped the stretch
flow after a finished run. ``keep_completed`` has this rule.

These are pure functions so that the server (api/training_plan/generation.py)
and the phone (com.tracks.core.plan.MovedWorkouts, driven by LocalPlanning)
can be held to one answer by spec/fixtures/moved_workouts.json.
"""

from __future__ import annotations

from datetime import date, timedelta

# The part a session plays in a week. Matching is within a role, so a moved
# long run never takes an interval session's content.
ROLES: dict[str, str] = {
    "long": "long", "long_run": "long",
    "easy": "easy", "easy_spin": "easy", "easy_recovery": "easy", "recovery": "easy",
    "endurance": "easy", "aerobic": "easy", "skills": "easy",
    "tempo": "quality", "threshold": "quality", "intervals": "quality",
    "fartlek": "quality", "race_pace": "quality", "short_quality": "quality",
    "sweet_spot": "quality", "sprint": "quality", "over_unders": "quality",
    "tt_pace": "quality", "sustained_climb": "quality", "descent_repeats": "quality",
    "matchbook": "quality", "quality": "quality",
    "strength": "strength", "custom_strength": "strength",
    "mobility": "mobility", "flexibility": "mobility",
    "race": "race",
    # A triathlon brick's run is its own part of the week: moved, it must not
    # take an easy run's content, nor an easy run the brick's.
    "brick_run": "brick",
}

# What a regenerated session hands to the moved workout. Not the date — that
# is the whole point — and nothing about completion or the watch.
CONTENT_FIELDS = ("sport", "workout_type", "title", "description",
                  "duration_minutes", "distance_meters", "steps")


def role(workout_type: str | None) -> str:
    t = workout_type or ""
    if t.startswith("field_test"):
        return "test"
    return ROLES.get(t, "type:" + t)


def _monday(d: date) -> date:
    return d - timedelta(days=d.weekday())


def keep_moved(generated: list[dict], moved: list[dict],
               today: date) -> tuple[list[dict], dict[str, dict]]:
    """Reconcile a regenerated plan with the workouts the user moved.

    ``generated``: the new plan's workout dicts (``scheduled_date`` a date,
    plus the ``CONTENT_FIELDS``). ``moved``: the moved workouts, each with
    ``uid``, ``scheduled_date``, ``sport``, ``workout_type`` and
    ``is_complete``.

    Returns the generated sessions left to write (claimed ones removed, order
    kept) and, by moved uid, the content to copy onto each moved workout that
    found a counterpart and is not complete.
    """
    claimed: set[int] = set()
    refresh: dict[str, dict] = {}
    for m in sorted(moved, key=lambda m: (m["scheduled_date"], m["uid"])):
        day = m["scheduled_date"]
        if day < today:
            continue
        week = _monday(day)
        want = role(m.get("workout_type"))
        best = None
        for i, g in enumerate(generated):
            if i in claimed or _monday(g["scheduled_date"]) != week:
                continue
            if role(g.get("workout_type")) != want:
                continue
            key = (
                0 if g.get("workout_type") == m.get("workout_type") else 1,
                0 if g.get("sport") == m.get("sport") else 1,
                abs((g["scheduled_date"] - day).days),
                g["scheduled_date"],
                i,
            )
            if best is None or key < best[0]:
                best = (key, i)
        if best is None:
            continue
        claimed.add(best[1])
        if not m.get("is_complete"):
            g = generated[best[1]]
            refresh[m["uid"]] = {f: g.get(f) for f in CONTENT_FIELDS}
    remaining = [g for i, g in enumerate(generated) if i not in claimed]
    return remaining, refresh


def keep_completed(generated: list[dict], completed: list[dict],
                   today: date) -> list[dict]:
    """The generated sessions left to write once each completed workout dated
    today or later has claimed its counterpart on its own day.

    ``completed``: each with ``uid``, ``scheduled_date``, ``sport`` and
    ``workout_type``. Walked in (date, uid) order, like ``keep_moved``, so
    two completed workouts wanting one session resolve the same way on the
    phone. Returns the unclaimed sessions, order kept.
    """
    claimed: set[int] = set()
    for c in sorted(completed, key=lambda c: (c["scheduled_date"], c["uid"])):
        day = c["scheduled_date"]
        if day < today:
            continue
        want = role(c.get("workout_type"))
        best = None
        for i, g in enumerate(generated):
            if i in claimed or g["scheduled_date"] != day:
                continue
            if role(g.get("workout_type")) != want:
                continue
            key = (
                0 if g.get("workout_type") == c.get("workout_type") else 1,
                0 if g.get("sport") == c.get("sport") else 1,
                i,
            )
            if best is None or key < best[0]:
                best = (key, i)
        if best is not None:
            claimed.add(best[1])
    return [g for i, g in enumerate(generated) if i not in claimed]
