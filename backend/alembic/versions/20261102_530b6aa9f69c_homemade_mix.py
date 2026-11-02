# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""homemade mix

The race plan's homemade drink: which recipe (null = picked by carb rate)
and how big a bottle (null = 750 ml). Whether it is carried at all is the
reserved "homemade" entry in fuel_product_uids. See calculators/fuel_plan.py.

Revision ID: 530b6aa9f69c
Revises: 22b4bf661ec4
Create Date: 2026-11-02

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '530b6aa9f69c'
down_revision = '22b4bf661ec4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('race_plans', sa.Column('fuel_mix_preset', sa.String(), nullable=True))
    op.add_column('race_plans', sa.Column('fuel_mix_bottle_ml', sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column('race_plans', 'fuel_mix_bottle_ml')
    op.drop_column('race_plans', 'fuel_mix_preset')
