# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Shared fixtures for all tests.

Database strategy: a real PostgreSQL database, on the same server the app uses
but a separate database of its own (`<app database>_test`).

It used to be SQLite in-memory, which was fast and wrong in ways that mattered.
Postgres-only SQL could not be tested at all — the POI endpoints use `to_tsvector`,
trigram operators and geometry, so the search path was simply untested, and the
one endpoint with coverage had it only because it happened to avoid them. Nothing
caught a type or constraint the two engines disagree about, and every test ran
against an engine that is not the one anybody's data lives in.

Safety: the test database name must end in `_test`, asserted below before any
fixture is allowed to create or delete anything. These fixtures truncate tables
between tests; pointing them at the real database would destroy a user's health
history, so this is a hard failure rather than a convention.

Lifespan strategy: TRACKS_SKIP_SEED=1 prevents seed operations at startup. The
watcher and _ensure_user_settings are patched out; tests exercise the API layer
only. The Postgres-specific index DDL *is* run once against the test database —
it is real DDL that nothing else checks, so letting it run here is free coverage.

Auth strategy: by default, no password is set (open-access mode), so all
authenticated routes accept requests without a token.
"""

import os
import tempfile
from urllib.parse import urlsplit, urlunsplit

import sqlalchemy


def _test_database_url() -> str:
    """Where the tests' own database lives.

    Derived from the app's DATABASE_URL by swapping the database name, so the
    credentials come from the environment the tests already run in and are
    never written down here. TRACKS_TEST_DATABASE_URL overrides it outright.
    """
    explicit = os.environ.get("TRACKS_TEST_DATABASE_URL")
    if explicit:
        return explicit

    app_url = os.environ.get("DATABASE_URL")
    if not app_url:
        raise RuntimeError(
            "No DATABASE_URL to derive a test database from. The suite runs "
            "against PostgreSQL — run it in the backend container "
            "(`docker exec backend pytest`), or set TRACKS_TEST_DATABASE_URL."
        )
    parts = urlsplit(app_url)
    if not parts.scheme.startswith("postgresql"):
        raise RuntimeError(
            f"DATABASE_URL is {parts.scheme!r}; the suite needs PostgreSQL so that "
            "what it tests is what actually runs."
        )
    return urlunsplit(parts._replace(path=f"/{parts.path.lstrip('/')}_test"))


def _with_psycopg2(url: str) -> str:
    """The driver the app pins (see Settings._pin_postgres_driver): a bare
    postgresql:// URL would get psycopg 3 from SQLAlchemy 2.1."""
    if url.startswith("postgresql://"):
        return "postgresql+psycopg2://" + url[len("postgresql://"):]
    return url


TEST_DATABASE_URL = _with_psycopg2(_test_database_url())

# The guard. Everything below truncates and drops; aimed at the real database it
# would delete a user's activities, sleep and health records. Nothing is
# imported and no fixture runs until this holds.
_TEST_DB_NAME = urlsplit(TEST_DATABASE_URL).path.lstrip("/")
if not _TEST_DB_NAME.endswith("_test"):
    raise RuntimeError(
        f"Refusing to run: test database {_TEST_DB_NAME!r} does not end in '_test'. "
        "These fixtures delete every row in every table between tests."
    )


def _create_test_database() -> None:
    """Create the test database if it is not there yet.

    CREATE DATABASE cannot run inside a transaction or from a connection to the
    database being created, so this connects to the server's default database
    to do it.
    """
    parts = urlsplit(TEST_DATABASE_URL)
    admin_url = urlunsplit(parts._replace(path="/postgres"))
    admin = sqlalchemy.create_engine(admin_url, isolation_level="AUTOCOMMIT")
    try:
        with admin.connect() as conn:
            exists = conn.execute(
                sqlalchemy.text("SELECT 1 FROM pg_database WHERE datname = :n"),
                {"n": _TEST_DB_NAME},
            ).scalar()
            if not exists:
                conn.execute(sqlalchemy.text(f'CREATE DATABASE "{_TEST_DB_NAME}"'))
    finally:
        admin.dispose()


_create_test_database()

os.environ["DATABASE_URL"] = TEST_DATABASE_URL
os.environ["JWT_SECRET"] = "test-secret-key-for-testing-only"
os.environ["SESSION_CACHE_KEY"] = "test-session-cache-key-for-testing-only"
os.environ["TRACKS_SKIP_SEED"] = "1"
os.environ["DEBUG"] = "true"
# app.services.object_storage writes blobs under this — the real default
# (/fit-files) doesn't exist/isn't writable outside the container.
os.environ["FIT_FILES_DIR"] = tempfile.mkdtemp(prefix="tracks-test-fit-files-")

from unittest.mock import patch

import fakeredis
import pytest
from fastapi.testclient import TestClient

from app.database import Base, get_db, engine as _app_engine, SessionLocal

# Register all model tables with Base.metadata before importing app.main.
from app.models import activity, coaching, health              # noqa: E402, F401
from app.models import imports, meals, medications, metrics    # noqa: E402, F401
from app.models import music                                   # noqa: E402, F401
from app.models import strength, training_plan, upload_jobs    # noqa: E402, F401
from app.models import sync, refresh_tokens                    # noqa: E402, F401
from app.models import device_keys                             # noqa: E402, F401
from app.models import streets                                 # noqa: E402, F401

from app.main import app                                       # noqa: E402
from app.models.activity import User, Device, UserDevice        # noqa: E402
from app.models.user_settings import UserSettings              # noqa: E402

# Celery tasks (app.tasks.imports.process_pending_imports) run synchronously,
# in-process, against fake_redis below — no real broker/worker in tests.
# eager_propagates surfaces a task's exception at the .delay() call site
# instead of silently swallowing it (task_ignore_result=True in
# celery_app.py means .delay() wouldn't otherwise raise even in eager mode).
from app.tasks.celery_app import celery_app                     # noqa: E402
celery_app.conf.task_always_eager = True
celery_app.conf.task_eager_propagates = True


def _override_get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# NOTE: _override_get_db above is kept only for tests that install it
# themselves. The `client` fixture uses the shared-session override below —
# see the comment on that fixture for why.


@pytest.fixture(scope="session", autouse=True)
def create_tables():
    """The schema, once per run.

    Extensions come first: pg_trgm and btree_gist are what the POI search and
    bbox indexes are built on, and CREATE INDEX fails without them. Creating
    them here is also the only place anything checks that the app's own startup
    DDL is valid — see _create_postgres_indexes, run below against the same
    tables the app would.
    """
    with _app_engine.connect() as conn:
        conn.execute(sqlalchemy.text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
        conn.execute(sqlalchemy.text("CREATE EXTENSION IF NOT EXISTS btree_gist"))
        conn.commit()

    Base.metadata.drop_all(bind=_app_engine)
    Base.metadata.create_all(bind=_app_engine)

    # The hand-tuned indexes that live outside Alembic. Running them here means
    # a typo in that list fails the suite rather than the next deploy, and it
    # means tests exercise the same index set production has.
    from app.main import _create_postgres_indexes
    _create_postgres_indexes()

    yield
    Base.metadata.drop_all(bind=_app_engine)


_sequence_reset_sql: str | None = None


def _restart_sequences(connection) -> None:
    """Send every identity sequence back to 1, inside the test's transaction.

    Rolling a transaction back does not undo `nextval` — sequences are
    deliberately non-transactional, so that concurrent inserts never block on
    each other. Left alone, ids climb for the whole run, and a test that says
    `device_id=1` because that is what the `user` fixture made passes first and
    fails forever after. Eleven test modules were written that way against a
    database that started empty every time; this keeps that promise.

    `ALTER SEQUENCE ... RESTART`, unlike `setval()`, *is* transactional, so the
    reset is undone with everything else and each test starts from the same
    place. The statement list is built once — the set of sequences only changes
    when the schema does.
    """
    global _sequence_reset_sql
    if _sequence_reset_sql is None:
        names = [
            r[0] for r in connection.execute(sqlalchemy.text(
                "SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'"
            ))
        ]
        _sequence_reset_sql = "; ".join(
            f'ALTER SEQUENCE "{n}" RESTART WITH 1' for n in names
        )
    if _sequence_reset_sql:
        # One round trip for all of them; psycopg2 accepts a multi-statement
        # string and the whole thing is inside the transaction either way.
        connection.exec_driver_sql(_sequence_reset_sql)


def _truncate_everything() -> None:
    """Empty every table, for the tests that cannot run inside a transaction."""
    names = [f'"{t.name}"' for t in Base.metadata.sorted_tables]
    if not names:
        return
    with _app_engine.connect() as conn:
        conn.execute(sqlalchemy.text(
            f"TRUNCATE {', '.join(names)} RESTART IDENTITY CASCADE"
        ))
        conn.commit()


@pytest.fixture
def db(request, create_tables):
    """The one session a test and its requests both use, inside a transaction
    that is rolled back when the test ends.

    Isolation by rollback rather than by cleanup. The obvious alternative —
    TRUNCATE every table before each test — is correct but costs about 0.7s a
    test here, because TRUNCATE takes an ACCESS EXCLUSIVE lock and rewrites the
    file node of all ~65 tables whether or not they hold anything. That is
    eleven minutes of the suite spent emptying tables that are already empty.
    A transaction that is never committed leaves nothing to clean up.

    `join_transaction_mode="create_savepoint"` is what makes that survive
    application code: endpoints call `db.commit()` freely, and each one
    releases a SAVEPOINT inside this outer transaction instead of ending it.
    The data stays visible for the rest of the test and disappears on rollback.

    Why a single session is shared with the request path (see `client`): two
    sessions would be two connections, and the second could not see anything
    this one has written but not committed — which, here, is everything.

    Application code that opens its own session is handled by rebinding the
    `SessionLocal` factory itself for the duration of the test. Every module
    that did `from app.database import SessionLocal` holds a reference to that
    one sessionmaker object, so reconfiguring it reaches all of them at once —
    and any module added later is covered without being listed anywhere. Left
    on the engine, such a session would take its own pooled connection, sit
    outside this transaction, and commit for real: rows that survive the
    rollback and leak into every test that follows.

    The escape hatch: `@pytest.mark.real_transaction`. Code that manages its own
    SAVEPOINTs — `device_resolution.get_or_create_device` uses one so two agents
    registering the same device do not race — nests them inside the savepoint
    this fixture is already standing on, and the releases stop lining up
    ("savepoint sa_savepoint_N does not exist"). That is the harness's problem,
    not the code's, so those tests get a real transaction and TRUNCATE cleanup
    instead. One test needs it today; the cost is only borne by tests that ask.
    """
    if request.node.get_closest_marker("real_transaction"):
        session = SessionLocal()
        try:
            yield session
        finally:
            session.close()
            _truncate_everything()
        return

    connection = _app_engine.connect()
    transaction = connection.begin()
    _restart_sequences(connection)
    SessionLocal.configure(bind=connection, join_transaction_mode="create_savepoint")
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()
        SessionLocal.configure(bind=_app_engine, join_transaction_mode="conditional_savepoint")
        if transaction.is_active:
            transaction.rollback()
        connection.close()


@pytest.fixture(autouse=True)
def in_a_transaction(db):
    """Every test runs inside the `db` fixture's transaction, whether it asks
    for a session or not.

    Autouse because isolation cannot be opt-in: a test that writes through the
    API without requesting `db` would otherwise commit for real and leave its
    rows behind for everything that follows.

    This replaces a fixture that monkeypatched `SessionLocal` in each of eleven
    named modules, because each had bound the name at import time and patching
    `app.database.SessionLocal` would have missed them. Rebinding the
    sessionmaker itself (see `db`) reaches every one of them through the single
    object they all share, and does not need the list — which had to be kept in
    step with `grep -rl "from app.database import.*SessionLocal"` by hand, and
    whose reward for falling behind was a test that failed only in suite order.
    """
    return db


@pytest.fixture(autouse=True)
def fresh_sync_clock():
    """Each test starts with no cached server clock.

    The clock caches its node id per process, but the row it came from is
    rolled back with each test; left cached, a later test would stamp with a
    node id its own database has never heard of.
    """
    from app.sync import store
    store._reset_clock_for_tests()
    yield
    store._reset_clock_for_tests()


@pytest.fixture(autouse=True)
def fake_redis(monkeypatch):
    """No real Redis in tests — swap the one shared client (crypto session
    cache, setup-complete flag, login rate limiter) for an in-memory fake.
    Function-scoped, so every test gets a fresh, empty cache (no cross-test
    bleed-through)."""
    from app.services import redis_client
    fake = fakeredis.FakeRedis()
    monkeypatch.setattr(redis_client, "_client", fake)
    return fake


@pytest.fixture
def client(db):
    """A TestClient whose requests use the SAME session as the `db` fixture.

    They used to be separate SessionLocal() instances. Under SQLite's
    StaticPool every session shares one DBAPI connection, so two sessions
    interleave transactions on it and a commit made by one was not reliably
    visible to the other. The symptom was subtle and load-bearing: after
    `POST /auth/setup`, a request-scoped session could not see the admin row it
    had just written, so `require_auth` kept taking its open-access branch and
    handed back the first user for *every* guarded route. Auth was effectively
    disabled for the whole suite, and tests asserting 401 only passed because
    `require_crypto_session` rejected them for a different reason.

    One session removes the visibility question entirely. The trade is that a
    request and its test share a transaction, so an endpoint that rolls back
    also discards test setup — worth it to have auth actually exercised.
    """
    def _shared_session():
        # No close(): the `db` fixture owns this session's lifetime.
        yield db

    app.dependency_overrides[get_db] = _shared_session
    with patch("app.main._ensure_user_settings"):
        with TestClient(app) as c:
            yield c
    app.dependency_overrides.clear()


@pytest.fixture
def user(db):
    """Default user in open-access mode (no password set)."""
    u = User(name="Test User")
    db.add(u)
    db.flush()
    us = UserSettings(user_id=u.id, units="metric", timezone="UTC", hidden_sports=[])
    db.add(us)
    # Create a dummy device so activity queries work (list_activities
    # filters by claimed device IDs).
    dev = Device(serial_number="test-serial", manufacturer="test", manufacturer_id=1)
    db.add(dev)
    db.flush()
    db.add(UserDevice(user_id=u.id, device_id=dev.id))
    db.commit()
    return u


@pytest.fixture(autouse=True)
def silence_post_import_jobs(monkeypatch):
    """app.services.fit_import's post-import jobs (cache warm, workout
    match, plan refresh) spawn daemon threading.Thread/Timer instances that
    open their own SessionLocal() against the SAME shared SQLite StaticPool
    connection tests use — a leftover thread from one test can still be
    running when the next test's assertions query that connection, causing
    flaky, hard-to-reproduce read failures unrelated to whatever's actually
    under test. No current test asserts on these jobs' side effects, so
    silence them everywhere rather than patching them per-test."""
    from app.services import fit_import
    monkeypatch.setattr(fit_import, "_schedule_cache_warm", lambda: None)
    monkeypatch.setattr(fit_import, "_schedule_workout_match", lambda *a, **kw: None)
    monkeypatch.setattr(fit_import, "_schedule_plan_refresh", lambda *a, **kw: None)


@pytest.fixture(autouse=True)
def clear_global_state():
    """Reset auth state between tests so test ordering (the suite runs under
    pytest-randomly) can't cause cross-test bleed-through: the login
    rate-limiter (keyed by IP — every TestClient request shares one IP, so
    failed-login counts would otherwise accumulate across tests) and the
    setup-complete flag. The metrics/heatmap caches don't need resetting
    here — they're Redis-backed now (see app.api.metrics.caching,
    app.api.activities.heatmap_cache), and fake_redis above already gives
    every test a fresh, empty store with nothing to reset."""
    from app.api import auth as auth_module
    from app import auth as auth_core

    def _reset():
        auth_module._reset_all()
        auth_core._reset_setup_cache()

    _reset()
    yield
    _reset()


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "real_transaction: run this test against a real committing transaction "
        "with TRUNCATE cleanup, instead of the usual rollback isolation. For "
        "tests exercising code that manages its own SAVEPOINTs — see the `db` "
        "fixture.",
    )
