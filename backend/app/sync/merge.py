# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The merge rules, as pure functions over plain rows.

Every synced entity merges the same way, so the rules live once, here, with no
database in sight. The Postgres-backed path (app.sync.store) makes the same
decisions by calling [decide_field] and [decide_delete] for each field it
touches, which is what keeps the fixture corpus — replayed against
[apply_change] — meaningful for the real endpoint too.

A row is ``{"fields": {name: value}, "clock": {name: hlc}, "deleted": hlc|None}``.
A field with no clock entry is *absent*, which is not the same as null: null
is a value somebody wrote.

The rules (spec/sync.yaml, and docs/offline-first.md for why):

- A field write applies only if its stamp is strictly greater than the field's
  current stamp. Ties lose, so replaying a push changes nothing.
- A delete wins. It clears the row to a tombstone — old values are irrelevant,
  and keeping them would let replicas disagree about them — and no later write
  un-deletes it. The larger delete stamp is kept, so replicas agree on the
  tombstone itself.
- Deleting a parent deletes its `children_of` rows with the same stamp, and a
  child that arrives while its parent is a tombstone is deleted on arrival
  with the parent's stamp — arrival order is free, so both directions.
- A child's parent field is written once; a different value is refused as
  immutable. A move racing a delete of the old parent cannot converge.
- A log row's fields are written once. An identical replay is merely stale; a
  different value is refused as immutable — a retraction is a delete.
- Readonly entities are written by the server alone.
"""
from __future__ import annotations

from app.spec.sync import ENTITIES
from app.sync.hlc import ClockError, Hlc

APPLIED = "applied"
PARTIAL = "partial"
STALE = "stale"
REJECTED = "rejected"

R_STALE = "stale"
R_DELETED = "deleted"
R_IMMUTABLE = "immutable"
R_UNKNOWN = "unknown"


# Reasons a whole change is rejected (spec/sync.yaml, wire section). Only
# clock_skew is transient: the client keeps the change and retries later.
REJ_READONLY = "readonly"
REJ_UNKNOWN_ENTITY = "unknown_entity"
REJ_CLOCK_SKEW = "clock_skew"
REJ_WIPED = "wiped"
# Not in the contract's list: a change that is malformed (no uid, a stamp
# that does not parse, a value the column cannot hold). Permanent — sending
# it again cannot help — and reported so rather than failing the whole batch,
# which would wedge a phone's outbox behind one bad row.
REJ_INVALID = "invalid"


class Rejected(Exception):
    """The whole change is refused. `reason` is one of the REJ_ codes."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(detail or reason)
        self.reason = reason
        self.detail = detail


def decide_field(kind: str, *, deleted: bool, present: bool, current_value,
                 current_stamp: str | None, value, stamp: str) -> str | None:
    """None if the write applies, else the refusal reason."""
    if deleted:
        return R_DELETED
    if kind == "log" and present:
        return R_STALE if current_value == value else R_IMMUTABLE
    if current_stamp is not None and stamp <= current_stamp:
        return R_STALE
    return None


def decide_delete(current: str | None, stamp: str) -> bool:
    """Whether a delete stamp changes the tombstone."""
    return current is None or stamp > current


def validate_stamp(stamp) -> str:
    try:
        return str(Hlc.parse(stamp))
    except (ClockError, TypeError) as exc:
        raise Rejected(REJ_INVALID, f"bad stamp: {exc}") from exc


def status_of(applied: int, refused: int, attempted: int) -> str:
    """`attempted` counts field writes plus a delete, if the change carried one.

    A change that changed nothing is stale even with nothing refused — an old
    delete stamp arriving at a newer tombstone refuses no field, yet did not
    apply.
    """
    if attempted and not applied:
        return STALE
    if refused:
        return PARTIAL
    return APPLIED


def _key(entity: str, uid: str) -> str:
    return f"{entity}/{uid}"


def _tombstone(stamp: str) -> dict:
    return {"fields": {}, "clock": {}, "deleted": stamp}


def children_of(entity: str) -> list[tuple[str, str]]:
    """(child entity, the field holding the parent's uid) for every child of `entity`."""
    return [
        (name, spec["children_of"]["field"])
        for name, spec in ENTITIES.items()
        if spec.get("children_of") and spec["children_of"]["entity"] == entity
    ]


def parent_link(entity: str) -> tuple[str, str] | None:
    """(parent entity, the field holding its uid), if `entity` is a child."""
    c = ENTITIES[entity].get("children_of")
    return (c["entity"], c["field"]) if c else None


def _cascade(rows: dict, entity: str, uid: str, stamp: str) -> None:
    for child, field in children_of(entity):
        for key, row in list(rows.items()):
            if not key.startswith(child + "/"):
                continue
            if row["fields"].get(field) != uid:
                continue
            child_uid = key.split("/", 1)[1]
            if decide_delete(row["deleted"], stamp):
                rows[key] = _tombstone(stamp)
                _cascade(rows, child, child_uid, stamp)


def apply_change(rows: dict, change: dict, *, from_client: bool = True):
    """Apply one wire change to `rows` (mutated in place).

    Returns (status, refused) where refused maps field name to reason.
    Fields are applied before a delete in the same change, so a change that
    both writes and deletes leaves a tombstone on every replica, whatever order
    the replicas saw it in.
    """
    entity = change.get("entity")
    spec = ENTITIES.get(entity)
    if spec is None:
        raise Rejected(REJ_UNKNOWN_ENTITY, f"unknown entity {entity!r}")
    if spec["kind"] == "readonly" and from_client:
        raise Rejected(REJ_READONLY, f"{entity} is written by the server only")
    uid = change["uid"]
    fields = change.get("fields") or {}
    delete_stamp = change.get("deleted")
    for _name, pair in fields.items():
        validate_stamp(pair[1])
    if delete_stamp is not None:
        validate_stamp(delete_stamp)

    key = _key(entity, uid)
    row = rows.get(key)
    applied = 0
    refused: dict[str, str] = {}
    allowed = set(spec["fields"])
    link = parent_link(entity)

    for name, (value, stamp) in fields.items():
        if name not in allowed:
            refused[name] = R_UNKNOWN
            continue
        if link and name == link[1] and row is not None and row["deleted"] is None \
                and row["fields"].get(name) is not None and value != row["fields"][name]:
            refused[name] = R_IMMUTABLE
            continue
        reason = decide_field(
            spec["kind"],
            deleted=row is not None and row["deleted"] is not None,
            present=row is not None and name in row["clock"],
            current_value=row["fields"].get(name) if row else None,
            current_stamp=row["clock"].get(name) if row else None,
            value=value,
            stamp=stamp,
        )
        if reason:
            refused[name] = reason
            continue
        if row is None:
            row = {"fields": {}, "clock": {}, "deleted": None}
            rows[key] = row
        row["fields"][name] = value
        row["clock"][name] = stamp
        applied += 1

    if delete_stamp is not None:
        current = row["deleted"] if row else None
        if decide_delete(current, delete_stamp):
            rows[key] = _tombstone(delete_stamp)
            _cascade(rows, entity, uid, delete_stamp)
            applied += 1

    # A child written while its parent is a tombstone goes with the parent.
    row = rows.get(key)
    if link and row is not None and row["deleted"] is None:
        parent_uid = row["fields"].get(link[1])
        parent = rows.get(_key(link[0], parent_uid)) if parent_uid is not None else None
        if parent is not None and parent["deleted"] is not None:
            rows[key] = _tombstone(parent["deleted"])
            _cascade(rows, entity, uid, parent["deleted"])

    attempted = len(fields) + (1 if delete_stamp is not None else 0)
    return status_of(applied, len(refused), attempted), refused


# ── Read-time rules ─────────────────────────────────────────────────────────
#
# Invariants that two offline writers can each break without either write
# being wrong. They are resolved when read, never by refusing a write.

def active_goal(goals: dict[str, dict]) -> str | None:
    """Of the live goals marked active, the one activated most recently.

    `goals` maps uid to row. Two phones can each activate a different goal
    offline; both writes stand and this picks one, the same one everywhere.
    """
    best = None
    for uid, row in goals.items():
        if row.get("deleted") or not row["fields"].get("is_active"):
            continue
        stamp = row["clock"].get("is_active") or ""
        if best is None or (stamp, uid) > best:
            best = (stamp, uid)
    return best[1] if best else None


def workout_is_dead(workout: dict, plan: dict | None) -> bool:
    """Whether a generated workout belongs to a superseded plan generation.

    Dead only if its generation differs from its plan's **and** was stamped
    before the plan's current generation was. The second condition is not in
    the plain reading of spec/sync.yaml and is load-bearing: a regeneration's
    workouts can reach a replica before the plan's own `generation` edit does,
    and without it they would be reaped on arrival — a delete that then wins
    everywhere, taking the new plan with it.

    A workout the user moved is never dead: regeneration keeps those
    (calculators/plan/moved.py), and one moved on a phone while another
    regenerated offline would otherwise be reaped here, a delete nobody made.
    At worst the week holds it beside its regenerated twin until the next
    rebuild, which pairs them up.
    """
    if workout.get("deleted") or plan is None or plan.get("deleted"):
        return False
    fields = workout["fields"]
    if fields.get("plan_uid") is None:
        return False  # added by hand; regeneration never touches it
    if fields.get("moved_by_user"):
        return False
    current = plan["fields"].get("generation")
    if current is None or fields.get("generation") == current:
        return False
    plan_stamp = plan["clock"].get("generation") or ""
    own_stamp = workout["clock"].get("generation") or ""
    return own_stamp < plan_stamp


def dead_workouts(rows: dict) -> list[str]:
    """Uids of every dead workout among `rows` (keyed "entity/uid")."""
    plans = {k.split("/", 1)[1]: r for k, r in rows.items() if k.startswith("plan/")}
    dead = []
    for key, row in rows.items():
        if not key.startswith("planned_workout/"):
            continue
        if workout_is_dead(row, plans.get(row["fields"].get("plan_uid"))):
            dead.append(key.split("/", 1)[1])
    return dead
