// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/sport_taxonomy.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

export const SPORT_TYPE_LIST = [
  "bouldering",
  "climbing",
  "strength",
  "running",
  "hiking",
  "cycling",
  "mtb",
  "indoor_cycling",
  "swimming",
  "rowing",
  "triathlon",
  "skiing",
  "nordic_skiing",
  "paddling",
  "golf",
  "team_sports",
  "fitness_equipment",
  "mind_body",
  "other"
];

export const FALLBACK = "other";

export const RULES = [
  {
    "type": "bouldering",
    "any": [
      {
        "field": "combined",
        "matches": "bouldering"
      }
    ]
  },
  {
    "type": "climbing",
    "any": [
      {
        "field": "combined",
        "matches": "rock_climbing|floor_climbing|indoor_climbing"
      }
    ]
  },
  {
    "type": "mind_body",
    "any": [
      {
        "field": "combined",
        "matches": "yoga|pilates|breath|meditat|mindful"
      }
    ]
  },
  {
    "type": "strength",
    "any": [
      {
        "field": "sport",
        "equals": "training"
      },
      {
        "field": "combined",
        "matches": "strength_training|weight_training|crossfit"
      }
    ]
  },
  {
    "type": "hiking",
    "any": [
      {
        "field": "sport",
        "matches": "hik|walk"
      }
    ],
    "none": [
      {
        "field": "combined",
        "matches": "mountain_bik|rock|bike|cycl"
      }
    ]
  },
  {
    "type": "running",
    "any": [
      {
        "field": "sport",
        "matches": "run"
      },
      {
        "field": "sub_sport",
        "equals": "treadmill"
      }
    ]
  },
  {
    "type": "indoor_cycling",
    "any": [
      {
        "field": "combined",
        "matches": "indoor_cycling|virtual_cycling|spin"
      },
      {
        "field": "sub_sport",
        "equals": "indoor_cycling"
      },
      {
        "all": [
          {
            "field": "sport",
            "equals": "cycling"
          },
          {
            "field": "sub_sport",
            "matches": "virtual|indoor|trainer|spin"
          }
        ]
      }
    ]
  },
  {
    "type": "mtb",
    "any": [
      {
        "field": "combined",
        "matches": "mountain_bik|trail_bik|mtb|downhill"
      },
      {
        "field": "sub_sport",
        "equals": "mountain"
      }
    ]
  },
  {
    "type": "cycling",
    "any": [
      {
        "field": "sport",
        "matches": "cycl|bik|ride"
      },
      {
        "field": "sub_sport",
        "matches": "road|gravel|cyclocross|track_cycling|hand_cycling|recumbent"
      }
    ]
  },
  {
    "type": "swimming",
    "any": [
      {
        "field": "combined",
        "matches": "swim|open_water"
      }
    ]
  },
  {
    "type": "rowing",
    "any": [
      {
        "field": "combined",
        "matches": "row"
      }
    ]
  },
  {
    "type": "triathlon",
    "any": [
      {
        "field": "combined",
        "matches": "triathlon|multisport|duathlon|transition"
      }
    ]
  },
  {
    "type": "skiing",
    "any": [
      {
        "field": "combined",
        "matches": "alpine_ski|downhill_ski|snowboard"
      }
    ]
  },
  {
    "type": "nordic_skiing",
    "any": [
      {
        "field": "combined",
        "matches": "cross_country|nordic_ski|skate_ski|snowshoe"
      }
    ]
  },
  {
    "type": "paddling",
    "any": [
      {
        "field": "combined",
        "matches": "kayak|canoe|paddle|stand_up|surfing|sail|windsurf|kitesurf|wakeboard|water_ski|raft"
      }
    ]
  },
  {
    "type": "fitness_equipment",
    "any": [
      {
        "field": "combined",
        "matches": "elliptical|stair|fitness_equipment"
      },
      {
        "field": "sub_sport",
        "equals": "elliptical"
      },
      {
        "field": "sub_sport",
        "equals": "stair_climbing"
      }
    ]
  },
  {
    "type": "team_sports",
    "any": [
      {
        "field": "combined",
        "matches": "basketball|soccer|football|tennis|volleyball|baseball|softball|hockey|lacrosse|rugby|handball|squash|racquetball|pickleball|badminton"
      }
    ]
  },
  {
    "type": "team_sports",
    "any": [
      {
        "field": "combined",
        "matches": "box|wrestling|mma|martial|judo|karate"
      }
    ]
  },
  {
    "type": "golf",
    "any": [
      {
        "field": "combined",
        "matches": "golf"
      }
    ]
  },
  {
    "type": "fitness_equipment",
    "any": [
      {
        "field": "combined",
        "matches": "skating|skateboard|inline"
      }
    ]
  },
  {
    "type": "other",
    "any": [
      {
        "field": "combined",
        "matches": "horseback|hunting|fishing|equestrian"
      }
    ]
  },
  {
    "type": "other",
    "any": [
      {
        "field": "combined",
        "matches": "motorcycl|snowmobil|driving|boating|flying"
      }
    ]
  }
];
