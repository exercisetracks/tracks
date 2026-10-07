"""
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later

GENERATED FILE — DO NOT EDIT.

Source: spec/strength.yaml
Regenerate: python3 spec/codegen.py

Edits here are silently discarded on the next codegen run. The tables are
shared across the Python backend, the JS frontend, and the Kotlin mobile
core precisely so they cannot drift apart — change the spec, not this.
"""

EQUIPMENT = [
    {
        "value": "bodyweight",
        "label": "Bodyweight",
        "description": "No equipment needed"
    },
    {
        "value": "dumbbell",
        "label": "Dumbbells",
        "description": "Fixed or adjustable"
    },
    {
        "value": "barbell",
        "label": "Barbell",
        "description": "Olympic bar + plates"
    },
    {
        "value": "cable",
        "label": "Cable machine",
        "description": "Pulley / functional trainer"
    },
    {
        "value": "machine",
        "label": "Machines",
        "description": "Leg press, lat pulldown, etc."
    },
    {
        "value": "kettlebell",
        "label": "Kettlebells",
        "description": "One or more kettlebells"
    },
    {
        "value": "band",
        "label": "Bands",
        "description": "Resistance or pull-up bands"
    },
    {
        "value": "pullup_bar",
        "label": "Pull-up bar",
        "description": "Doorway, wall or rig bar you can hang from"
    }
]

VALID_EQUIPMENT = {"bodyweight", "dumbbell", "barbell", "cable", "machine", "kettlebell", "band", "pullup_bar"}

EXPERIENCE_LEVELS = ("brand_new", "returning", "regular", "advanced")

EXPERIENCE_TABLE = {   'brand_new': {   'label': 'Brand new',
                     'blurb': 'New to lifting — teach me the basics with lighter, '
                              'simpler movements.',
                     'default_tier': {'endurance': 1, 'strength': 2},
                     'max_difficulty': 2,
                     'stage_floor': 0,
                     'starting_weight_factor': 0.8},
    'returning': {   'label': 'Returning',
                     'blurb': "Trained before but it's been a while — ease me back in.",
                     'default_tier': {'endurance': 2, 'strength': 3},
                     'max_difficulty': 3,
                     'stage_floor': 0,
                     'starting_weight_factor': 1.0},
    'regular': {   'label': 'Train regularly',
                   'blurb': 'I lift consistently and know the main movements.',
                   'default_tier': {'endurance': 2, 'strength': 4},
                   'max_difficulty': None,
                   'stage_floor': 20,
                   'starting_weight_factor': 1.0},
    'advanced': {   'label': 'Advanced',
                    'blurb': 'Experienced lifter — bring the barbell and heavier '
                             'programming.',
                    'default_tier': {'endurance': 2, 'strength': 5},
                    'max_difficulty': None,
                    'stage_floor': 100,
                    'starting_weight_factor': 1.0}}

UNKNOWN_FALLBACK_TIER = 3

EXPERIENCE_FROM_FREQUENCY = {   'never': 'brand_new',
    'occasional': 'returning',
    '1_2': 'regular',
    '3_4': 'regular',
    '5_plus': 'advanced'}
