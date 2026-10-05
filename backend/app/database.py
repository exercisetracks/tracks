# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import JSON, create_engine
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import sessionmaker, DeclarativeBase
from sqlalchemy.pool import StaticPool
from app.config import settings

if "sqlite" in settings.database_url:
    engine = create_engine(
        settings.database_url,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
else:
    # Sized per worker process: every uvicorn worker gets its own engine, so the
    # stack-wide ceiling is workers × (pool_size + max_overflow). Postgres caps
    # at max_connections=100 by default — 8 workers × 10 = 80 stays under it,
    # where the old 15+20 could hit 280 and start throwing OperationalError.
    engine = create_engine(
        settings.database_url,
        pool_size=4,
        max_overflow=6,
        pool_pre_ping=True,
        pool_recycle=3600,
    )
SessionLocal = sessionmaker(bind=engine)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# Use JSONB on PostgreSQL, JSON on SQLite (for tests)
PJson = JSON().with_variant(JSONB(), "postgresql")
