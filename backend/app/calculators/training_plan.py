# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Backward-compatibility shim.

All real code now lives in `app.calculators.plan`.
This module re-exports everything so existing imports continue to work.
"""

from app.calculators.plan import *  # noqa: F401, F403
