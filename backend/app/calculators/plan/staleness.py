# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Which edits make a training plan stale — and so rebuild it.

There is no Regenerate button (user decision, 2026-09-28): every change that
would leave the plan built for something that is no longer true rebuilds it
by itself. These two rules say which changes those are. The server applies
them in PATCH /coaching/goals and PATCH /me/settings; the phone applies the
same rules to its own edits (com.tracks.core.plan.PlanStaleness), held to
these sets by spec/fixtures/goal_planning.json.

A goal edit is stale unless it touches *only* fields the plan never reads.
Listing the harmless fields rather than the plan's inputs is deliberate: a
field added to goals later (a second sport, a new discipline) rebuilds the
plan by default, where an allow-list would silently leave it built for the
old value until someone remembered to add it.

Settings are the other way round — the settings row holds dozens of fields
(theme, music server, A-GPS) and only these reach a plan.
"""

from __future__ import annotations

# Goal fields no plan reads: the label, the free-text note, and the weekly
# volume goal's own numbers (a volume goal has no plan).
NON_PLAN_GOAL_FIELDS = frozenset({
    "event_name", "notes", "target_weekly_km", "volume_sport",
})

# Settings the plan generator reads (api/training_plan/generation.py and
# injectors.py): the unit its notes are written in, the thresholds its
# targets come from, the strength inputs, and how often each sport is done (where a plan with no
# history starts — calculators/plan/starting.py).
PLAN_SETTINGS = frozenset({
    "units",
    "ftp_mode", "ftp_manual",
    "threshold_hr_mode", "threshold_hr_manual",
    "max_hr_mode", "max_hr_manual",
    "equipment_available", "strength_experience",
    "activity_frequency",
})


def goal_edit_stales_plan(changed: dict) -> bool:
    """Whether a goal edit (field -> new value) leaves its plan stale.

    Deactivating is not a change to the plan — the plan simply stops being
    shown — but activating is: a goal that sat inactive for weeks has a plan
    built from the fitness of weeks ago, and for a race, the wrong phase.
    """
    for field, value in changed.items():
        if field == "is_active":
            if value:
                return True
            continue
        if field not in NON_PLAN_GOAL_FIELDS:
            return True
    return False


def setting_stales_plan(field: str) -> bool:
    return field in PLAN_SETTINGS
