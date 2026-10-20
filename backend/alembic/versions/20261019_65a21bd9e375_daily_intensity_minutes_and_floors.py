# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""daily intensity minutes and floors

The watch's moderate/vigorous minutes (summed from per-minute series in
`extra`) and its floors-climbed count, per day. Nullable: days imported
before this have none until their files are re-read.

Revision ID: 65a21bd9e375
Revises: 08f9b054b4e4
Create Date: 2026-10-19 23:57:19.195743

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '65a21bd9e375'
down_revision = '08f9b054b4e4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('daily_metrics', sa.Column('intensity_moderate_min', sa.Integer(), nullable=True))
    op.add_column('daily_metrics', sa.Column('intensity_vigorous_min', sa.Integer(), nullable=True))
    op.add_column('daily_metrics', sa.Column('floors_climbed', sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column('daily_metrics', 'floors_climbed')
    op.drop_column('daily_metrics', 'intensity_vigorous_min')
    op.drop_column('daily_metrics', 'intensity_moderate_min')
