# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
FIT parse + database-insert pipeline, and the pending-import processor that
drives it for sync-agent-ingested data.

Replaces the old filesystem watcher (app.watcher, deleted): there's no more
directory-based user resolution or "move the file to a folder once
attributed" step, because ingestion now always knows the target user_id
upfront (see app.api.sync_ingest / app.api.fit_upload) — a sealed/encrypted
blob just sits in app.services.object_storage until it's processed.

process_pending_imports_for_user is the sync-agent entry point: called right
after login (see app.api.auth._create_authenticated_token) with that
session's freshly-unwrapped key material, since sealed blobs can only be
opened once a user's privkey is available — which never happens at ingest
time for a headless agent. Runs synchronously for now (see the branch plan —
this moves onto the Celery task queue in a later stage); a background thread
is used so login itself doesn't block on however many files are queued.
"""

import hashlib
import logging
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from psycopg2.extras import execute_values
from sqlalchemy import func

from app.database import SessionLocal
from app.models.activity import (
    Activity,
    ClimbSplit,
    DataPoint,
    Lap,
    PaceBest,
    PowerBest,
    StrengthSet,
    UserDevice,
)
from app.models.imports import Import, PendingImport
from app.models.sync import FitFile
from app.spec.sync import ENTITIES as SYNC_ENTITIES
from app.sync import store as sync_store
from app.sync.registry import activity_uid, to_wire
from app.sync.uids import natural_uid
from app.models.metrics import DailyMetric
from app.models.user_settings import UserSettings
from app.calculators.activity_metrics import compute_power_tss
from app.calculators.hr_review import review_points
from app.calculators.training_load import estimated_raw_tss
from app.parsers.activity import ActivityParser
from app.parsers.sleep import SleepParser
from app.parsers.daily_health import DailyHealthParser
from app.services import crypto_context, object_storage, user_crypto
from app.services.device_resolution import get_or_create_device
from app.services.user_crypto import UserKeyMaterial

log = logging.getLogger(__name__)

_PARSERS = [ActivityParser(), SleepParser(), DailyHealthParser()]


# ── Import settings (FTP / threshold HR) ────────────────────────────────────
# Deliberately private copies of app.api.training_plan.helpers's equivalents
# — this module is a service the API layer depends on, not the other way
# around, so it can't import from app.api without risking a circular import.

@dataclass
class _ImportSettings:
    ftp: float | None
    threshold_hr: float | None


def _effective_ftp(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.ftp_mode == "manual":
        return float(us.ftp_manual) if us.ftp_manual else None
    return float(us.ftp_auto) if us.ftp_auto else None


def _effective_threshold_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.threshold_hr_mode == "manual":
        return float(us.threshold_hr_manual) if us.threshold_hr_manual else None
    return float(us.threshold_hr_auto) if us.threshold_hr_auto else None


def _load_import_settings(db, user_id: int) -> _ImportSettings:
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    return _ImportSettings(ftp=_effective_ftp(us), threshold_hr=_effective_threshold_hr(us))


def _compute_hr_tss(activity_dict: dict, threshold_hr: float | None,
                    data_points: list[dict] | None = None) -> float | None:
    """hrTSS, from the samples of the recording that survive review.

    The recording is checked sample by sample first (calculators/hr_review):
    samples it contradicts — a dropout, a cadence lock, a strap reading
    walking pace up a climb — are replaced from the athlete's own heart rate
    against effort, and the average used here is recomputed. A clean
    recording is untouched and scores exactly as before. One with too little
    left to believe scores as an activity with no heart rate does.
    """
    dur = activity_dict.get("duration_seconds")
    avg_hr = activity_dict.get("avg_heart_rate")
    max_hr = activity_dict.get("max_heart_rate")
    if not dur or not avg_hr:
        return None
    review = review_points(activity_dict.get("sport"), data_points) if data_points else None
    if review is not None:
        if not review.usable:
            return estimated_raw_tss(activity_dict.get("sport"), dur,
                                     activity_dict.get("distance_meters"),
                                     activity_dict.get("total_ascent"))
        avg_hr = review.avg_hr
        max_hr = review.max_hr
    if threshold_hr is None:
        threshold_hr = (max_hr or int(avg_hr * 1.15)) * 0.87
    if threshold_hr <= 0:
        return None
    hr_ratio = min(avg_hr / threshold_hr, 1.5)
    # Multiplied, not ``** 2``: pow() need not round as x·x does, and the
    # phone (LocalImporter.hrTss) multiplies — a reviewed average is no
    # longer a whole number, so the last bit can now reach the rounding.
    return round((dur / 3600) * (hr_ratio * hr_ratio) * 100, 1)


# ── Post-import jobs (cache warm, workout match, plan refresh) ─────────────
# Debounced background jobs — unchanged from the old watcher.jobs, just
# relocated (none of this was filesystem/watcher-specific).

import threading

_warm_lock = threading.Lock()
_warm_timer: threading.Timer | None = None


def _schedule_cache_warm() -> None:
    global _warm_timer
    with _warm_lock:
        if _warm_timer is not None:
            _warm_timer.cancel()
        _warm_timer = threading.Timer(5.0, _do_cache_warm)
        _warm_timer.daemon = True
        _warm_timer.start()


def _do_cache_warm() -> None:
    global _warm_timer
    with _warm_lock:
        _warm_timer = None
    from app.api.activities import invalidate_heatmap_cache
    from app.api.metrics import (
        invalidate_dashboard_cache, invalidate_training_load_cache,
        warm_dashboard_cache, warm_training_load_cache,
    )
    invalidate_heatmap_cache()
    invalidate_training_load_cache()
    invalidate_dashboard_cache()
    warm_training_load_cache()
    warm_dashboard_cache()


_refresh_lock = threading.Lock()
_refresh_timers: dict[int, threading.Timer] = {}


def _schedule_workout_match(activity_id: int, user_id: int | None) -> None:
    if user_id is None:
        return
    from app.api.training_plan import match_activity_to_workout

    def _run():
        db = SessionLocal()
        try:
            match_activity_to_workout(db, activity_id, user_id)
        finally:
            db.close()

    threading.Thread(target=_run, daemon=True).start()


def _schedule_plan_refresh(user_id: int | None, material: UserKeyMaterial | None = None) -> None:
    """Rebuild the user's plans shortly after their last import.

    ``material`` is the key the import ran under. The rebuild runs on a timer
    thread, which inherits no decryption key (crypto_context), and without
    one it cannot read the person's injuries and plans strength as if they
    had none — while their phone, which always can, plans around them. The
    key is held only until the timer fires, in this process's memory, which
    is where the import already held it.
    """
    if user_id is None:
        return
    with _refresh_lock:
        existing = _refresh_timers.get(user_id)
        if existing is not None:
            existing.cancel()
        t = threading.Timer(10.0, _do_plan_refresh, args=(user_id, material))
        t.daemon = True
        _refresh_timers[user_id] = t
        t.start()


def _do_plan_refresh(user_id: int, material: UserKeyMaterial | None = None) -> None:
    with _refresh_lock:
        _refresh_timers.pop(user_id, None)
    from app.api.training_plan import refresh_plans_for_user
    from app.calculators.user_stats import recalculate_auto_values

    # The auto thresholds first, from the history as it now stands. They were
    # only recomputed at startup, so a plan rebuilt after an import read the
    # max and threshold HR of the last restart — and the phone, which derives
    # them afresh at every rebuild (LocalSources.importThresholds), built its
    # paces and zones from different numbers.
    db = SessionLocal()
    try:
        recalculate_auto_values(db, user_id)
    except Exception:
        log.exception("Auto-value recalculation failed for user %d", user_id)
    finally:
        db.close()
    if material is None:
        refresh_plans_for_user(user_id)
    else:
        crypto_context.run_with_key(material, refresh_plans_for_user, user_id)


# ── Parse + insert ───────────────────────────────────────────────────────────

def _insert_datapoints(db, activity_id: int, data_points: list[dict], material: UserKeyMaterial) -> None:
    """Bulk-insert via psycopg2 execute_values — much faster than individual
    inserts for large activities. Bypasses the SQLAlchemy type layer
    entirely (that's the whole point of using execute_values), so lat/lng —
    encrypted on the DataPoint model via EncryptedFloat — are encrypted
    explicitly here instead; a TypeDecorator on the model would silently not
    apply to this path."""
    if not data_points:
        return

    def _enc(value):
        if value is None:
            return None
        return user_crypto.encrypt_bytes(material.dek, repr(float(value)).encode("utf-8"))

    cursor = db.connection().connection.cursor()
    execute_values(
        cursor,
        """INSERT INTO data_points
        (activity_id, recorded_at, lat, lng, altitude,
        heart_rate, power, cadence, speed, grit, flow)
        VALUES %s""",
        [
            (
                activity_id,
                dp["recorded_at"],
                _enc(dp.get("lat")),
                _enc(dp.get("lng")),
                dp.get("altitude"),
                dp.get("heart_rate"),
                dp.get("power"),
                dp.get("cadence"),
                dp.get("speed"),
                dp.get("grit"),
                dp.get("flow"),
            )
            for dp in data_points
        ],
        page_size=1000,
    )


"""Daily-metric fields an import is allowed to write.

An allow-list rather than a pass-through, so a parser gaining a key cannot
silently start writing a column nobody meant it to. The cost of that is a list
that has to be kept in step, and it had drifted badly: `steps`,
`active_calories`, `avg_stress_level` and `avg_respiration_rate` were parsed out
of every monitoring file, dropped here, and left null in every row — while the
model, the API schema, the sync delta and both clients all carried them. The
Health screen's Steps and Stress cards were reading columns nothing had ever
written to.
"""
_DAILY_METRIC_FIELDS = {
    "resting_hr", "hrv", "sleep_hours", "sleep_score",
    "sleep_deep_hours", "sleep_light_hours", "sleep_rem_hours", "sleep_awake_hours",
    "training_load",
    "spo2", "steps", "active_calories", "resting_calories",
    "avg_stress_level", "avg_respiration_rate",
    "body_battery_high", "body_battery_low", "body_battery_last",
    "body_battery_charged", "body_battery_drained",
}

# Fields where a later file for the same day should widen the answer rather than
# be ignored. A watch writes one monitoring file per day and keeps appending to
# it, so each sync brings a fuller version of a day already partly recorded —
# under first-wins, a morning sync would pin the day's Body Battery high at
# whatever it happened to be at breakfast.
_DAILY_METRIC_MAX = {"body_battery_high", "body_battery_charged", "body_battery_drained", "steps",
                     "active_calories", "resting_calories"}
_DAILY_METRIC_MIN = {"body_battery_low"}
# ...and where the newest reading is simply the right one.
#
# `resting_hr` is here because the watch reports "resting heart rate for the
# current day" continuously and has not recomputed it at two minutes past
# midnight — so the first thing a new day hears is yesterday's figure, and
# first-wins pinned it there. The settled reading is the last one.
_DAILY_METRIC_LATEST = {"body_battery_last", "resting_hr"}

# Everything above that a *health file* is the source of.
#
# `training_load` is the exception and the reason this set exists separately:
# it lives on the same row and in the same allow-list, and it is the activity
# importer's own arithmetic rather than anything a monitoring file carries. A
# re-parse that cleared it would take out the input to the fitness model.
_DAILY_METRIC_FROM_FILES = _DAILY_METRIC_FIELDS - {"training_load"}


def _merge_daily_value(field: str, current, incoming):
    """What a day's value becomes when a second file reports it.

    Default is first-wins: the earliest file to carry a reading owns it, which is
    right for a summary like resting HR that the device computes once. The three
    sets above opt specific fields out of that.
    """
    if current is None:
        return incoming
    if field in _DAILY_METRIC_LATEST:
        return incoming
    if field in _DAILY_METRIC_MAX:
        return max(current, incoming)
    if field in _DAILY_METRIC_MIN:
        return min(current, incoming)
    return current


def _merge_stress_series(current, incoming) -> list[list[int]]:
    """Two readings of one day's stress, folded together by the clock.

    A day is described by several monitoring files — one per sync, each a few
    hours of epochs — so no single file holds the whole curve and "longest
    wins" would keep whichever slice happened to be biggest and throw the rest
    of the day away. That is the difference from the sleep timeline above,
    where one file *is* the night.

    Keyed by minute of local day, which makes re-reading a file a no-op and
    makes a re-parse corrective rather than merely additive: the incoming
    reading wins its own minute, so a series recorded by a parser that filed
    an evening under the wrong date is replaced rather than merged into.
    """
    merged: dict[int, int] = {}
    for point in (current or []):
        try:
            merged[int(point[0])] = int(point[1])
        except (TypeError, ValueError, IndexError):
            continue
    for point in incoming:
        try:
            merged[int(point[0])] = int(point[1])
        except (TypeError, ValueError, IndexError):
            continue
    return [[m, v] for m, v in sorted(merged.items())]


def _write_daily_metrics(db, parsed: dict, device_id: int | None, user_id: int) -> None:
    """Fold one parsed file into the day rows it describes.

    ``days`` when a parser found several — a monitoring file straddles local
    midnight far more often than not, and its counters reset there, so filing
    the whole of one under a single date is what made a step count carry over
    into the morning. See ``DailyHealthParser.parse``. The single-``date`` shape
    is still accepted for the parsers that only ever describe one day.
    """
    days = parsed.get("days")
    if days is not None:
        for day in days:
            _write_one_day(db, day, device_id, user_id)
        return
    _write_one_day(db, parsed, device_id, user_id)


def _write_one_day(db, parsed: dict, device_id: int | None, user_id: int) -> None:
    metric_date = parsed.get("date")
    if metric_date is None:
        log.warning("Skipping daily metrics: missing date")
        return

    metrics = {k: v for k, v in parsed.get("metrics", {}).items() if k in _DAILY_METRIC_FIELDS}

    # The night's stage timeline, when this was a sleep file. It goes in `extra`
    # rather than a column because it is a variable-length list per day and the
    # only thing that ever reads it is the one endpoint that draws it — a table
    # of its own would be a join for data that is never queried across days.
    stages = parsed.get("stages") or []
    # The day's stress, reading by reading, for exactly the same reasons. See
    # ``_merge_stress_series`` for why this one is merged per minute rather
    # than by whichever copy is longer.
    stress_series = parsed.get("stress_series") or []

    existing = db.query(DailyMetric).filter_by(date=metric_date, user_id=user_id).first()
    if existing:
        for field, value in metrics.items():
            if value is None:
                continue
            merged = _merge_daily_value(field, getattr(existing, field), value)
            if merged != getattr(existing, field):
                setattr(existing, field, merged)
        if device_id and existing.device_id is None:
            existing.device_id = device_id
        extra = dict(existing.extra or {})
        changed = False
        if stages:
            # Whichever file describes more of the night wins. A watch syncing
            # mid-morning can write a partial night and a later sync the whole
            # of it; first-wins would pin the chart to the fragment.
            if len(stages) >= len((extra.get("sleep_stages") or [])):
                extra["sleep_stages"] = stages
                changed = True
        if stress_series:
            extra["stress_series"] = _merge_stress_series(
                extra.get("stress_series"), stress_series,
            )
            changed = True
        if changed:
            existing.extra = extra
    else:
        extra = {}
        if stages:
            extra["sleep_stages"] = stages
        if stress_series:
            extra["stress_series"] = stress_series
        db.add(DailyMetric(
            date=metric_date, user_id=user_id, device_id=device_id, extra=extra, **metrics,
        ))


# Which session fields an activity's import has read: 1 is workout_feel and
# workout_rpe (FIT session fields 192/193, parsed since 2026-09-30). Written on
# the Import row that parsed the activity, so backfill_activity_summaries_for_user
# can tell "read, and the prompt was skipped" (both null, marked) from "never
# read" (both null, unmarked) without a schema change. Bump it, and add the
# columns to _SUMMARY_BACKFILL_FIELDS, when the parser learns another one.
SUMMARY_FIELDS_VERSION = 1
_SUMMARY_BACKFILL_FIELDS = ("workout_feel", "workout_rpe")


def _record_import(db, activity_id: int | None, user_id: int, content_hash: str, filename: str,
                    source: str = "sync", blob_id: str | None = None) -> None:
    extra = {"filename": filename}
    if blob_id is not None:
        extra["blob_id"] = blob_id
    if activity_id is not None:
        # Parsed by this build, so every summary field it knows is in.
        extra["summary_fields"] = SUMMARY_FIELDS_VERSION
    db.add(Import(
        activity_id=activity_id,
        user_id=user_id,
        source=source,
        source_id=content_hash,
        extra=extra,
    ))


def register_file(db, user_id: int, content_hash: str, filename: str | None,
                  size: int | None = None, blob_id: str | None = None, sealed: bool = False,
                  parsed: dict | None = None) -> FitFile:
    """Record that this account has this file — the manifest phones pull.

    Idempotent, and filled in as more is known: the sealed ingest knows only
    the hash and size; the parse, later, knows what the file is. Written
    outside `derived()` on purpose: these rows are what tells every phone which
    files exist, so they must be stamped and pulled.
    """
    f = db.query(FitFile).filter_by(user_id=user_id, sha256=content_hash).first()
    if f is None:
        f = FitFile(user_id=user_id, sha256=content_hash,
                    uid=natural_uid(SYNC_ENTITIES["fit_file"]["uid_key"], sha256=content_hash))
        db.add(f)
    if filename and f.filename is None:
        f.filename = filename
    if size is not None and f.size_bytes is None:
        f.size_bytes = size
    if blob_id is not None and f.blob_id is None:
        f.blob_id, f.sealed = blob_id, sealed
    if parsed is not None:
        kind = parsed.get("type") or "other"
        if f.file_kind is None:
            f.file_kind = kind
        serial = (parsed.get("device") or {}).get("serial_number")
        if serial and f.device_serial is None:
            f.device_serial = serial
        started = (parsed.get("activity") or {}).get("started_at") if kind == "activity" else None
        if started is not None and f.started_at is None:
            f.started_at = to_wire(started)
    # Flushed here, not whenever the session next flushes: the caller goes on
    # to write derived data under sync_store.derived(), and a flush inside
    # that block would carry this row through unstamped — and a row with no
    # stamps is never pulled.
    db.flush()
    return f


def _insert_children(db, activity_id: int, parsed: dict, material: UserKeyMaterial) -> None:
    """Everything measured under an activity: track, laps, bests, sets."""
    _insert_datapoints(db, activity_id, parsed["data_points"], material)

    laps = parsed.get("laps", [])
    if laps:
        db.bulk_insert_mappings(Lap, [{"activity_id": activity_id, **lap} for lap in laps])

    climb_splits = parsed.get("climb_splits", [])
    if climb_splits:
        db.bulk_insert_mappings(ClimbSplit, [{"activity_id": activity_id, **s} for s in climb_splits])

    power_curve = parsed.get("power_curve", {})
    if power_curve:
        db.bulk_insert_mappings(PowerBest, [
            {"activity_id": activity_id, "duration_seconds": dur, "avg_watts": w}
            for dur, w in power_curve.items()
        ])

    pace_curve = parsed.get("pace_curve", {})
    if pace_curve:
        db.bulk_insert_mappings(PaceBest, [
            {"activity_id": activity_id, "distance_meters": dist, "avg_speed_mps": s}
            for dist, s in pace_curve.items()
        ])

    strength_sets = parsed.get("strength_sets", [])
    if strength_sets:
        db.bulk_insert_mappings(StrengthSet, [{"activity_id": activity_id, **s} for s in strength_sets])


def _insert_parsed(db, parsed: dict | None, content_hash: str, filename: str,
                    settings: _ImportSettings, user_id: int, material: UserKeyMaterial,
                    source: str = "sync", blob_id: str | None = None,
                    size: int | None = None) -> None:
    """Insert already-parsed FIT data. Raises on failure — callers decide
    whether that aborts a batch or is recorded per-item (see
    process_pending_imports_for_user). `source` distinguishes where this
    came from (sync agent vs. authenticated browser upload) in the Import
    table; `blob_id` optionally records the at-rest ciphertext copy.

    ## Duplicates, by bytes

    An Import row with this content hash *for this user* means the file is
    already in, whichever path brought it: a sync agent can re-deliver a file
    under a different source than the one that first imported it, and a
    source-scoped check once let 192 duplicate activities through with doubled
    DataPoints (see git history around 2026-08-13). It is scoped to the user
    because two members of one household can hold the same file — a shared
    ride recorded on a shared device — and a global check silently dropped the
    second person's copy.

    ## Duplicates, by meaning

    The same activity can arrive as different bytes (a watch re-exporting it,
    another transfer path). Its uid is derived from the watch serial and the
    start second (spec/sync.yaml), so both copies are the same activity by
    construction. The copy with the lowest hash is the one parsed — so every
    replica, whichever order the files reached it in, shows the same numbers —
    and the others are kept as files. A later, lower-hash copy therefore
    replaces the measurements in place, keeping the row, its links and
    whatever the user said about it.

    ## Derived, not stamped

    Everything written here is recomputed by every device from the same file,
    so it is written under `sync_store.derived()`: a phone's rename made before
    the upload must not be outranked by the server importing the original
    name. Only the file registry is stamped.
    """
    if db.query(Import).filter_by(user_id=user_id, source_id=content_hash).first():
        log.info("Already imported, skipping: %s (source=%s)", filename, source)
        return

    register_file(db, user_id, content_hash, filename, size=size, parsed=parsed,
                  blob_id=blob_id if source == "upload" else None)

    if parsed is None:
        log.info("Unrecognised FIT type, skipping: %s", filename)
        _record_import(db, None, user_id, content_hash, filename, source, blob_id)
        db.flush()
        return

    with sync_store.derived(db):
        device_id = get_or_create_device(db, parsed["device"], user_id)
    if device_id is not None:
        _sync_device_claim(db, user_id, device_id)

    if parsed.get("type") == "daily":
        with sync_store.derived(db):
            _write_daily_metrics(db, parsed, device_id, user_id)
            _record_import(db, None, user_id, content_hash, filename, source, blob_id)
            db.flush()
        log.info(
            "Imported daily metrics from %s (dates: %s)",
            filename,
            parsed.get("date")
            or ", ".join(str(d.get("date")) for d in parsed.get("days") or []),
        )
        return

    if parsed.get("type") != "activity":
        log.warning("Parser returned unhandled type %r for %s", parsed.get("type"), filename)
        return

    activity_dict = parsed["activity"].copy()

    power_tss = compute_power_tss(
        normalized_power=activity_dict.get("normalized_power"),
        ftp=settings.ftp,
        duration_seconds=activity_dict.get("duration_seconds"),
    )
    if power_tss is not None:
        activity_dict["effective_tss"] = power_tss
    elif activity_dict.get("training_stress_score") is None:
        activity_dict["effective_tss"] = _compute_hr_tss(activity_dict, settings.threshold_hr,
                                                         parsed.get("data_points"))
    # Stored unscaled. The MTB and indoor-cycling multipliers are applied when
    # load is read (training_load.scale_tss), so the stored value never depends
    # on which goal was active when, or on which device, the file was imported.

    uid = activity_uid(parsed["device"].get("serial_number"), activity_dict.get("started_at"),
                       content_hash)

    with sync_store.derived(db):
        if sync_store.is_tombstoned(db, user_id, "activity", uid):
            # The user deleted this activity. Its file is kept, as every file
            # is, but a delete wins: another copy of it must not bring it back.
            log.info("Activity %s was deleted; keeping %s as a file only", uid, filename)
            _record_import(db, None, user_id, content_hash, filename, source, blob_id)
            db.flush()
            return

        existing = db.query(Activity).filter_by(user_id=user_id, uid=uid, is_merged=False).first()
        if existing is not None:
            winner = (db.query(Import.source_id)
                      .filter_by(user_id=user_id, activity_id=existing.id)
                      .order_by(Import.source_id).first())
            if winner is not None and winner[0] <= content_hash:
                log.info("Another copy of %s already parsed; keeping %s as a file", uid, filename)
                _record_import(db, None, user_id, content_hash, filename, source, blob_id)
                db.flush()
                return
            # This copy has the lower hash, so it is the one every replica
            # parses. Replace the measurements, keep the row.
            said = set((existing.clock or {}).keys())
            for field, value in activity_dict.items():
                if field not in said:
                    setattr(existing, field, value)
            existing.device_id = device_id
            with sync_store.no_tombstones(db):
                for model in (DataPoint, Lap, ClimbSplit, PowerBest, PaceBest, StrengthSet):
                    db.query(model).filter(model.activity_id == existing.id).delete(
                        synchronize_session=False)
            db.query(Import).filter_by(user_id=user_id, activity_id=existing.id).update(
                {Import.activity_id: None}, synchronize_session=False)
            db.flush()
            _insert_children(db, existing.id, parsed, material)
            _record_import(db, existing.id, user_id, content_hash, filename, source, blob_id)
            db.flush()
            log.info("Re-parsed %s from lower-hash copy %s", uid, filename)
            return

        activity = Activity(device_id=device_id, user_id=user_id, uid=uid, **activity_dict)
        db.add(activity)
        db.flush()
        _insert_children(db, activity.id, parsed, material)
        _record_import(db, activity.id, user_id, content_hash, filename, source, blob_id)
        db.flush()

    # A rename or note pushed from a phone before this file reached the server.
    sync_store.absorb_staged(db, user_id, "activity", activity)

    log.info("Imported %s — %s, %d data points",
             filename, activity_dict.get("sport") or "unknown", len(parsed["data_points"]))

    _schedule_cache_warm()
    _schedule_workout_match(activity.id, user_id)
    _schedule_plan_refresh(user_id, material)


def _sync_device_claim(db, user_id: int, device_id: int) -> None:
    """A newly claimed watch is a fact phones should hear about: stamp its
    identity once, so the device row they pull says which watch it is."""
    claim = db.query(UserDevice).filter_by(user_id=user_id, device_id=device_id).first()
    if claim is None or (claim.clock or {}).get("serial_number"):
        return
    db.flush()
    sync_store.stamp_fields(db, claim, list(SYNC_ENTITIES["device"]["fields"]))


def _parse_bytes(raw: bytes, filename: str) -> dict | None:
    """Parsers take a Path, not bytes — write to a private temp file for the
    duration of parsing, then remove it. The temp file holds PLAINTEXT FIT
    data briefly and only in this process's own tmpdir; it's never written
    anywhere durable."""
    suffix = Path(filename).suffix or ".fit"
    with tempfile.NamedTemporaryFile(suffix=suffix) as tmp:
        tmp.write(raw)
        tmp.flush()
        path = Path(tmp.name)
        parser = next((p for p in _PARSERS if p.can_parse(path)), None)
        return parser.parse(path) if parser else None


def import_uploaded_bytes(user_id: int, content: bytes, filename: str, material: UserKeyMaterial) -> str:
    """Browser-upload path (see app.api.fit_upload) — the request already
    has a live session (a just-verified DEK), so this encrypts an at-rest
    copy and parses/inserts synchronously in the same call, rather than
    queuing a PendingImport for later: there's no "wait for login" gap to
    bridge, the key is right here. Returns "imported", "duplicate", or
    "unrecognised"."""
    content_hash = hashlib.sha256(content).hexdigest()
    db = SessionLocal()
    try:
        if db.query(Import).filter_by(user_id=user_id, source_id=content_hash).first():
            return "duplicate"

        encrypted = user_crypto.encrypt_bytes(material.dek, content)
        blob_id = object_storage.store_blob(encrypted)

        settings = _load_import_settings(db, user_id)
        parsed = _parse_bytes(content, filename)
        _insert_parsed(db, parsed, content_hash, filename, settings, user_id, material,
                        source="upload", blob_id=blob_id, size=len(content))
        db.commit()
        return "imported" if parsed is not None else "unrecognised"
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def retained_plaintext(db, user_id: int, sha256: str, material: UserKeyMaterial) -> bytes | None:
    """A file's bytes from this server's at-rest copy, or None if it holds none.

    Two shapes are kept: a sync agent's file is sealed against the user's
    public key on ingest (pending_imports, or fit_files.sealed), and a browser
    upload is encrypted with the DEK directly. Either needs the session's key
    material, so this only works while the user is logged in.
    """
    pending = (db.query(PendingImport)
               .filter_by(user_id=user_id, content_hash=sha256).first())
    if pending is not None:
        return user_crypto.unseal(material, object_storage.load_blob(pending.blob_id))
    record = db.query(Import).filter_by(user_id=user_id, source_id=sha256).first()
    blob_id = (record.extra or {}).get("blob_id") if record else None
    if blob_id is None:
        f = db.query(FitFile).filter_by(user_id=user_id, sha256=sha256).first()
        if f is None or f.blob_id is None:
            return None
        if f.sealed:
            return user_crypto.unseal(material, object_storage.load_blob(f.blob_id))
        blob_id = f.blob_id
    return user_crypto.decrypt_bytes(material.dek, object_storage.load_blob(blob_id))


def backfill_activity_summaries_for_user(user_id: int, material: UserKeyMaterial,
                                         sid: str | None = None) -> dict:
    """Fill the session fields an older parser walked past into activities
    imported before it learned them — today, the watch's feel and perceived
    effort (``_SUMMARY_BACKFILL_FIELDS``).

    ## Why at login

    The files are kept sealed, and the server holds the key only during a
    logged-in session (module docstring), so this runs where
    process_pending_imports_for_user does: queued at login with the session
    id (app.tasks.imports), or on request (POST /activities/summaries/backfill).
    The phone does the same for its own copies at startup
    (com.tracks.core.local.LocalImporter.backfillSummaries).

    ## What it may change

    Only those columns, and only where they are null: a value already stored —
    by this build's import, or by anything else — is never replaced, and no
    other field of the activity is touched. Like reparse_daily_metrics_for_user
    it reads the retained file directly rather than going through
    _insert_parsed, whose "already imported" check is right for imports and
    exactly wrong here.

    ## How it knows what is done

    Not by the columns: both null is also what an activity whose prompt was
    skipped looks like, and those would be re-read at every login for ever.
    The Import row that parsed the activity carries ``summary_fields`` instead
    (SUMMARY_FIELDS_VERSION), set by every import since and by this once a file
    has been read — or found to have nothing to read. A file that fails to
    open is left unmarked and tried again at the next login. An activity that
    already has either value is marked without opening its file. So a second
    run reads nothing, and each login costs one query.
    """
    db = SessionLocal()
    try:
        marked = func.coalesce(Import.extra["summary_fields"].as_integer(), 0)
        todo = (
            db.query(Import, Activity)
            .join(Activity, Activity.id == Import.activity_id)
            .filter(Import.user_id == user_id, Activity.user_id == user_id,
                    marked < SUMMARY_FIELDS_VERSION)
            .order_by(Import.id)
            .all()
        )
        read = filled = failed = 0
        for imp, activity in todo:
            try:
                if all(getattr(activity, f) is None for f in _SUMMARY_BACKFILL_FIELDS):
                    found = {}
                    raw = retained_plaintext(db, user_id, imp.source_id, material)
                    if raw is not None:
                        read += 1
                        parsed = _parse_bytes(raw, (imp.extra or {}).get("filename") or "unknown.fit")
                        if parsed and parsed.get("type") == "activity":
                            found = parsed.get("activity") or {}
                    values = {f: found[f] for f in _SUMMARY_BACKFILL_FIELDS if found.get(f) is not None}
                    if values:
                        # Derived from the file, as the import's own write is:
                        # these columns are not synced, and a phone works out
                        # its own from its copy.
                        with sync_store.derived(db):
                            for field, value in values.items():
                                if getattr(activity, field) is None:
                                    setattr(activity, field, value)
                        filled += 1
                # A new dict, so the JSON column registers the change.
                imp.extra = {**(imp.extra or {}), "summary_fields": SUMMARY_FIELDS_VERSION}
                db.commit()
            except Exception:
                db.rollback()
                log.exception("Summary backfill failed for import %s (activity %s)",
                              imp.id, activity.id)
                failed += 1
            finally:
                if sid is not None:
                    crypto_context.renew_session_key(sid)

        if todo:
            log.info("Backfilled activity summaries for user %s: %d files read, %d filled, %d failed",
                     user_id, read, filled, failed)
        return {"files": read, "filled": filled, "failed": failed}
    finally:
        db.close()


def reparse_daily_metrics_for_user(user_id: int, material: UserKeyMaterial,
                                   sid: str | None = None) -> dict:
    """Re-read every retained file and fold its daily metrics in again.

    ## Why this exists

    A parser improves, and every file already imported keeps whatever the old
    one managed to extract. That happened here on a scale worth a function:
    stress and respiration were being looked for as *fields* of the per-epoch
    monitoring record when the watch writes them as messages of their own, and
    steps were read as a single value when they are a counter per activity type
    — so three columns stayed null across a year of perfectly good files.
    Waiting for new syncs would have fixed the future and left the history
    empty.

    ## Why it does not go through the import path

    [_insert_parsed] returns early when a file's content hash already has an
    Import row, which is exactly right for imports and exactly wrong here: the
    point is to re-read files that *are* already imported. Making that path
    re-run would mean deleting Import rows — throwing away the record of what
    was ingested to work around a check that is doing its job.

    So this skips it entirely and calls [_write_daily_metrics] directly. No
    activity is touched, and no Import or PendingImport row changes.

    ## Why the watch-derived columns are cleared first

    Because the merge rules only ever *widen*. High-water for the counters,
    first-wins for the summaries — which is right when two files describe one
    day and useless when the stored figure is the thing being corrected. A day
    holding 3,333 steps because a file that crossed local midnight was filed
    under tomorrow will still hold 3,333 after a re-parse says 22, since 3,333
    is the larger. Correcting history means starting it empty.

    Only the columns a health file writes. `training_load` is in the same
    allow-list and comes from the activity importer's own arithmetic, so
    clearing it would take out the input to the fitness model; the entered
    figures — weight, hydration, calories in — were never in the list at all.

    Nothing is lost by it: every file is retained, and a run that fails partway
    can simply be run again.
    """
    db = SessionLocal()
    try:
        # Device-measured columns are derived on every device, so clearing them
        # is not an edit anyone else needs to hear about.
        with sync_store.derived(db):
            db.query(DailyMetric).filter_by(user_id=user_id).update(
                {field: None for field in _DAILY_METRIC_FROM_FILES},
                synchronize_session=False,
            )
            db.commit()

        items = (
            db.query(PendingImport)
            .filter_by(user_id=user_id)
            .order_by(PendingImport.created_at)
            .all()
        )
        seen = days = failed = 0
        for item in items:
            try:
                sealed = object_storage.load_blob(item.blob_id)
                raw = user_crypto.unseal(material, sealed)
                parsed = _parse_bytes(raw, item.filename or "unknown.fit")
                seen += 1
                if parsed and parsed.get("type") == "daily":
                    _write_daily_metrics(db, parsed, None, user_id)
                    db.commit()
                    days += 1
            except Exception:
                db.rollback()
                # One unreadable blob is not a reason to abandon the other nine
                # hundred; the error is already recorded on its own import row.
                log.exception("Re-parse failed for pending import %s", item.id)
                failed += 1
            finally:
                if sid is not None:
                    crypto_context.renew_session_key(sid)

        log.info(
            "Re-parsed daily metrics for user %s: %d files read, %d days written, %d failed",
            user_id, seen, days, failed,
        )
        return {"files": seen, "days": days, "failed": failed}
    finally:
        db.close()


def process_pending_imports_for_user(user_id: int, material: UserKeyMaterial,
                                     sid: str | None = None) -> None:
    """Unseal, parse, and insert every not-yet-processed PendingImport row
    for this user. Called with the key material from the session that was
    just established (login/setup) — never relies on the contextvar, since
    this runs off the request's own execution context (see
    app.tasks.imports, the Celery task that drives this in production).

    `sid` is optional only so direct/test callers that already have
    `material` in hand don't need a real Redis-backed session; when given,
    the session's Redis TTL is renewed after each item, since a large
    backlog could otherwise run past the crypto session's own sliding
    timeout with no HTTP activity to keep it alive."""
    db = SessionLocal()
    try:
        pending = (
            db.query(PendingImport)
            .filter_by(user_id=user_id, processed_at=None)
            .order_by(PendingImport.created_at)
            .all()
        )
        if not pending:
            return

        settings = _load_import_settings(db, user_id)
        ok = fail = 0
        for item in pending:
            try:
                sealed = object_storage.load_blob(item.blob_id)
                raw = user_crypto.unseal(material, sealed)
                parsed = _parse_bytes(raw, item.filename or "unknown.fit")
                _insert_parsed(db, parsed, item.content_hash, item.filename or "unknown.fit",
                               settings, user_id, material, size=len(raw))
                item.processed_at = datetime.now(timezone.utc)
                item.error = None
                db.commit()
                ok += 1
            except Exception as exc:
                db.rollback()
                item.error = str(exc)[:500]
                db.commit()
                log.exception("Failed to process pending import %s for user %s", item.id, user_id)
                fail += 1
            finally:
                if sid is not None:
                    crypto_context.renew_session_key(sid)

        log.info("Processed pending imports for user %s: %d ok, %d failed", user_id, ok, fail)
    finally:
        db.close()
