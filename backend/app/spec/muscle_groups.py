"""
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later

GENERATED FILE — DO NOT EDIT.

Source: spec/muscle_groups.yaml
Regenerate: python3 spec/codegen.py

Edits here are silently discarded on the next codegen run. The tables are
shared across the Python backend, the JS frontend, and the Kotlin mobile
core precisely so they cannot drift apart — change the spec, not this.
"""

CATEGORY_MUSCLES = {
    "bench_press": {
        "primary": [
            "chest"
        ],
        "secondary": [
            "front_delts",
            "triceps"
        ]
    },
    "calf_raise": {
        "primary": [
            "calves_front",
            "calves_back"
        ],
        "secondary": []
    },
    "cardio": {
        "primary": [],
        "secondary": []
    },
    "carry": {
        "primary": [
            "forearms",
            "traps"
        ],
        "secondary": [
            "abs",
            "obliques",
            "lower_back",
            "glutes"
        ]
    },
    "chop": {
        "primary": [
            "obliques"
        ],
        "secondary": [
            "abs",
            "side_delts",
            "rear_delts"
        ]
    },
    "core": {
        "primary": [
            "abs",
            "obliques"
        ],
        "secondary": [
            "lower_back"
        ]
    },
    "crunch": {
        "primary": [
            "abs"
        ],
        "secondary": [
            "obliques",
            "hip_flexors"
        ]
    },
    "curl": {
        "primary": [
            "biceps"
        ],
        "secondary": [
            "forearms"
        ]
    },
    "deadlift": {
        "primary": [
            "hamstrings",
            "glutes",
            "lower_back"
        ],
        "secondary": [
            "traps",
            "forearms",
            "lats",
            "quads",
            "adductors"
        ]
    },
    "flye": {
        "primary": [
            "chest"
        ],
        "secondary": [
            "front_delts"
        ]
    },
    "hip_raise": {
        "primary": [
            "glutes"
        ],
        "secondary": [
            "hamstrings",
            "lower_back"
        ]
    },
    "hip_stability": {
        "primary": [
            "glutes"
        ],
        "secondary": [
            "obliques",
            "hamstrings"
        ]
    },
    "hip_swing": {
        "primary": [
            "glutes",
            "hamstrings"
        ],
        "secondary": [
            "lower_back",
            "abs"
        ]
    },
    "hyperextension": {
        "primary": [
            "lower_back"
        ],
        "secondary": [
            "glutes",
            "hamstrings"
        ]
    },
    "lateral_raise": {
        "primary": [
            "side_delts"
        ],
        "secondary": [
            "traps",
            "rear_delts"
        ]
    },
    "leg_curl": {
        "primary": [
            "hamstrings"
        ],
        "secondary": [
            "calves_back"
        ]
    },
    "leg_raise": {
        "primary": [
            "abs",
            "hip_flexors"
        ],
        "secondary": [
            "obliques"
        ]
    },
    "lunge": {
        "primary": [
            "quads",
            "glutes"
        ],
        "secondary": [
            "hamstrings",
            "calves_front",
            "calves_back",
            "adductors"
        ]
    },
    "olympic_lift": {
        "primary": [
            "quads",
            "glutes",
            "traps"
        ],
        "secondary": [
            "lower_back",
            "front_delts",
            "side_delts",
            "forearms",
            "hamstrings"
        ]
    },
    "plank": {
        "primary": [
            "abs"
        ],
        "secondary": [
            "obliques",
            "front_delts",
            "lower_back"
        ]
    },
    "plyo": {
        "primary": [
            "quads",
            "calves_front",
            "calves_back"
        ],
        "secondary": [
            "glutes",
            "hamstrings"
        ]
    },
    "pull_up": {
        "primary": [
            "lats"
        ],
        "secondary": [
            "biceps",
            "rear_delts",
            "mid_back",
            "forearms"
        ]
    },
    "push_up": {
        "primary": [
            "chest",
            "triceps"
        ],
        "secondary": [
            "front_delts",
            "abs"
        ]
    },
    "row": {
        "primary": [
            "lats",
            "mid_back"
        ],
        "secondary": [
            "rear_delts",
            "biceps",
            "traps",
            "forearms"
        ]
    },
    "shoulder_press": {
        "primary": [
            "front_delts",
            "side_delts"
        ],
        "secondary": [
            "triceps",
            "traps"
        ]
    },
    "shoulder_stability": {
        "primary": [
            "rear_delts"
        ],
        "secondary": [
            "traps"
        ]
    },
    "shrug": {
        "primary": [
            "traps"
        ],
        "secondary": [
            "forearms"
        ]
    },
    "sit_up": {
        "primary": [
            "abs"
        ],
        "secondary": [
            "hip_flexors",
            "obliques"
        ]
    },
    "squat": {
        "primary": [
            "quads",
            "glutes"
        ],
        "secondary": [
            "hamstrings",
            "lower_back",
            "calves_front",
            "calves_back",
            "adductors"
        ]
    },
    "total_body": {
        "primary": [
            "chest",
            "lats",
            "quads",
            "glutes"
        ],
        "secondary": [
            "abs",
            "shoulders",
            "biceps",
            "triceps",
            "hamstrings"
        ]
    },
    "triceps_extension": {
        "primary": [
            "triceps"
        ],
        "secondary": []
    },
    "warm_up": {
        "primary": [],
        "secondary": []
    },
    "run": {
        "primary": [],
        "secondary": []
    },
    "hip_hinge": {
        "primary": [
            "glutes",
            "hamstrings"
        ],
        "secondary": [
            "lower_back"
        ]
    },
    "push": {
        "primary": [
            "chest",
            "front_delts",
            "triceps"
        ],
        "secondary": [
            "side_delts",
            "abs"
        ]
    },
    "pull": {
        "primary": [
            "lats",
            "biceps"
        ],
        "secondary": [
            "rear_delts",
            "mid_back",
            "forearms"
        ]
    }
}

MUSCLE_LABELS = {
    "chest": "Chest",
    "front_delts": "Front Deltoids",
    "side_delts": "Side Deltoids",
    "rear_delts": "Rear Deltoids",
    "biceps": "Biceps",
    "triceps": "Triceps",
    "forearms": "Forearms",
    "traps": "Trapezius",
    "lats": "Lats",
    "mid_back": "Mid Back",
    "lower_back": "Lower Back",
    "abs": "Abs",
    "obliques": "Obliques",
    "hip_flexors": "Hip Flexors",
    "glutes": "Glutes",
    "quads": "Quads",
    "hamstrings": "Hamstrings",
    "calves_front": "Calves (Front)",
    "calves_back": "Calves",
    "adductors": "Adductors",
    "neck": "Neck",
    "core": "Core",
    "upper_back": "Upper Back",
    "achilles": "Achilles",
    "back": "Back",
    "brachialis": "Brachialis",
    "calves": "Calves",
    "feet": "Feet",
    "full_body": "Full Body",
    "groin": "Groin",
    "hands": "Hands",
    "hip_abductors": "Hip Abductors",
    "hip_adductors": "Hip Adductors",
    "hip_external_rotators": "Hip External Rotators",
    "hips": "Hips",
    "intercostals": "Intercostals",
    "pec_minor": "Pec Minor",
    "shoulders": "Shoulders",
    "spinal_erectors": "Spinal Erectors",
    "thoracic_spine": "Thoracic Spine",
    "wrists": "Wrists"
}

CATEGORY_LABELS = {
    "bench_press": "Bench Press",
    "calf_raise": "Calf Raise",
    "cardio": "Cardio",
    "carry": "Carry",
    "chop": "Chop",
    "core": "Core",
    "crunch": "Crunch",
    "curl": "Curl",
    "deadlift": "Deadlift",
    "flye": "Flye",
    "hip_raise": "Hip Raise",
    "hip_stability": "Hip Stability",
    "hip_swing": "Hip Swing",
    "hyperextension": "Hyperextension",
    "lateral_raise": "Lateral Raise",
    "leg_curl": "Leg Curl",
    "leg_raise": "Leg Raise",
    "lunge": "Lunge",
    "olympic_lift": "Olympic Lift",
    "plank": "Plank",
    "plyo": "Plyometric",
    "pull_up": "Pull-Up",
    "push_up": "Push-Up",
    "row": "Row",
    "shoulder_press": "Shoulder Press",
    "shoulder_stability": "Shoulder Stability",
    "shrug": "Shrug",
    "sit_up": "Sit-Up",
    "squat": "Squat",
    "total_body": "Total Body",
    "triceps_extension": "Triceps Extension",
    "warm_up": "Warm-Up",
    "run": "Run"
}
