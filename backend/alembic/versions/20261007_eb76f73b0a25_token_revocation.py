# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""token revocation

Access tokens that can be taken back: `revoked_sessions` lists the session ids
that logout and "sign this device out" ended, and `users.tokens_valid_after`
is the cutoff a password change sets. See app.auth.token_is_live.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: eb76f73b0a25
Revises: a60b1c95c621
Create Date: 2026-10-07 04:11:17.163947

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'eb76f73b0a25'
down_revision = 'a60b1c95c621'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('revoked_sessions',
    sa.Column('sid', sa.String(), nullable=False),
    sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
    sa.PrimaryKeyConstraint('sid')
    )
    op.add_column('users', sa.Column('tokens_valid_after', sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column('users', 'tokens_valid_after')
    op.drop_table('revoked_sessions')
