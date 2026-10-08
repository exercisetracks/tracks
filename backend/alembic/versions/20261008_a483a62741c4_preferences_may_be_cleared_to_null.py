# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""preferences may be cleared to null

A phone clears an exercise or stretch preference by syncing `preference` as
null — the row's uid is derived from the exercise's name, so it is kept and
emptied rather than deleted. NOT NULL failed that change, and with it the
whole push it travelled in, so every later edit from that phone was held back
on each retry. Null reads as neutral, the same as no row.

Autogenerate also proposed recreating idx_imports_source; as in f551740dd90c,
that is a dev-database artefact and is deliberately left out.

Revision ID: a483a62741c4
Revises: edeec8b60d5e
Create Date: 2026-10-08 02:23:19.677000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'a483a62741c4'
down_revision = 'edeec8b60d5e'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.alter_column('user_exercise_preferences', 'preference',
               existing_type=sa.VARCHAR(length=16),
               nullable=True)
    op.alter_column('user_flexibility_preferences', 'preference',
               existing_type=sa.VARCHAR(length=16),
               nullable=True)


def downgrade() -> None:
    # A cleared preference is neutral either way; drop those rows so the
    # constraint can come back.
    op.execute("DELETE FROM user_flexibility_preferences WHERE preference IS NULL")
    op.execute("DELETE FROM user_exercise_preferences WHERE preference IS NULL")
    op.alter_column('user_flexibility_preferences', 'preference',
               existing_type=sa.VARCHAR(length=16),
               nullable=False)
    op.alter_column('user_exercise_preferences', 'preference',
               existing_type=sa.VARCHAR(length=16),
               nullable=False)
