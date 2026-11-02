# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""fuel potassium

Fuel products carry potassium, and a race plan can override the potassium
target (otherwise worked out from the fluid target; see
calculators/fuel_plan.py and docs/race-fuelling-research.md). Existing
products read as 0 mg, which is what they claimed before: nothing.

Revision ID: 22b4bf661ec4
Revises: 3877e64c8a87
Create Date: 2026-11-02

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '22b4bf661ec4'
down_revision = '3877e64c8a87'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('fuel_products', sa.Column('potassium_mg', sa.Integer(), server_default='0', nullable=False))
    op.add_column('race_plans', sa.Column('fuel_potassium_mg_per_hour', sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column('race_plans', 'fuel_potassium_mg_per_hour')
    op.drop_column('fuel_products', 'potassium_mg')
