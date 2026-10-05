"""
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later

GENERATED FILE — DO NOT EDIT.

Source: spec/fueling.yaml
Regenerate: python3 spec/codegen.py

Edits here are silently discarded on the next codegen run. The tables are
shared across the Python backend, the JS frontend, and the Kotlin mobile
core precisely so they cannot drift apart — change the spec, not this.
"""

CARB_BREAKPOINTS = [
    {
        "max_hours": 1,
        "carbs_per_hour": 30
    },
    {
        "max_hours": 2,
        "carbs_per_hour": 60
    },
    {
        "max_hours": 4,
        "carbs_per_hour": 80
    }
]

DEFAULT_CARBS_PER_HOUR = 90

HEAT_THRESHOLD_C = 25

VERY_HOT_THRESHOLD_C = 32

HUMIDITY_THRESHOLD_PCT = 70

HUMIDITY_MIN_TEMP_C = 20

BANDS = {
    "normal": {
        "concentration_pct": 12,
        "sip_interval_min": 15,
        "salt_index": 0
    },
    "hot": {
        "concentration_pct": 8,
        "sip_interval_min": 12,
        "salt_index": 1
    },
    "very_hot": {
        "concentration_pct": 6,
        "sip_interval_min": 10,
        "salt_index": 2
    }
}
