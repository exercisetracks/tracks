# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""drop hidden_sports

Hidden sports are gone: every sport counts toward every metric, chart and
training load, for everyone. Nothing reads the column any more, and a phone
still sending the field has it refused per field (R_UNKNOWN), not the change.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: f91b5e81f7c6
Revises: eb76f73b0a25
Create Date: 2026-10-07 14:20:04.053439

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = 'f91b5e81f7c6'
down_revision = 'eb76f73b0a25'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_column('user_settings', 'hidden_sports')


def downgrade() -> None:
    op.add_column('user_settings', sa.Column(
        'hidden_sports', postgresql.JSONB(astext_type=sa.Text()),
        autoincrement=False, nullable=False, server_default=sa.text("'[]'::jsonb"),
    ))
