# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Supersets: lifts done in turn, a set of each per round, instead of every set
of one before the next.

Squat, rest, dip, rest, row, rest, and round again: the same work and the same
rest as straight sets, with a different lift each time rather than ten
minutes of one. Pairing lifts that share no prime mover keeps each one's
performance (Robbins et al. 2010, J Strength Cond Res: agonist-antagonist
paired sets kept volume load), so the variety costs nothing.

Grouped only where nobody has to cross the room between sets:

- Every pair in a group shares a free weight (dumbbells with dumbbells, one
  barbell), or one of the two needs nothing at all (a bodyweight move can
  join any group). Machines, cables, a pull-up bar or a box are a station
  each, and two stations are never grouped.
- No two lifts in a group work the same primary muscle.
- Heavy work (5 reps or fewer) and jumps stay as straight sets: they want
  full rest and full attention, not a lift in between.
- Two or three to a group.

The output is the builder's own block form, which the watch encoder, the
phone's runners and the web already play: each member carries the same
`group` ({uid, kind: "superset", rounds, rest_seconds}), a `rest` step
follows each member but the last with that member's own rest, and the
group's `rest_seconds` is the last member's. Rounds are the members' most
sets. Ported to the phone in Supersets.kt; the strength-plan fixtures keep
the two identical.
"""
from __future__ import annotations

FREE_WEIGHTS = ("dumbbell", "barbell", "kettlebell", "band")
MAX_GROUP = 3
HEAVY_REPS = 5


def _kit(step: dict, library: dict) -> set[str]:
    ex = library.get(step.get("name"), {})
    return set(ex.get("equipment") or ["bodyweight"]) - {"bodyweight"}


def _compatible(a: dict, b: dict, library: dict) -> bool:
    ka, kb = _kit(a, library), _kit(b, library)
    if not ka or not kb:
        return True
    return any(k in kb for k in ka if k in FREE_WEIGHTS)


def _muscles_clash(a: dict, b: dict) -> bool:
    return bool(set(a.get("primary_muscles") or []) & set(b.get("primary_muscles") or []))


def _eligible(step: dict) -> bool:
    return (step.get("type") == "strength_exercise"
            and step.get("phase", "main") == "main"
            and step.get("movement_pattern") != "plyometric"
            and int(step.get("reps") or 0) > HEAVY_REPS)


def _fits(group: list[dict], step: dict, library: dict) -> bool:
    return all(_compatible(m, step, library) and not _muscles_clash(m, step) for m in group)


def group_supersets(steps: list[dict], library: dict,
                    preferred: list[tuple[int, int]] | None = None) -> list[dict]:
    """
    [steps] with compatible lifts formed into supersets.

    [preferred] are index pairs a coach's blueprint (an archetype) asked to be
    supersetted; they are formed first where the rules allow, then the rest
    are grouped in order, each lift with the next ones that fit.
    """
    taken: set[int] = set()
    groups: list[list[int]] = []

    for i, j in preferred or []:
        if i in taken or j in taken or i >= len(steps) or j >= len(steps):
            continue
        if _eligible(steps[i]) and _eligible(steps[j]) and _fits([steps[i]], steps[j], library):
            groups.append([i, j])
            taken.update((i, j))

    for i, step in enumerate(steps):
        if i in taken or not _eligible(step):
            continue
        group = [i]
        for j in range(i + 1, len(steps)):
            if len(group) >= MAX_GROUP:
                break
            if j in taken or not _eligible(steps[j]):
                continue
            if _fits([steps[k] for k in group], steps[j], library):
                group.append(j)
        if len(group) > 1:
            groups.append(group)
            taken.update(group)

    if not groups:
        return steps

    # Each group is played where its first member was.
    first_of = {min(g): (n, sorted(g)) for n, g in enumerate(sorted(groups, key=min))}
    out: list[dict] = []
    for i, step in enumerate(steps):
        if i in taken and i not in first_of:
            continue
        if i not in first_of:
            out.append(step)
            continue
        n, members = first_of[i]
        rounds = max(int(steps[m].get("sets") or 1) for m in members)
        last = steps[members[-1]]
        group = {
            "uid": f"superset-{n + 1}",
            "kind": "superset",
            "rounds": rounds,
            "rest_seconds": int(last.get("rest_seconds") or 0),
        }
        for k, m in enumerate(members):
            member = dict(steps[m])
            member["group"] = group
            out.append(member)
            if k < len(members) - 1:
                out.append({
                    "type": "rest",
                    "name": "Rest",
                    "duration_seconds": int(member.get("rest_seconds") or 0),
                    "group": group,
                })
    return out
