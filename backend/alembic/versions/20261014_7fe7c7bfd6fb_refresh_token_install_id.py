# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""refresh token install id

Which app install a session came from, so a fresh sign-in replaces that
install's earlier sessions (app.models.refresh_tokens.issue). Nullable: old
rows keep NULL and are matched by device name instead.

Revision ID: 7fe7c7bfd6fb
Revises: 45024116629d
Create Date: 2026-10-14 17:41:48.590560

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '7fe7c7bfd6fb'
down_revision = '45024116629d'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('refresh_tokens', sa.Column('install_id', sa.String(length=64), nullable=True))
    op.create_index(op.f('ix_refresh_tokens_install_id'), 'refresh_tokens', ['install_id'], unique=False)


def downgrade() -> None:
    op.drop_index(op.f('ix_refresh_tokens_install_id'), table_name='refresh_tokens')
    op.drop_column('refresh_tokens', 'install_id')
