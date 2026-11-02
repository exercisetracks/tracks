# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""race plan climb target replaces fueling_plan

The race plan's old per-sport carbs dict goes: fuelling is the fuel plan's
(calculators/fuel_plan.py) for every sport. The one thing it also carried,
a hill climb's W/kg target, gets a column of its own. Nothing is copied
across — the page regenerates the plan every time it is opened, which
writes the new column, and the carbs figures are recomputed by the fuel
plan from the race's duration.

Revision ID: 3877e64c8a87
Revises: ebb4a7fe5af0
Create Date: 2026-11-02 23:16:47.309176

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


# revision identifiers, used by Alembic.
revision = '3877e64c8a87'
down_revision = 'ebb4a7fe5af0'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('race_plans', sa.Column('climb_target_w_per_kg', sa.Float(), nullable=True))
    op.drop_column('race_plans', 'fueling_plan')


def downgrade() -> None:
    op.add_column('race_plans', sa.Column('fueling_plan', postgresql.JSONB(astext_type=sa.Text()), autoincrement=False, nullable=True))
    op.drop_column('race_plans', 'climb_target_w_per_kg')
