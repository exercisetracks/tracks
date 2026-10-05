# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Alembic revisions build exactly the schema the models describe.

The rest of the suite builds its tables with `Base.metadata.create_all`, which
is fast and always matches the models — and so says nothing about the path a
real install takes, which is the baseline plus every revision since. Those
two drift apart the moment someone edits a model and forgets the revision, or
hand-edits a generated one wrongly, and the first to find out would be an
operator whose upgrade left a column missing. This builds a scratch database
the way an install does and diffs it against the models.
"""

from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import pytest
import sqlalchemy
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory

from app.database import Base
from app.main import _ALEMBIC_BASELINE, _schema_problem
from tests.conftest import TEST_DATABASE_URL

_BACKEND = Path(__file__).resolve().parent.parent


def _config() -> Config:
    return Config(str(_BACKEND / "alembic.ini"))


@pytest.fixture
def scratch_engine():
    """A database of its own, created empty and dropped afterwards.

    Not the shared test database: that one is built by create_all, and
    migrating it would test nothing. The name still ends in `_test`, for the
    same reason conftest insists on it.
    """
    parts = urlsplit(TEST_DATABASE_URL)
    name = parts.path.lstrip("/").removesuffix("_test") + "_migrations_test"
    admin = sqlalchemy.create_engine(
        urlunsplit(parts._replace(path="/postgres")), isolation_level="AUTOCOMMIT"
    )
    with admin.connect() as conn:
        conn.execute(sqlalchemy.text(f'DROP DATABASE IF EXISTS "{name}"'))
        conn.execute(sqlalchemy.text(f'CREATE DATABASE "{name}"'))
    engine = sqlalchemy.create_engine(urlunsplit(parts._replace(path=f"/{name}")))
    try:
        yield engine
    finally:
        engine.dispose()
        with admin.connect() as conn:
            conn.execute(sqlalchemy.text(f'DROP DATABASE IF EXISTS "{name}"'))
        admin.dispose()


@pytest.mark.real_transaction
def test_the_migrated_schema_matches_the_models(scratch_engine):
    """A model change without a matching revision fails here, not on upgrade."""
    cfg = _config()
    with scratch_engine.begin() as conn:
        cfg.attributes["connection"] = conn
        command.upgrade(cfg, "head")

    # The same filter autogenerate uses: the hand-tuned indexes main.py makes
    # outside Alembic are not drift. A fresh database has none of them, but
    # applying the filter keeps this test and `alembic revision` agreeing on
    # what counts.
    def include_object(obj, name, type_, reflected, compare_to):
        return not (type_ == "index" and reflected and compare_to is None)

    with scratch_engine.connect() as conn:
        mc = MigrationContext.configure(
            conn, opts={"include_object": include_object, "compare_type": True}
        )
        diff = compare_metadata(mc, Base.metadata)
    assert diff == [], (
        "The models and the Alembic revisions disagree. Generate a revision with "
        "`docker exec backend alembic revision --autogenerate -m \"...\"` and read it. "
        f"Differences: {diff}"
    )


def test_the_baseline_is_the_one_root_revision():
    """Every install starts from the 1.0.0 baseline; a second root would fork history."""
    script = ScriptDirectory.from_config(_config())
    assert script.get_bases() == [_ALEMBIC_BASELINE]
    assert len(script.get_heads()) == 1, "Two heads: merge them with `alembic merge heads`."


def test_an_empty_database_may_be_migrated():
    assert _schema_problem(False, None, {_ALEMBIC_BASELINE}) is None


def test_a_database_at_a_known_revision_may_be_migrated():
    assert _schema_problem(True, _ALEMBIC_BASELINE, {_ALEMBIC_BASELINE}) is None


def test_a_database_from_before_the_release_is_refused():
    """Tables with no revision predate 1.0.0; upgrading them would fail half-way."""
    assert "predates" in _schema_problem(True, None, {_ALEMBIC_BASELINE})


def test_a_database_from_a_newer_release_is_refused():
    """Pinning an older image after an upgrade must stop at startup, not mid-request."""
    assert "newer release" in _schema_problem(True, "9999future", {_ALEMBIC_BASELINE})
