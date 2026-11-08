# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""race plan intensity

How hard to race against the prediction, 0.90–1.10; null is 1.0, as
predicted. See race_helpers.race_intensity and race_plan.sport_plan.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is left out.

Revision ID: ae39d1ee607b
Revises: de402f9bdb85
Create Date: 2026-11-08

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'ae39d1ee607b'
down_revision = 'de402f9bdb85'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('race_plans', sa.Column('intensity', sa.Float(), nullable=True))


def downgrade() -> None:
    op.drop_column('race_plans', 'intensity')
