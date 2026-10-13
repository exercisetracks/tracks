# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""waypoint folders

Places join collections: a waypoint may belong to a track folder, as a track
does. Null is the default collection. Deleting a folder leaves its places in
the default rather than deleting them (ON DELETE SET NULL, as for tracks).

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: 9bfa42abd1e3
Revises: a483a62741c4
Create Date: 2026-10-13 19:40:10.980392

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '9bfa42abd1e3'
down_revision = 'a483a62741c4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('waypoints', sa.Column('folder_id', sa.Integer(), nullable=True))
    op.create_foreign_key('waypoints_folder_id_fkey', 'waypoints', 'custom_track_folders',
                          ['folder_id'], ['id'], ondelete='SET NULL')


def downgrade() -> None:
    op.drop_constraint('waypoints_folder_id_fkey', 'waypoints', type_='foreignkey')
    op.drop_column('waypoints', 'folder_id')
