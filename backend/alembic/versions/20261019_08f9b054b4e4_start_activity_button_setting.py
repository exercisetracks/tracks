# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""start activity button setting

The phone dashboard's "Start activity" button, as an account setting so it
is the same on every phone. Nullable: NULL is automatic (shown without a
watch, hidden with one), and every existing row keeps it.

Revision ID: 08f9b054b4e4
Revises: 7fe7c7bfd6fb
Create Date: 2026-10-19 13:50:32.829055

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '08f9b054b4e4'
down_revision = '7fe7c7bfd6fb'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('user_settings', sa.Column('start_activity_button', sa.Boolean(), nullable=True))


def downgrade() -> None:
    op.drop_column('user_settings', 'start_activity_button')
