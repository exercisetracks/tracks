# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""refresh token device key

Links a refresh-token family to the device key that owns it, so a device-key
unlock can end the device's previous family instead of leaving it listed
under Signed-in devices at an old app version (RefreshToken.device_key_id).
Existing families start unlinked; they are ended at the device's next unlock
once it re-enrols, or expire on their own.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: edeec8b60d5e
Revises: f91b5e81f7c6
Create Date: 2026-10-07 14:32:48.207122

"""
from alembic import op
import sqlalchemy as sa

# revision identifiers, used by Alembic.
revision = 'edeec8b60d5e'
down_revision = 'f91b5e81f7c6'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('refresh_tokens', sa.Column('device_key_id', sa.Integer(), nullable=True))
    op.create_foreign_key(
        'refresh_tokens_device_key_id_fkey', 'refresh_tokens', 'device_keys',
        ['device_key_id'], ['id'], ondelete='SET NULL',
    )


def downgrade() -> None:
    op.drop_constraint('refresh_tokens_device_key_id_fkey', 'refresh_tokens', type_='foreignkey')
    op.drop_column('refresh_tokens', 'device_key_id')
