# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""map region build queue

The columns services.map_builds needs: a heartbeat from the process building
an area, the phases it has finished (to resume from), and the ground its
overlay part owns. All nullable, so existing rows need no backfill.

Revision ID: 45024116629d
Revises: 9bfa42abd1e3
Create Date: 2026-10-14 08:34:33.688106

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = '45024116629d'
down_revision = '9bfa42abd1e3'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('map_regions', sa.Column('heartbeat_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('map_regions', sa.Column('phases_done', sa.JSON().with_variant(postgresql.JSONB(astext_type=sa.Text()), 'postgresql'), nullable=True))
    op.add_column('map_regions', sa.Column('owned', sa.JSON().with_variant(postgresql.JSONB(astext_type=sa.Text()), 'postgresql'), nullable=True))


def downgrade() -> None:
    op.drop_column('map_regions', 'owned')
    op.drop_column('map_regions', 'phases_done')
    op.drop_column('map_regions', 'heartbeat_at')
