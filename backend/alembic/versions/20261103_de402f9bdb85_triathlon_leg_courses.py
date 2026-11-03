# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""triathlon leg courses

A triathlon's own leg files, each winning over its stretch of the whole
course, and the user's T1/T2 along that whole course (null = the automatic
split). See calculators/race_predictor/triathlon.py.

Revision ID: de402f9bdb85
Revises: 530b6aa9f69c
Create Date: 2026-11-03

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


# revision identifiers, used by Alembic.
revision = 'de402f9bdb85'
down_revision = '530b6aa9f69c'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('race_plans', sa.Column('tri_leg_courses', postgresql.JSONB(astext_type=sa.Text()), nullable=True))
    op.add_column('race_plans', sa.Column('tri_transitions_m', postgresql.JSONB(astext_type=sa.Text()), nullable=True))


def downgrade() -> None:
    op.drop_column('race_plans', 'tri_transitions_m')
    op.drop_column('race_plans', 'tri_leg_courses')
