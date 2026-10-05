# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Maps a FIT set message's (exercise_category, category_subtype) to a
human-readable exercise name, and decodes raw exercise_category values to
canonical snake_case strings.

This is a package: the large static lookup tables live in :mod:`._data` and
the resolver logic in :mod:`.resolver`. The public interface is unchanged --
both functions remain importable as ``app.parsers.exercise_names.<name>``.

Source: Garmin FIT Java SDK (Profile Version 21.205.0).
"""

from .resolver import decode_category, resolve_exercise_name

__all__ = ["decode_category", "resolve_exercise_name"]
