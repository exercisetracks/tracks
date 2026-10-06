# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""settings weather location

Where the phone last was, synced so the server's watch-forecast fallback uses
the same up-to-date position the phone does. See UserSettings.weather_location.

Autogenerate also proposed recreating idx_imports_source; that is a dev-database
artefact (app/main.py drops it at startup as a duplicate of a unique
constraint) and is deliberately left out.

Revision ID: f551740dd90c
Revises: 0001baseline
Create Date: 2026-10-06 18:39:43.043852

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = 'f551740dd90c'
down_revision = '0001baseline'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('user_settings', sa.Column(
        'weather_location',
        sa.JSON().with_variant(postgresql.JSONB(astext_type=sa.Text()), 'postgresql'),
        nullable=True,
    ))


def downgrade() -> None:
    op.drop_column('user_settings', 'weather_location')
