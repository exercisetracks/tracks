# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""refresh token client version

What each signed-in app reports itself as (its X-Tracks-Client header), so the
web's version panel can list every phone with the version it runs. See
RefreshToken.client_version.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: a60b1c95c621
Revises: f551740dd90c
Create Date: 2026-10-06 22:35:49.317384

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'a60b1c95c621'
down_revision = 'f551740dd90c'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('refresh_tokens', sa.Column('client_version', sa.String(), nullable=True))


def downgrade() -> None:
    op.drop_column('refresh_tokens', 'client_version')
