# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""goal completed_at

A goal can be marked done: the day it was, which also takes it out of the
active slot (api/coaching/goals.py). Nullable, so every existing goal reads
as not done and nothing needs backfilling.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is left out.

Revision ID: 48d2c95e4cab
Revises: ae39d1ee607b
Create Date: 2026-11-09 02:42:54.026680

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '48d2c95e4cab'
down_revision = 'ae39d1ee607b'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('training_goals', sa.Column('completed_at', sa.Date(), nullable=True))


def downgrade() -> None:
    op.drop_column('training_goals', 'completed_at')
