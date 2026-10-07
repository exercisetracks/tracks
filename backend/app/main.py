# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from sqlalchemy import text

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)

from app.api import (
    activities, coaching, devices, health, meals, medications, metrics, music,
    users, training_plan, fit_upload, workouts, device_sync, sync_agents,
    sync_ingest, sync, fuel,
)
from app.api import auth as auth_router
from app.api import strength as strength_api
from app.api import flexibility as flexibility_api
from app.api import garmin as garmin_api
from app.api.training_plan import sync_router
from app.api.metrics import warm_dashboard_cache, warm_training_load_cache
from app.auth import require_auth
from app.calculators.user_stats import recalculate_auto_values
from app.config import settings
from app.database import SessionLocal, engine
from app.middleware.caching import CacheControlMiddleware
from app.routes import capabilities, contours, fonts, sprite, map_style, regions, routes_api, tiles, poi as poi_routes, gnis as gnis_routes, trail_logos, courses_api, version as version_routes
from app.models.activity import User
from app.models.user_settings import UserSettings
from app.seed import seed_all

# Ensure all models are imported so Base.metadata.register is populated
import app.models.activity       # noqa: F401 — registers User, Device, Activity, DataPoint, et al.
import app.models.coaching       # noqa: F401 — registers TrainingGoal, RacePlan, CoachingRecommendation
import app.models.health         # noqa: F401 — registers Injury
import app.models.fuel           # noqa: F401 — registers FuelProduct, GutTrainingLog
import app.models.imports        # noqa: F401 — registers ImportSource, Import
import app.models.meals          # noqa: F401 — registers Meal, MealLog
import app.models.medications    # noqa: F401 — registers Medication, MedicationSchedule, MedicationLog
import app.models.metrics        # noqa: F401 — registers DailyMetric
import app.models.strength       # noqa: F401 — registers ExerciseLibrary, UserExerciseStrength, et al.
import app.models.flexibility    # noqa: F401 — registers StretchLibrary, UserFlexibilityFlow, et al.
import app.models.training_plan  # noqa: F401 — registers TrainingPlan, PlannedWorkout, et al.
import app.models.waypoint       # noqa: F401 — registers Waypoint
import app.models.upload_jobs    # noqa: F401 — registers FitUploadJob
import app.models.workout        # noqa: F401 — registers UserWorkout, UserWorkoutExercise, UserWorkoutSession
import app.models.map_region     # noqa: F401 — registers MapRegion
import app.models.poi_search     # noqa: F401 — registers PoiSearch
import app.models.custom_track   # noqa: F401 — registers CustomTrack
import app.models.user_keys      # noqa: F401 — registers UserKey
import app.models.sync_agents    # noqa: F401 — registers SyncAgent
import app.models.device_keys    # noqa: F401 — registers DeviceKey
import app.models.sync           # noqa: F401 — registers FitFile, SyncTombstone, et al.
# Registers the session events that stamp, sequence and tombstone every
# synced write — without this import the web app's edits would not sync.
import app.sync.store            # noqa: F401


# The revision a 1.0.0 database was created at. Everything before it was
# collapsed into it when Tracks was first released, so a database that has
# user tables but no revision, or a revision this build has never heard of,
# came from somewhere this build cannot upgrade.
_ALEMBIC_BASELINE = "0001baseline"


def _schema_problem(has_users: bool, current: str | None, known: set[str]) -> str | None:
    """Why this database must not be migrated, or None when it may be.

    Separate from _run_migrations so the refusals are testable without a
    database in each state. Each one stops startup rather than letting Alembic
    try: an unknown revision is almost always a database written by a *newer*
    Tracks (the operator pinned an older image after upgrading), and running
    the old code against the new schema would fail one endpoint at a time
    instead of once, clearly, here.
    """
    if not has_users:
        return None
    if current is None:
        return (
            "This database has Tracks tables but no schema revision, so it predates "
            "the 1.0.0 release and cannot be upgraded. Take a pg_dump, recreate the "
            "database, and re-import your FIT files."
        )
    if current not in known:
        return (
            f"This database is at schema revision {current!r}, which this version of "
            "Tracks does not know. It was most likely upgraded by a newer release; "
            "run that release (or newer) instead. Downgrades are not supported — "
            "restore the pg_dump taken before the upgrade if you need to go back."
        )
    return None


def _run_migrations() -> None:
    """Bring the database to the newest schema this build knows.

    An empty database is built from the baseline up; an existing one gets only
    the revisions it is missing. Serialised via a Postgres advisory lock so
    concurrent instances sharing a database can't race the DDL.
    """
    from alembic import command
    from alembic.config import Config
    from alembic.script import ScriptDirectory
    from sqlalchemy import inspect as sa_inspect

    cfg = Config(str(Path(__file__).resolve().parent.parent / "alembic.ini"))
    known = {rev.revision for rev in ScriptDirectory.from_config(cfg).walk_revisions()}
    insp = sa_inspect(engine)
    current = None
    if insp.has_table("alembic_version"):
        with engine.connect() as conn:
            current = conn.execute(text("SELECT version_num FROM alembic_version")).scalar()
    problem = _schema_problem(insp.has_table("users"), current, known)
    if problem:
        raise RuntimeError(problem)

    with engine.connect() as conn:
        conn.execute(text("SELECT pg_advisory_lock(0x7472616B)"))  # 'trak'
        try:
            command.upgrade(cfg, "head")
        finally:
            conn.execute(text("SELECT pg_advisory_unlock(0x7472616B)"))


def _create_postgres_indexes() -> None:
    """Create PostgreSQL-specific indexes not expressible via ORM declarations.

    These stay outside Alembic on purpose: they're idempotent, hand-tuned
    (partial/BRIN/GIN), and env.py's include_object hides them from
    autogenerate so it never proposes dropping them.
    """
    # pg_trgm for fuzzy POI search; btree_gist so a GiST index can carry
    # min_zoom alongside the POI's position (see idx_poi_search_geo below).
    from sqlalchemy import text as _text
    with engine.connect() as conn:
        conn.execute(_text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
        conn.execute(_text("CREATE EXTENSION IF NOT EXISTS btree_gist"))
        conn.commit()

    stmts = [
        "CREATE INDEX IF NOT EXISTS idx_dp_heatmap ON data_points (activity_id, recorded_at) WHERE lat IS NOT NULL AND lng IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_dp_recorded_brin ON data_points USING BRIN (recorded_at) WITH (pages_per_range = 32)",
        "CREATE INDEX IF NOT EXISTS idx_dp_activity_brin ON data_points USING BRIN (activity_id) WITH (pages_per_range = 32)",
        "CREATE INDEX IF NOT EXISTS idx_activities_device_sport_started ON activities (device_id, sport, started_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_activities_user_started ON activities (user_id, started_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_activities_user_vo2max ON activities (user_id, started_at) WHERE vo2max_estimate IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS ix_exercise_library_sport_relevance ON exercise_library USING GIN (sport_relevance jsonb_path_ops)",
        "CREATE INDEX IF NOT EXISTS ix_user_exercise_strength_exercise ON user_exercise_strength (exercise_name)",
        "CREATE INDEX IF NOT EXISTS idx_planned_workouts_completed_act ON planned_workouts (completed_activity_id) WHERE completed_activity_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_daily_metrics_user_date ON daily_metrics (user_id, date)",
        # Import de-duplication: (user, content hash), regardless of source.
        #
        # The unique constraint leads with user and source, which a lookup by
        # user and hash alone cannot use well — and that check runs for every
        # file imported. It is deliberately source-agnostic (a source-scoped
        # one once let 192 duplicate activities through; see
        # fit_import._insert_parsed), so the fix is this index rather than a
        # narrower query. A scan per file over a table that grows by one per
        # file is quadratic across a bulk import.
        "CREATE INDEX IF NOT EXISTS idx_imports_user_source_id ON imports (user_id, source_id)",
        # The backlog drain: every login and every /auth/sync-pending-imports
        # poll asks for this user's unprocessed rows. Partial, because
        # processed_at IS NULL is what makes it selective — 43 rows out of
        # 2,670 here, and the processed ones are never queried again.
        "CREATE INDEX IF NOT EXISTS idx_pending_imports_unprocessed "
        "ON pending_imports (user_id, created_at) WHERE processed_at IS NULL",
        # Exactly duplicates imports_source_source_id_key, the UNIQUE
        # constraint on the same two columns in the same order — same lookups,
        # twice the write cost and 1.3 MB of disk to keep them both current.
        "DROP INDEX IF EXISTS idx_imports_source",
        # Never used, and cannot be: `kind` appears only in NOT IN filters,
        # which a btree cannot serve. Zero scans over the lifetime of the
        # database (pg_stat_user_indexes, never reset), against 12 MB and a
        # write on every one of 1.15M POI rows each time the index rebuilds.
        "DROP INDEX IF EXISTS idx_poi_search_kind",
        # POI search indexes — single authoritative location for all poi_search indexes
        "CREATE INDEX IF NOT EXISTS idx_poi_search_fts "
        "ON poi_search USING GIN (to_tsvector('english', name))",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_poi_search_osm_id "
         "ON poi_search (osm_id) WHERE osm_id IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_poi_search_gnis_id "
         "ON poi_search (gnis_id) WHERE gnis_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_poi_search_name_trgm "
         "ON poi_search USING GIN (name gin_trgm_ops)",
        "CREATE INDEX IF NOT EXISTS idx_poi_search_min_zoom "
         "ON poi_search (min_zoom)",
        # The map's own index: every POI read is "what is in this box, down to
        # this importance", and this is the only shape that answers it in one
        # step.
        #
        # A btree on (lat, lng) cannot. B-trees order on one key, so a bounding
        # box degenerates into "scan the whole latitude band, then discard on
        # longitude": for a half-degree box over a mountain range the index handed back
        # 42,363 candidates to produce 4,339 rows. Putting min_zoom first
        # instead is no better — it matches 85.7% of the table, so the planner
        # rightly ignores it.
        #
        # GiST indexes the position as a point, so the box is a containment test
        # rather than a range scan, and btree_gist lets min_zoom ride in the
        # same tree so the importance cutoff is applied before any heap fetch.
        # Measured on 1.15M rows: 42,363 index rows -> 4,674, total 2,270
        # buffers -> 876. It costs ~100 MB and replaces the 54 MB btree below.
        "CREATE INDEX IF NOT EXISTS idx_poi_search_geo "
        "ON poi_search USING gist (min_zoom, point(lng, lat))",
        # Superseded by idx_poi_search_geo. Every bbox query now expresses
        # itself as a containment test, which a btree cannot serve at all.
        "DROP INDEX IF EXISTS idx_poi_search_lat_lng",
        # Remove old redundant plain B-tree name index (superseded by trgm index above)
        "DROP INDEX IF EXISTS idx_poi_search_name",
    ]
    with engine.connect() as conn:
        for sql in stmts:
            conn.execute(text(sql))
        conn.commit()
    logger.info("Performance indexes ensured (%d total)", len(stmts))


def _ensure_user_settings() -> None:
    """Give every account a user_settings row, then run its auto-calc.

    Every account, not the first: this once took `query(User).first()`, so on
    a household server only one person's auto thresholds were ever computed.
    """
    db = SessionLocal()
    try:
        for (user_id,) in db.query(User.id).order_by(User.id).all():
            if not db.query(UserSettings).filter_by(user_id=user_id).first():
                db.add(UserSettings(user_id=user_id))
                db.commit()
            recalculate_auto_values(db, user_id)
    finally:
        db.close()


def _recover_map_data() -> None:
    """Re-trigger any map data downloads that should be running but aren't.

    Called at startup after the DB is ready. Covers two scenarios:
    1. map_enabled=True in settings but planet_z7.pmtiles is missing
       (e.g. the map-data bind-mount was wiped).
    2. Regions marked 'installed' in the DB whose tile files no longer exist
       on disk — marks them as 'error' so the UI shows a re-download prompt.
    """
    import threading
    from app.models.user_settings import UserSettings as _US
    from app.services import region_registry, region_merger
    from app.services.global_overview import start_map_download
    from app.services.global_dem import start_dem_download

    data_dir = Path(settings.map_data_dir)

    # ── Global tile recovery ──────────────────────────────────────────────────
    db = SessionLocal()
    try:
        us = db.query(_US).first()
        map_enabled = us.map_enabled if us else False
    finally:
        db.close()

    if map_enabled:
        # Treat the overview as present if EITHER the z0-12 basemap or the legacy
        # z0-7 file exists, so the multi-GB z0-12 upgrade is never auto-triggered
        # by startup recovery — it's pulled explicitly when the operator opts in.
        # Complete, not merely present: an extract killed mid-download leaves a
        # full-size file with no header (see is_complete_archive).
        from app.services.pmtiles_extract import is_complete_archive
        overview_missing = not (
            is_complete_archive(data_dir / "planet_basemap.pmtiles")
            or (data_dir / "planet_z7.pmtiles").exists()
        )
        dem_missing = settings.dem_source_url and not is_complete_archive(
            data_dir / "planet_dem_z7.pmtiles")

        if overview_missing or dem_missing:
            logger.info(
                "Map data recovery: overview_missing=%s dem_missing=%s — re-queuing downloads",
                overview_missing, dem_missing,
            )
            if overview_missing:
                threading.Thread(target=start_map_download, daemon=True,
                                 name="map-recovery").start()
            if dem_missing:
                threading.Thread(target=start_dem_download, daemon=True,
                                 name="dem-recovery").start()

    # ── Interrupted builds ────────────────────────────────────────────────────
    # A region download is a daemon thread, and a restart takes it with no
    # record anywhere that the work stopped. Picked back up here rather than
    # left to go stale twenty minutes later — see resume_interrupted.
    try:
        from app.routes.regions.download import resume_interrupted
        resume_interrupted()
    except Exception:
        logger.exception("Could not resume interrupted region builds")

    # ── Region orphan check ───────────────────────────────────────────────────
    active = region_registry.list_active()
    orphaned = [
        r for r in active
        if r["status"] == "installed" and not region_registry.source_dir(r["id"]).exists()
    ]
    if orphaned:
        names = ", ".join(r["name"] for r in orphaned)
        logger.warning(
            "Map data recovery: %d region(s) missing files — marking for re-download: %s",
            len(orphaned), names,
        )
        for r in orphaned:
            region_registry.update_status(
                r["id"], "error",
                error="Region tile files missing — please re-download this area",
            )
        # Rebuild master from whatever regions still have files
        threading.Thread(target=region_merger.rebuild_master, daemon=True,
                         name="master-recovery").start()


def _ensure_map_dirs() -> None:
    """Create map-data subdirectories if missing (e.g. volume was wiped).

    Ownership is handled by the entrypoint script which runs as root on
    every container start. This is an idempotent safety net.
    """
    import os as _os
    base = Path(settings.map_data_dir)
    for sub in ("regions", "brouter/segments4", "fonts", "sprite"):
        _os.makedirs(base / sub, exist_ok=True)

_EXERCISE_LIBRARY_CACHE: dict | None = None


def get_exercise_library_cache() -> dict:
    """Return the cached exercise library dict. Seeds on first call."""
    global _EXERCISE_LIBRARY_CACHE
    if _EXERCISE_LIBRARY_CACHE is None:
        from app.models.strength import ExerciseLibrary
        db = SessionLocal()
        try:
            rows = db.query(ExerciseLibrary).all()
            _EXERCISE_LIBRARY_CACHE = {
                r.name: {
                    "garmin_category":  r.garmin_category,
                    "garmin_subtype":   r.garmin_subtype,
                    # has_animation is consumed by the planner to default
                    # auto-generated workouts to animated-only movements
                    # (overridable per-row via _is_custom or user preference).
                    "has_animation":    bool(r.has_animation),
                    "primary_muscles":  r.primary_muscles or [],
                    "secondary_muscles": r.secondary_muscles or [],
                    "equipment":        r.equipment or ["bodyweight"],
                    "movement_pattern": r.movement_pattern,
                    "sport_relevance":  r.sport_relevance or {},
                    "difficulty":       r.difficulty,
                    "is_compound":      r.is_compound,
                    # Coaching cues embedded into generated strength steps.
                    "cues":             r.cues or [],
                }
                for r in rows
            }
            logger.info("exercise_library cache populated (%d exercises)",
                        len(_EXERCISE_LIBRARY_CACHE))
        finally:
            db.close()
    return _EXERCISE_LIBRARY_CACHE


_primary_lock_fh = None


def _is_primary_worker() -> bool:
    """With multiple uvicorn workers the lifespan runs in every process. One-time
    work (DB seed, cache warming) must run in exactly ONE worker. The first
    worker to grab an exclusive flock is the primary; others skip that work
    but still serve every endpoint (lazy caches populate on demand)."""
    global _primary_lock_fh
    try:
        import fcntl
        fh = open("/tmp/tracks-primary.lock", "w")
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        _primary_lock_fh = fh  # keep the fd open to hold the lock for the process lifetime
        return True
    except OSError:
        return False


_STARTUP_LOCK = "/tmp/tracks-startup.lock"


def _startup_barrier():
    """Hold every worker at startup until the schema exists.

    Uvicorn starts serving from a worker the moment its lifespan returns, and
    a secondary worker's returns at once — while the primary may still be
    building the schema of a brand-new database. On a fresh install the first
    request (the admin's own setup form) could land in that window and fail
    against a table that did not exist yet.

    So every worker takes this lock before it decides anything, and the
    primary keeps it until the schema and seed data are in place. Whoever gets
    it first and wins the primary flock does the work; each later worker gets
    it only after that, finds the primary taken, and starts serving against a
    finished database. A primary that dies part-way releases both locks with
    its process, so the next worker in line becomes primary and does it again
    — every step is idempotent. Blocking the event loop here is harmless:
    nothing is being served yet.
    """
    import fcntl
    # Opened read-only when it exists: flock needs no write access, and /tmp
    # is sticky, so the kernel (fs.protected_regular) refuses an O_CREAT open
    # of a file another user owns there — even to root. That is the case when
    # `docker exec backend pytest` runs as root beside a server running as
    # HOST_UID, which created the file first.
    try:
        fd = os.open(_STARTUP_LOCK, os.O_RDONLY)
    except FileNotFoundError:
        fd = os.open(_STARTUP_LOCK, os.O_RDONLY | os.O_CREAT, 0o644)
    fh = os.fdopen(fd)
    fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
    return fh


@asynccontextmanager
async def lifespan(app: FastAPI):
    barrier = _startup_barrier()
    try:
        primary = _is_primary_worker()
        seeding = primary and not os.environ.get("TRACKS_SKIP_SEED")
        if seeding:
            _run_migrations()
            _create_postgres_indexes()
            with engine.connect() as conn:
                seed_all(conn)
            _ensure_user_settings()
    finally:
        barrier.close()   # releases the lock for the next worker
    if seeding:
        _ensure_map_dirs()
        get_exercise_library_cache()
        warm_training_load_cache()
        warm_dashboard_cache()

        import threading
        from app.services.poi_indexer import full_reindex
        threading.Thread(target=full_reindex, daemon=True,
                         name="poi-reindex").start()

        # Generate contours only if the DEM exists and contours are missing or
        # stale (older than the DEM). Avoids re-deriving from a multi-GB DEM on
        # every restart.
        from app.services.contour_generator import regenerate_contours
        from app.services.global_download_tracker import global_download_tracker
        _dem = Path(settings.map_data_dir, "master_dem.pmtiles")
        _contours = Path(settings.map_data_dir, "master_contours.pmtiles")
        if _dem.exists() and (
            not _contours.exists()
            or _contours.stat().st_mtime < _dem.stat().st_mtime
        ):
            def _regen_contours_tracked():
                global_download_tracker.register("contours", "Generating contour lines", "")
                global_download_tracker.start("contours")
                try:
                    regenerate_contours(progress_cb=lambda done, total: (
                        global_download_tracker.set_progress(
                            "contours", done / max(total, 1) * 100, f"{done} / {total} tiles")))
                    global_download_tracker.complete("contours")
                except Exception as exc:
                    global_download_tracker.fail("contours", str(exc))
                    logger.exception("Contour generation failed")
            threading.Thread(target=_regen_contours_tracked, daemon=True,
                             name="contour-gen").start()

        _recover_map_data()

        # Source long-trail emblem images (non-blocking, idempotent).
        trail_logos.source_logos_async()

        # Build the USGS symbol sprite sheet (non-blocking, idempotent — only
        # regenerates when the symbol code version changes or the sheet is gone).
        from app.services.sprite_builder import build_sprite_async
        build_sprite_async()

        # Build the region-independent global long-route overview if missing, so
        # long trails show everywhere (not only in downloaded regions). Write to a
        # temp file and atomically rename, so a restart mid-build never leaves a
        # truncated archive that `not exists()` would treat as "done" and serve.
        from app.services.trail_builder import build_global_routes
        from app.services import region_merger
        from app.services.route_profile import precompute_route_profiles
        _routes = Path(settings.map_data_dir, "master_routes.pmtiles")

        def _precompute_profiles():
            try:
                precompute_route_profiles()
            except Exception:
                logger.exception("Route profile precompute failed")

        def _build_routes():
            tmp = _routes.with_suffix(".building.pmtiles")
            global_download_tracker.register("routes", "Building long-trail overview", "")
            global_download_tracker.start("routes")
            try:
                build_global_routes(str(tmp), progress_cb=lambda pct, detail: (
                    global_download_tracker.set_progress("routes", pct, detail)))
                tmp.replace(_routes)
                global_download_tracker.complete("routes")
                region_merger.write_reload_trigger()
                _precompute_profiles()   # cache elevation profiles for the fresh routes
            except Exception as exc:
                global_download_tracker.fail("routes", str(exc))
                logger.exception("Global routes build failed")
                tmp.unlink(missing_ok=True)

        if not _routes.exists():
            threading.Thread(target=_build_routes, daemon=True, name="global-routes").start()
        else:
            # Routes already built — precompute any still-missing section profiles
            # so the detail panel opens instantly (idempotent; skips cached ones).
            threading.Thread(target=_precompute_profiles, daemon=True, name="route-profiles").start()

    yield


app = FastAPI(title="Tracks", lifespan=lifespan)

app.add_middleware(CacheControlMiddleware)
app.add_middleware(GZipMiddleware, minimum_size=1024)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

_auth_dep = [Depends(require_auth)]

app.include_router(capabilities.router)      # public, polled before login for version skew
app.include_router(version_routes.router)    # authed; the version panels, and records the app's version
app.include_router(auth_router.router)
app.include_router(activities.router,        dependencies=_auth_dep)
app.include_router(coaching.router,          dependencies=_auth_dep)
app.include_router(devices.router,           dependencies=_auth_dep)
app.include_router(metrics.router,           dependencies=_auth_dep)
from app.routes import waypoints as waypoints_routes  # noqa: E402
app.include_router(waypoints_routes.router, dependencies=_auth_dep)
app.include_router(users.router,             dependencies=_auth_dep)
app.include_router(sync_agents.router,       dependencies=_auth_dep)
app.include_router(training_plan.router,     dependencies=_auth_dep)
app.include_router(health.router,            dependencies=_auth_dep)
app.include_router(training_plan.ics_router)
app.include_router(sync_router)
app.include_router(sync_ingest.router)
# Shares the /sync prefix with sync_ingest but NOT its auth: ingest is guarded
# per-route by require_sync_agent (an agent holds only a public key and can
# upload but never read), while /sync/delta serves decrypted user data and so
# takes a JWT like any other read endpoint.
app.include_router(sync.router,             dependencies=_auth_dep)
app.include_router(fit_upload.router,       dependencies=_auth_dep)
app.include_router(device_sync.router,      dependencies=_auth_dep)
app.include_router(meals.router,            dependencies=_auth_dep)
app.include_router(fuel.router,             dependencies=_auth_dep)
app.include_router(medications.router,      dependencies=_auth_dep)
app.include_router(strength_api.router,     dependencies=_auth_dep)
app.include_router(flexibility_api.router,  dependencies=_auth_dep)
app.include_router(music.router,            dependencies=_auth_dep)
app.include_router(music.music_sync_router)   # sync-agent token guarded, like course_sync_router
app.include_router(garmin_api.router,        dependencies=_auth_dep)
app.include_router(workouts.router,          dependencies=_auth_dep)
app.include_router(regions.router,            dependencies=_auth_dep)
app.include_router(poi_routes.router,          dependencies=_auth_dep)
app.include_router(routes_api.router,          dependencies=_auth_dep)
app.include_router(courses_api.router,          dependencies=_auth_dep)
app.include_router(courses_api.course_sync_router)   # sync-secret guarded, like sync_router
from app.routes.waypoints_sync import waypoint_sync_router  # noqa: E402
app.include_router(waypoint_sync_router)              # sync-secret guarded, like course_sync_router
app.include_router(fonts.router)             # public, fetched by MapLibre
app.include_router(sprite.router)            # public, fetched by MapLibre
app.include_router(trail_logos.router)       # public, fetched by MapLibre (emblems)
app.include_router(tiles.router)             # public, fetched by frontend for cache busting
app.include_router(map_style.router)          # public, the style document both clients render from
app.include_router(contours.router,          dependencies=_auth_dep)  # contour regeneration trigger
app.include_router(gnis_routes.router,          dependencies=_auth_dep)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}
