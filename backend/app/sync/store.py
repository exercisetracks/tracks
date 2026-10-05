# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Where sync meets the database: stamping, sequencing, tombstones, push, pull.

## Every write participates, without asking

The web app is a REST client that knows nothing about clocks, and should not
have to. A session event does it: any ORM write to a synced field is stamped
with the server's clock, every deleted synced row leaves a tombstone, and the
row is queued for a sequence number. So an endpoint written before sync
existed — or tomorrow by someone who has not read this — still produces edits
that merge correctly with a phone's.

Two things are deliberately *not* stamped, via [derived]:

- What the importer writes. An activity's name and sport come from its FIT
  file, which every device parses for itself; stamping them would let a
  server import outrank a rename the user made on the phone before uploading.
- Automatic workout matching, for the same reason: it is derived everywhere.

Bulk ORM UPDATEs bypass per-object events. One that touches a synced field
outside [derived] raises, loudly, rather than silently not syncing; bulk
DELETEs are handled here (tombstones for every matched row).

## Why sequence numbers are assigned at commit

`server_seq` orders rows by *arrival* for the pull cursor. Assigned at write
time, it would be wrong: transaction A takes 10, B takes 11 and commits first,
a phone pulls and stores cursor 11, then A commits — and row 10 is below the
cursor forever. So stamping marks rows pending (-1), and at commit, under one
advisory lock, pending rows take the next numbers. Commits that stamp are
serialised only for the length of that UPDATE, and a cursor can never pass a
row that was not yet visible.
"""
from __future__ import annotations

import logging
from contextlib import contextmanager

from sqlalchemy import Update, Delete, event, func, inspect, select, text  # noqa: F401
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session

from app.models.activity import User
from app.models.sync import SEQ_PENDING, SyncNode, SyncPendingRow, SyncTombstone
from app.models.user_settings import UserSettings
from app.spec.sync import ENTITIES, PULL_DEFAULT_LIMIT, PULL_MAX_LIMIT
from app.sync import merge, uids
from app.sync.hlc import Clock, ClockError, Hlc, SkewRefused, new_node
from app.sync.registry import (
    ADAPTERS, SYNCED_MODELS, Adapter, NotYet, adapter_for,
)

log = logging.getLogger(__name__)

_APPLYING = "sync.applying"
_DERIVED = "sync.derived"
_NO_TOMBSTONES = "sync.no_tombstones"
_TOUCHED = "sync.touched"

# 'trks' — serialises the commit-time sequence assignment across workers.
_SEQ_LOCK = 0x74726B73


# ── The server's clock ──────────────────────────────────────────────────────

_clock: Clock | None = None


def server_clock(db: Session) -> Clock:
    """This server's clock, with its node id loaded from (or written to) the DB once."""
    global _clock
    if _clock is None:
        # One statement that always returns the row. Insert-if-absent then a
        # separate select can come back empty, and a clock with no node mints
        # stamps that sort wrongly everywhere.
        t = SyncNode.__table__
        stmt = pg_insert(t).values(id=1, node=new_node())
        stmt = stmt.on_conflict_do_update(index_elements=["id"], set_={"node": t.c.node})
        node = db.connection().execute(stmt.returning(t.c.node)).scalar()
        if not node:
            raise RuntimeError("sync_node has no node id")
        _clock = Clock(node)
    return _clock


def _reset_clock_for_tests() -> None:
    global _clock
    _clock = None


# ── Modes ────────────────────────────────────────────────────────────────────

@contextmanager
def _flag(db: Session, key: str):
    # Flush first: writes made before the block must be judged by the rules
    # outside it. Otherwise an edit still pending when a derived() block opens
    # would be flushed inside it, unstamped, and never reach a phone.
    db.flush()
    db.info[key] = db.info.get(key, 0) + 1
    try:
        yield db
    finally:
        db.info[key] -= 1


def derived(db: Session):
    """Writes inside are derived data: assign uids, stamp nothing."""
    return _flag(db, _DERIVED)


def no_tombstones(db: Session):
    """Deletes inside leave no tombstone — for a wipe that resets clients
    outright, or a server-side re-parse of an activity every phone still has."""
    return _flag(db, _NO_TOMBSTONES)


def _applying(db: Session):
    return _flag(db, _APPLYING)


def _on(db: Session, key: str) -> bool:
    return db.info.get(key, 0) > 0


def _touch(db: Session, table: str) -> None:
    db.info.setdefault(_TOUCHED, set()).add(table)


# ── Stamping ─────────────────────────────────────────────────────────────────

def stamp_fields(db: Session, obj, fields: list[str], adapter: Adapter | None = None) -> None:
    """Stamp `fields` of `obj` as written now, by this server."""
    adapter = adapter or adapter_for(obj)
    if adapter is None or not fields:
        return
    clock = server_clock(db)
    stamps = dict(obj.clock or {})
    if adapter.entity == "device" and "serial_number" not in stamps:
        # A watch's identity lives on the shared `devices` row, not the claim,
        # so no column change on the claim ever names it. Without this, the
        # first edit to a claim (a label) would reach phones as a device with
        # no serial — a row nothing can match to a watch.
        fields = list(dict.fromkeys([*fields, *adapter.fields]))
    for f in fields:
        stamps[f] = str(clock.stamp_after(stamps.get(f)))
    # Reassigned, not mutated: a JSON column does not track in-place edits.
    obj.clock = stamps
    obj.server_seq = SEQ_PENDING
    _touch(db, adapter.table.name)


def _changed_columns(obj, new: bool) -> list[str]:
    """Columns this flush writes.

    For a new row that includes columns left to a non-null default: a default
    is a value the server chose, and a phone must receive it (a generated
    workout's `origin`, an unticked `is_complete`). Columns left NULL are not
    written — stamping them would let a server-side create erase a value a
    phone wrote offline to the same natural-key row, like a day's hydration.
    """
    state = inspect(obj)
    out = []
    for attr in state.mapper.column_attrs:
        hist = state.attrs[attr.key].history
        if new:
            col = attr.columns[0]
            has_default = (col.default is not None and getattr(col.default, "arg", None) is not None) \
                or col.server_default is not None
            if hist.added or has_default:
                out.append(attr.key)
        elif hist.has_changes():
            out.append(attr.key)
    return out


def _ensure_identity(db: Session, obj, adapter: Adapter) -> None:
    conn = db.connection()
    if getattr(obj, "user_id", None) is None:
        _inherit_user(conn, obj, adapter)
    if not getattr(obj, "uid", None):
        obj.uid = adapter.natural_uid(db, obj) or uids.uuid7()


def _inherit_user(conn, obj, adapter: Adapter) -> None:
    """Children carry their owner's user_id; fill it from the parent row."""
    parent = adapter.spec.get("children_of")
    if not parent:
        return
    field = parent["field"]
    fk = adapter.columns[field]
    parent_model = ADAPTERS[parent["entity"]].model
    parent_id = getattr(obj, fk)
    if parent_id is not None:
        obj.user_id = conn.execute(
            select(parent_model.user_id).where(parent_model.id == parent_id)
        ).scalar()


@event.listens_for(Session, "before_flush")
def _before_flush(db: Session, _ctx, _instances):
    applying = _on(db, _APPLYING)
    derived_mode = _on(db, _DERIVED)

    for obj in list(db.new):
        adapter = adapter_for(obj)
        if adapter is None:
            continue
        _ensure_identity(db, obj, adapter)
        if applying or derived_mode or adapter.entity == "activity":
            # An activity is born from its file, on every device alike; only
            # what someone later says about it is an edit.
            continue
        fields = [f for c in _changed_columns(obj, new=True) for f in adapter.fields_for_column(c)]
        stamp_fields(db, obj, fields, adapter)

    for obj in list(db.dirty):
        if isinstance(obj, User):
            if not (applying or derived_mode) and "name" in _changed_columns(obj, new=False):
                settings = db.execute(
                    select(UserSettings).where(UserSettings.user_id == obj.id)
                ).scalars().first()
                if settings is not None:
                    stamp_fields(db, settings, ["name"], ADAPTERS["settings"])
            continue
        adapter = adapter_for(obj)
        if adapter is None or not db.is_modified(obj, include_collections=False):
            continue
        if not getattr(obj, "uid", None):
            _ensure_identity(db, obj, adapter)
        if applying or derived_mode:
            continue
        fields = [f for c in _changed_columns(obj, new=False) for f in adapter.fields_for_column(c)]
        stamp_fields(db, obj, fields, adapter)

    if applying or _on(db, _NO_TOMBSTONES):
        return
    for obj in list(db.deleted):
        adapter = adapter_for(obj)
        if adapter is None or not getattr(obj, "uid", None):
            continue
        stamp = str(server_clock(db).tick())
        write_tombstone(db, obj.user_id, adapter.entity, obj.uid, stamp)
        _cascade_tombstones(db, adapter, [_pk(obj)], stamp)


def _pk(obj):
    return getattr(obj, "id", None)


# ── Tombstones ───────────────────────────────────────────────────────────────

def write_tombstone(db: Session, user_id: int, entity: str, uid: str, stamp: str) -> None:
    """Record a deletion; the later of two delete stamps is kept."""
    t = SyncTombstone.__table__
    stmt = pg_insert(t).values(
        user_id=user_id, entity=entity, uid=uid, deleted=stamp, server_seq=SEQ_PENDING,
    )
    stmt = stmt.on_conflict_do_update(
        index_elements=["user_id", "entity", "uid"],
        set_={"deleted": func.greatest(t.c.deleted, stmt.excluded.deleted),
              "server_seq": SEQ_PENDING},
        where=t.c.deleted < stmt.excluded.deleted,
    )
    db.connection().execute(stmt)
    _touch(db, "sync_tombstones")
    db.connection().execute(
        SyncPendingRow.__table__.delete().where(
            SyncPendingRow.user_id == user_id,
            SyncPendingRow.entity == entity,
            SyncPendingRow.uid == uid,
        )
    )


def _cascade_tombstones(db: Session, adapter: Adapter, ids: list, stamp: str) -> None:
    """Tombstone every synced row the database will cascade-delete with these.

    Postgres removes `ON DELETE CASCADE` children itself, invisibly to the
    ORM. Without this a phone would keep a deleted flow's stretches forever.
    """
    ids = [i for i in ids if i is not None]
    if not ids:
        return
    conn = db.connection()
    for child in ADAPTERS.values():
        if child.table is adapter.table:
            continue
        for fk in child.table.foreign_keys:
            if fk.column.table is not adapter.table or fk.ondelete != "CASCADE":
                continue
            if "id" not in child.table.c:
                continue
            rows = conn.execute(
                select(child.table.c.id, child.table.c.uid, child.table.c.user_id)
                .where(fk.parent.in_(ids))
            ).all()
            for row in rows:
                write_tombstone(db, row.user_id, child.entity, row.uid, stamp)
            _cascade_tombstones(db, child, [r.id for r in rows], stamp)


@event.listens_for(Session, "do_orm_execute")
def _bulk_statements(state):
    stmt = state.statement
    if not isinstance(stmt, (Update, Delete)):
        return
    # An ORM statement (`db.query(Model).delete()`, `update(Model)`) carries an
    # annotated copy of the table, never the Table itself; compared by
    # identity as it was, nothing matched, and every bulk delete of a synced
    # row went untombstoned — a plan rebuild's replaced workouts among them.
    table = stmt.table._deannotate()
    adapters = [a for a in ADAPTERS.values() if a.table is table]
    if not adapters:
        return
    db = state.session
    if isinstance(stmt, Update):
        if _on(db, _DERIVED) or _on(db, _APPLYING):
            return
        keys = {getattr(c, "key", str(c)) for c in stmt._values} if stmt._values else set()
        synced = {c for a in adapters for c in a.columns.values()}
        touched = {k for k in keys if k in synced}
        if touched:
            raise RuntimeError(
                f"bulk UPDATE of {table.name}.{sorted(touched)} bypasses sync stamping; "
                "update the rows through the ORM, or wrap derived writes in sync.store.derived()"
            )
        return
    if _on(db, _APPLYING) or _on(db, _NO_TOMBSTONES):
        return
    where = stmt.whereclause
    q = select(table.c.id if "id" in table.c else table.c.uid,
               table.c.uid, table.c.user_id)
    if where is not None:
        q = q.where(where)
    rows = db.connection().execute(q).all()
    if not rows:
        return
    stamp = str(server_clock(db).tick())
    for row in rows:
        adapter = adapters[0]
        if len(adapters) > 1:  # activities: activity vs trip
            is_merged = db.connection().execute(
                select(table.c.is_merged).where(table.c.uid == row.uid,
                                                table.c.user_id == row.user_id)
            ).scalar()
            adapter = next(a for a in adapters if (a.entity == "trip") == bool(is_merged))
        write_tombstone(db, row.user_id, adapter.entity, row.uid, stamp)
    _cascade_tombstones(db, adapters[0], [r[0] for r in rows], stamp)


# ── Sequencing at commit ─────────────────────────────────────────────────────

@event.listens_for(Session, "before_commit")
def _assign_sequence(db: Session):
    # Commit flushes only after this hook runs, and that final flush may be
    # the one that stamps; so flush first, then look.
    db.flush()
    if not db.info.get(_TOUCHED):
        return
    conn = db.connection()
    conn.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _SEQ_LOCK})
    for table in sorted(db.info.get(_TOUCHED, ())):
        conn.execute(text(
            f'UPDATE "{table}" SET server_seq = nextval(\'sync_seq\') '
            f"WHERE server_seq = {SEQ_PENDING}"
        ))
    db.info[_TOUCHED] = set()


@event.listens_for(Session, "after_rollback")
def _forget(db: Session):
    db.info.pop(_TOUCHED, None)


# ── Push ─────────────────────────────────────────────────────────────────────

class _Outcome:
    def __init__(self, change):
        self.entity = change.get("entity")
        self.uid = change.get("uid")
        self.status = merge.APPLIED
        self.refused: dict[str, str] = {}
        self.reason: str | None = None
        self.detail: str | None = None

    def out(self):
        d = {"entity": self.entity, "uid": self.uid, "status": self.status,
             "refused": self.refused}
        if self.detail:
            d["detail"] = self.detail
        if self.reason:
            d["reason"] = self.reason
        return d


def account_epoch(db: Session, user_id: int) -> int:
    return db.execute(select(User.sync_epoch).where(User.id == user_id)).scalar() or 0


def push(db: Session, user_id: int, body: dict) -> dict:
    """Apply a phone's changes; the whole batch commits or none of it does.

    `body["epoch"]` is the account epoch the phone last pulled (absent means 0,
    what every account starts at). Older than the account's means the user
    deleted their data since: every change is rejected "wiped", permanently,
    because applying it would quietly undo the deletion.
    """
    clock = server_clock(db)
    epoch = account_epoch(db, user_id)
    try:
        client_epoch = int(body.get("epoch") or 0)
    except (TypeError, ValueError):
        client_epoch = 0
    if client_epoch < epoch:
        return {"clock": str(clock.current()), "results": [
            {"entity": c.get("entity"), "uid": c.get("uid"), "status": merge.REJECTED,
             "refused": {}, "reason": merge.REJ_WIPED}
            for c in body.get("changes") or [] if isinstance(c, dict)]}
    try:
        if body.get("clock"):
            clock.receive(Hlc.parse(body["clock"]))
    except ClockError:
        pass  # the per-change stamps are what is checked; a bad header clock is not fatal
    results = []
    arrived: list[tuple[str, str]] = []
    plans_touched = False
    with _applying(db):
        for change in body.get("changes") or []:
            outcome = _Outcome(change)
            try:
                _apply(db, user_id, change, clock, outcome, arrived)
            except merge.Rejected as exc:
                outcome.status, outcome.refused = merge.REJECTED, {}
                outcome.reason, outcome.detail = exc.reason, exc.detail or None
            results.append(outcome.out())
            if outcome.entity in ("plan", "planned_workout"):
                plans_touched = True
        _settle(db, user_id, arrived)
    if plans_touched:
        reap_dead_workouts(db, user_id)
    db.commit()
    return {"clock": str(clock.current()), "results": results}


def _receive_all(clock: Clock, change: dict) -> None:
    stamps = [pair[1] for pair in (change.get("fields") or {}).values()]
    if change.get("deleted") is not None:
        stamps.append(change["deleted"])
    for s in stamps:
        merge.validate_stamp(s)
        try:
            clock.receive(Hlc.parse(s))
        except SkewRefused as exc:
            raise merge.Rejected(merge.REJ_CLOCK_SKEW, str(exc)) from exc


def _apply(db, user_id, change, clock, outcome, arrived):
    entity = change.get("entity")
    spec = ENTITIES.get(entity)
    if spec is None:
        raise merge.Rejected(merge.REJ_UNKNOWN_ENTITY, f"unknown entity {entity!r}")
    if spec["kind"] == "readonly":
        raise merge.Rejected(merge.REJ_READONLY, f"{entity} is written by the server only")
    uid = change.get("uid")
    if not isinstance(uid, str) or not uid or len(uid) > 36:
        raise merge.Rejected(merge.REJ_INVALID, "missing or malformed uid")
    fields = change.get("fields") or {}
    if not isinstance(fields, dict) or not all(
            isinstance(p, (list, tuple)) and len(p) == 2 for p in fields.values()):
        raise merge.Rejected(merge.REJ_INVALID, "fields must map name to [value, stamp]")
    _receive_all(clock, change)
    adapter = ADAPTERS[entity]
    delete = change.get("deleted")
    # Every value checked before any is applied, so one bad value cannot
    # leave the rest of the change half-written.
    for name, (value, _stamp) in fields.items():
        if name in spec["fields"]:
            try:
                adapter.validate(name, value)
            except ValueError as exc:
                raise merge.Rejected(merge.REJ_INVALID, str(exc)) from exc

    natural = adapter.natural_uid_from_fields({k: v[0] for k, v in fields.items()})
    if natural is not None and natural != uid and entity != "activity":
        raise merge.Rejected(merge.REJ_INVALID, f"uid does not match its natural key ({natural})")

    tomb = db.execute(select(SyncTombstone).where(
        SyncTombstone.user_id == user_id, SyncTombstone.entity == entity,
        SyncTombstone.uid == uid)).scalars().first()
    if tomb is not None:
        outcome.refused = {f: merge.R_DELETED if f in spec["fields"] else merge.R_UNKNOWN
                           for f in fields}
        changed = delete is not None and merge.decide_delete(tomb.deleted, delete)
        if changed:
            write_tombstone(db, user_id, entity, uid, delete)
        attempted = len(fields) + (1 if delete is not None else 0)
        outcome.status = merge.status_of(1 if changed else 0, len(outcome.refused), attempted)
        return

    obj = adapter.find(db, user_id, uid)
    link = merge.parent_link(entity)
    if obj is None:
        _apply_staged(db, user_id, adapter, change, outcome, arrived)
        _follow_dead_parent(db, user_id, adapter, uid, link)
        return

    applied = 0
    for name, (value, stamp) in fields.items():
        if name not in spec["fields"]:
            outcome.refused[name] = merge.R_UNKNOWN
            continue
        if link and name == link[1]:
            current = adapter.get(db, obj, name)
            if current is not None and value != current:
                outcome.refused[name] = merge.R_IMMUTABLE
                continue
        clock_map = obj.clock or {}
        reason = merge.decide_field(
            spec["kind"], deleted=False, present=name in clock_map,
            current_value=adapter.get(db, obj, name) if name in clock_map else None,
            current_stamp=clock_map.get(name), value=value, stamp=stamp,
        )
        if reason:
            outcome.refused[name] = reason
            continue
        try:
            adapter.set(db, user_id, obj, name, value)
        except (ValueError, NotYet) as exc:
            raise merge.Rejected(merge.REJ_INVALID, str(exc)) from exc
        obj.clock = {**(obj.clock or {}), name: stamp}
        applied += 1
    if applied:
        obj.server_seq = SEQ_PENDING
        _touch(db, adapter.table.name)
    if delete is not None:
        delete_row(db, user_id, adapter, obj, delete)
        applied += 1
    db.flush()
    attempted = len(fields) + (1 if delete is not None else 0)
    outcome.status = merge.status_of(applied, len(outcome.refused), attempted)


def _follow_dead_parent(db, user_id, adapter: Adapter, uid: str, link) -> None:
    """A child that arrived while its parent is a tombstone is deleted with the
    parent's stamp — the other half of cascade, since arrival order is free."""
    if not link:
        return
    parent_entity, field = link
    obj = adapter.find(db, user_id, uid)
    staged = _staged(db, user_id, adapter.entity, uid)
    if obj is not None:
        parent_uid = adapter.get(db, obj, field)
    elif staged is not None:
        parent_uid = (staged.fields or {}).get(field)
    else:
        return
    if parent_uid is None:
        return
    tomb = db.execute(select(SyncTombstone).where(
        SyncTombstone.user_id == user_id, SyncTombstone.entity == parent_entity,
        SyncTombstone.uid == parent_uid)).scalars().first()
    if tomb is None:
        return
    if obj is not None:
        delete_row(db, user_id, adapter, obj, tomb.deleted)
    else:
        write_tombstone(db, user_id, adapter.entity, uid, tomb.deleted)
        db.flush()


def delete_row(db: Session, user_id: int, adapter: Adapter, obj, stamp: str) -> None:
    """Delete a row with a known stamp, tombstoning it and its cascade."""
    write_tombstone(db, user_id, adapter.entity, obj.uid, stamp)
    _cascade_tombstones(db, adapter, [_pk(obj)], stamp)
    db.delete(obj)
    db.flush()


def _staged(db, user_id, entity, uid) -> SyncPendingRow | None:
    return db.execute(select(SyncPendingRow).where(
        SyncPendingRow.user_id == user_id, SyncPendingRow.entity == entity,
        SyncPendingRow.uid == uid)).scalars().first()


def _apply_staged(db, user_id, adapter: Adapter, change, outcome, arrived):
    """A change to a row this database does not have: merge it into the staged
    copy (the pure merge, exactly as the corpus specifies), then try to create."""
    entity, uid = adapter.entity, change["uid"]
    staged = _staged(db, user_id, entity, uid)
    key = f"{entity}/{uid}"
    rows = {}
    if staged is not None:
        rows[key] = {"fields": dict(staged.fields), "clock": dict(staged.clock), "deleted": None}
    status, refused = merge.apply_change(rows, change)
    outcome.status, outcome.refused = status, refused
    row = rows.get(key)
    if row is None:
        return
    if row["deleted"] is not None:
        if staged is not None:
            db.delete(staged)
        write_tombstone(db, user_id, entity, uid, row["deleted"])
        # Children staged under it go with it.
        for child, field in merge.children_of(entity):
            for s in db.execute(select(SyncPendingRow).where(
                    SyncPendingRow.user_id == user_id,
                    SyncPendingRow.entity == child)).scalars():
                if (s.fields or {}).get(field) == uid:
                    write_tombstone(db, user_id, child, s.uid, row["deleted"])
        db.flush()
        return
    if status == merge.STALE and staged is not None:
        return
    if _materialize(db, user_id, adapter, uid, row):
        if staged is not None:
            db.delete(staged)
        arrived.append((entity, uid))
    else:
        if staged is None:
            staged = SyncPendingRow(user_id=user_id, entity=entity, uid=uid)
            db.add(staged)
        staged.fields = row["fields"]
        staged.clock = row["clock"]
        staged.server_seq = SEQ_PENDING
        _touch(db, "sync_pending_rows")
    db.flush()


def _materialize(db, user_id, adapter: Adapter, uid: str, row: dict) -> bool:
    try:
        obj = adapter.new(db, user_id, uid, row["fields"])
    except NotYet:
        return False
    except ValueError as exc:
        raise merge.Rejected(merge.REJ_INVALID, str(exc)) from exc
    obj.clock = dict(row["clock"])
    obj.server_seq = SEQ_PENDING
    db.add(obj)
    db.flush()
    _touch(db, adapter.table.name)
    return True


def _settle(db: Session, user_id: int, arrived: list[tuple[str, str]]) -> None:
    """Now that some rows exist, retry what was waiting for them.

    Staged rows whose parent arrived are created, and rows holding a pending
    reference to an arrival have it resolved to a real foreign key. Repeats
    until nothing more moves, because a creation can unblock another.
    """
    progress = True
    while progress:
        progress = False
        staged = db.execute(select(SyncPendingRow).where(
            SyncPendingRow.user_id == user_id)).scalars().all()
        for s in staged:
            adapter = ADAPTERS.get(s.entity)
            if adapter is None or s.entity == "activity":
                continue
            row = {"fields": dict(s.fields), "clock": dict(s.clock), "deleted": None}
            if _materialize(db, user_id, adapter, s.uid, row):
                db.delete(s)
                arrived.append((s.entity, s.uid))
                progress = True
        db.flush()
    _resolve_pending_refs(db, user_id)


def _resolve_pending_refs(db: Session, user_id: int) -> None:
    for adapter in ADAPTERS.values():
        if not adapter.refs:
            continue
        rows = db.execute(adapter.scope(select(adapter.model), user_id).where(
            adapter.model.pending_refs.isnot(None))).scalars().all()
        for obj in rows:
            for field, uid in dict(obj.pending_refs or {}).items():
                if isinstance(uid, list):
                    adapter.set(db, user_id, obj, field, adapter.get(db, obj, field))
                elif adapter.ref_target(field).id_for_uid(db, user_id, uid) is not None:
                    adapter.set(db, user_id, obj, field, uid)


def absorb_staged(db: Session, user_id: int, entity: str, obj) -> None:
    """Fold edits that were waiting for this row (e.g. a rename pushed before
    the ride's file was imported) into it, stamps and all."""
    staged = _staged(db, user_id, entity, obj.uid)
    if staged is None:
        return
    adapter = ADAPTERS[entity]
    with _applying(db):
        clock_map = dict(obj.clock or {})
        for field, value in (staged.fields or {}).items():
            stamp = staged.clock.get(field)
            if stamp is None or (clock_map.get(field) and stamp <= clock_map[field]):
                continue
            adapter.set(db, user_id, obj, field, value)
            clock_map[field] = stamp
        obj.clock = clock_map
        obj.server_seq = SEQ_PENDING
        _touch(db, adapter.table.name)
        db.delete(staged)
        db.flush()


def is_tombstoned(db: Session, user_id: int, entity: str, uid: str) -> bool:
    return db.execute(select(SyncTombstone.id).where(
        SyncTombstone.user_id == user_id, SyncTombstone.entity == entity,
        SyncTombstone.uid == uid)).first() is not None


# ── Read-time rules on the server ───────────────────────────────────────────

def reap_dead_workouts(db: Session, user_id: int) -> int:
    """Delete generated workouts of a superseded plan generation.

    Deleted with a fresh server stamp, as any replica that notices would; the
    tombstones then reach phones that have not noticed yet.
    """
    from app.models.training_plan import PlannedWorkout, TrainingPlan
    plans = {p.id: p for p in db.execute(select(TrainingPlan).where(
        TrainingPlan.user_id == user_id)).scalars()}
    if not plans:
        return 0
    n = 0
    rows = db.execute(select(PlannedWorkout).where(
        PlannedWorkout.user_id == user_id, PlannedWorkout.plan_id.in_(list(plans)))).scalars().all()
    for w in rows:
        p = plans[w.plan_id]
        workout = {"fields": {"plan_uid": p.uid, "generation": w.generation,
                              "moved_by_user": w.moved_by_user},
                   "clock": w.clock or {}, "deleted": None}
        plan = {"fields": {"generation": p.generation}, "clock": p.clock or {}, "deleted": None}
        if merge.workout_is_dead(workout, plan):
            db.delete(w)
            n += 1
    if n:
        db.flush()
    return n


def active_goal(db: Session, user_id: int):
    """The active goal, resolved as the corpus says when more than one is marked."""
    from app.models.coaching import TrainingGoal
    goals = db.execute(select(TrainingGoal).where(
        TrainingGoal.user_id == user_id, TrainingGoal.is_active.is_(True))).scalars().all()
    if len(goals) <= 1:
        return goals[0] if goals else None
    rows = {g.uid: {"fields": {"is_active": True}, "clock": g.clock or {}, "deleted": None}
            for g in goals}
    uid = merge.active_goal(rows)
    return next(g for g in goals if g.uid == uid)


# ── Pull ─────────────────────────────────────────────────────────────────────

def _row_change(db, adapter: Adapter, obj) -> dict:
    stamps = obj.clock or {}
    fields = {f: [adapter.get(db, obj, f), s] for f, s in stamps.items() if f in adapter.spec["fields"]}
    return {"entity": adapter.entity, "uid": obj.uid, "fields": fields, "deleted": None}


def pull(db: Session, user_id: int, since: int, limit: int | None,
         entities: set[str] | None = None) -> dict:
    limit = max(1, min(limit or PULL_DEFAULT_LIMIT, PULL_MAX_LIMIT))
    since = max(0, int(since or 0))
    # Every page says which database and which epoch it came from. A phone that
    # sees a new server_id re-pushes everything (the server was recreated); one
    # that sees a higher epoch wipes (the user deleted their data). Telling the
    # two apart is the point: treated alike, one of them loses data.
    header = {"server_id": server_clock(db).node, "epoch": account_epoch(db, user_id)}

    candidates: list[tuple[int, dict]] = []
    truncated = False

    def seq_filter(col):
        return (col > since, col.isnot(None), col != SEQ_PENDING)

    for adapter in ADAPTERS.values():
        if entities is not None and adapter.entity not in entities:
            continue
        stmt = adapter.scope(select(adapter.model), user_id).where(
            *seq_filter(adapter.model.server_seq)).order_by(adapter.model.server_seq).limit(limit)
        rows = db.execute(stmt).scalars().all()
        truncated |= len(rows) == limit
        for obj in rows:
            if not obj.clock:
                continue
            candidates.append((obj.server_seq, _row_change(db, adapter, obj)))

    tomb_stmt = select(SyncTombstone).where(
        SyncTombstone.user_id == user_id,
        *seq_filter(SyncTombstone.server_seq)).order_by(SyncTombstone.server_seq).limit(limit)
    tombs = db.execute(tomb_stmt).scalars().all()
    truncated |= len(tombs) == limit
    for t in tombs:
        if entities is None or t.entity in entities:
            candidates.append((t.server_seq, {"entity": t.entity, "uid": t.uid,
                                              "fields": {}, "deleted": t.deleted}))

    staged_stmt = select(SyncPendingRow).where(
        SyncPendingRow.user_id == user_id,
        *seq_filter(SyncPendingRow.server_seq)).order_by(SyncPendingRow.server_seq).limit(limit)
    staged = db.execute(staged_stmt).scalars().all()
    truncated |= len(staged) == limit
    for s in staged:
        if entities is None or s.entity in entities:
            candidates.append((s.server_seq, {
                "entity": s.entity, "uid": s.uid,
                "fields": {f: [v, s.clock.get(f)] for f, v in (s.fields or {}).items()},
                "deleted": None}))

    candidates.sort(key=lambda c: c[0])
    page = candidates[:limit]
    has_more = truncated or len(candidates) > limit
    nxt = page[-1][0] if page else since
    return {"changes": [c for _, c in page], "next": nxt, "has_more": has_more, **header}


def record_wipe(db: Session, user_id: int) -> None:
    """The user deleted their data: bump the account's epoch.

    Tombstones and staged rows go too — they describe data that no longer
    exists, and every phone is about to wipe and pull from 0 anyway.
    """
    db.execute(SyncTombstone.__table__.delete().where(SyncTombstone.user_id == user_id))
    db.execute(SyncPendingRow.__table__.delete().where(SyncPendingRow.user_id == user_id))
    user = db.get(User, user_id)
    user.sync_epoch = (user.sync_epoch or 0) + 1

