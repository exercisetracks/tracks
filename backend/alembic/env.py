# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Alembic migration environment for Tracks.

Reads the database URL from app.config.settings (the same .env-driven source
the app uses) and targets the app's model metadata, so
`alembic revision --autogenerate` diffs the models against the live schema.

The baseline is the schema Tracks 1.0.0 shipped with, and it is never edited
again: people's databases were built from it. Every change since is a
revision on top. To change the schema:
    1. Edit the SQLAlchemy models (and add any new model module to the
       imports below).
    2. `docker exec backend alembic revision --autogenerate -m "what changed"`
       against a database already at head.
    3. Read the generated file. Autogenerate cannot see renames (it emits a
       drop and an add, losing the column's data), data backfills, or the
       hand-written extension/sequence lines the baseline carries — fix those
       by hand.
    4. `tests/test_migrations.py` fails until the revisions and the models
       agree, so the suite tells you if step 2 or 3 missed something.
Startup applies pending revisions automatically (main._run_migrations).
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.config import settings
from app.database import Base

# Import every model module so all tables register on Base.metadata.
import app.models.activity  # noqa: F401
import app.models.coaching  # noqa: F401
import app.models.custom_track  # noqa: F401
import app.models.fuel  # noqa: F401
import app.models.flexibility  # noqa: F401
import app.models.health  # noqa: F401
import app.models.imports  # noqa: F401
import app.models.map_region  # noqa: F401
import app.models.meals  # noqa: F401
import app.models.medications  # noqa: F401
import app.models.metrics  # noqa: F401
import app.models.poi_search  # noqa: F401
# Every model module belongs here, and the list being incomplete is not a
# cosmetic problem: autogenerate compares the database against whatever
# Base.metadata happens to know about, so a table whose model was not
# imported looks like a table that should be DROPPED. The first
# autogenerate run after street_junctions was added proposed dropping
# device_keys, refresh_tokens, waypoints, sync tables and both music
# tables — every one of them live, and two of them holding the keys to a
# user's encrypted history. Add new model modules here as they are written.
import app.models.device_keys  # noqa: F401
import app.models.music  # noqa: F401
import app.models.refresh_tokens  # noqa: F401
import app.models.streets  # noqa: F401
import app.models.sync  # noqa: F401
import app.models.waypoint  # noqa: F401
import app.models.strength  # noqa: F401
import app.models.training_plan  # noqa: F401
import app.models.upload_jobs  # noqa: F401
import app.models.user_keys  # noqa: F401
import app.models.sync_agents  # noqa: F401
import app.models.user_settings  # noqa: F401
import app.models.workout  # noqa: F401

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

config.set_main_option("sqlalchemy.url", settings.database_url)

target_metadata = Base.metadata


def include_object(obj, name, type_, reflected, compare_to):
    """Keep autogenerate away from the hand-tuned indexes.

    _create_postgres_indexes() in main.py owns the partial/BRIN/GIN indexes
    (plus the pg_trgm extension). They exist in the database but not in the
    model metadata, so without this guard every autogenerate run would propose
    dropping them.
    """
    if type_ == "index" and reflected and compare_to is None:
        return False
    return True


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        include_object=include_object,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    # A caller that already holds a connection (tests/test_migrations.py,
    # migrating a scratch database) passes it in rather than have this
    # connect to the app's own database from the settings.
    given = config.attributes.get("connection")
    if given is not None:
        _run_on(given)
        return
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        _run_on(connection)


def _run_on(connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        include_object=include_object,
    )
    with context.begin_transaction():
        context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
