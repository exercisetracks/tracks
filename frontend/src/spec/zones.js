// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/zones.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

export const HR_MODELS = {
  "friel_lthr_run": {
    "basis": "lthr",
    "source": "Friel, \"The Triathlete's Training Bible\" 4th ed.",
    "zones": [
      {
        "number": 1,
        "name": "Zone 1",
        "description": "Recovery",
        "min_pct": 0.0,
        "max_pct": 0.85
      },
      {
        "number": 2,
        "name": "Zone 2",
        "description": "Aerobic",
        "min_pct": 0.85,
        "max_pct": 0.9
      },
      {
        "number": 3,
        "name": "Zone 3",
        "description": "Tempo",
        "min_pct": 0.9,
        "max_pct": 0.95
      },
      {
        "number": 4,
        "name": "Zone 4",
        "description": "Sub-Threshold",
        "min_pct": 0.95,
        "max_pct": 1.0
      },
      {
        "number": 5,
        "name": "Zone 5a",
        "description": "Threshold",
        "min_pct": 1.0,
        "max_pct": 1.03
      },
      {
        "number": 6,
        "name": "Zone 5b",
        "description": "VO2max",
        "min_pct": 1.03,
        "max_pct": 1.06
      },
      {
        "number": 7,
        "name": "Zone 5c",
        "description": "Anaerobic",
        "min_pct": 1.06,
        "max_pct": null
      }
    ]
  },
  "friel_lthr_bike": {
    "basis": "lthr",
    "source": "Friel, \"The Triathlete's Training Bible\" 4th ed.",
    "zones": [
      {
        "number": 1,
        "name": "Zone 1",
        "description": "Recovery",
        "min_pct": 0.0,
        "max_pct": 0.81
      },
      {
        "number": 2,
        "name": "Zone 2",
        "description": "Aerobic",
        "min_pct": 0.81,
        "max_pct": 0.9
      },
      {
        "number": 3,
        "name": "Zone 3",
        "description": "Tempo",
        "min_pct": 0.9,
        "max_pct": 0.94
      },
      {
        "number": 4,
        "name": "Zone 4",
        "description": "Sub-Threshold",
        "min_pct": 0.94,
        "max_pct": 1.0
      },
      {
        "number": 5,
        "name": "Zone 5a",
        "description": "Threshold",
        "min_pct": 1.0,
        "max_pct": 1.03
      },
      {
        "number": 6,
        "name": "Zone 5b",
        "description": "VO2max",
        "min_pct": 1.03,
        "max_pct": 1.06
      },
      {
        "number": 7,
        "name": "Zone 5c",
        "description": "Anaerobic",
        "min_pct": 1.06,
        "max_pct": null
      }
    ]
  },
  "display_maxhr": {
    "basis": "max_hr",
    "source": "Conventional five-zone %HRmax split.",
    "zones": [
      {
        "number": 1,
        "name": "Z1",
        "description": "Recovery",
        "min_pct": 0.0,
        "max_pct": 0.6,
        "color": "#22c55e"
      },
      {
        "number": 2,
        "name": "Z2",
        "description": "Aerobic",
        "min_pct": 0.6,
        "max_pct": 0.7,
        "color": "#84cc16"
      },
      {
        "number": 3,
        "name": "Z3",
        "description": "Tempo",
        "min_pct": 0.7,
        "max_pct": 0.8,
        "color": "#eab308"
      },
      {
        "number": 4,
        "name": "Z4",
        "description": "Threshold",
        "min_pct": 0.8,
        "max_pct": 0.9,
        "color": "#f97316"
      },
      {
        "number": 5,
        "name": "Z5",
        "description": "VO\u2082 Max",
        "min_pct": 0.9,
        "max_pct": null,
        "color": "#ef4444"
      }
    ]
  }
};

export const POWER_MODELS = {
  "coggan_ftp": {
    "basis": "ftp",
    "source": "Allen & Coggan, \"Training and Racing with a Power Meter\" 2nd ed.",
    "zones": [
      {
        "number": 1,
        "name": "Zone 1",
        "description": "Active Recovery",
        "min_pct": 0.0,
        "max_pct": 0.55
      },
      {
        "number": 2,
        "name": "Zone 2",
        "description": "Endurance",
        "min_pct": 0.55,
        "max_pct": 0.75
      },
      {
        "number": 3,
        "name": "Zone 3",
        "description": "Tempo",
        "min_pct": 0.75,
        "max_pct": 0.9
      },
      {
        "number": 4,
        "name": "Zone 4",
        "description": "Threshold",
        "min_pct": 0.9,
        "max_pct": 1.05
      },
      {
        "number": 5,
        "name": "Zone 5",
        "description": "VO2max",
        "min_pct": 1.05,
        "max_pct": 1.2
      },
      {
        "number": 6,
        "name": "Zone 6",
        "description": "Anaerobic",
        "min_pct": 1.2,
        "max_pct": 1.5
      },
      {
        "number": 7,
        "name": "Zone 7",
        "description": "Neuromuscular",
        "min_pct": 1.5,
        "max_pct": null
      }
    ]
  }
};

export const TSB_BANDS = {
  "source": "Common CTL/ATL/TSB practice; thresholds tuned for this app.",
  "bands": [
    {
      "key": "transition",
      "label": "Transition",
      "min": 25,
      "max": null,
      "color": "#f59e0b",
      "bg": "#f59e0b14",
      "desc": "> 25"
    },
    {
      "key": "fresh",
      "label": "Fresh",
      "min": 5,
      "max": 25,
      "color": "#60a5fa",
      "bg": "#60a5fa14",
      "desc": "5\u201325"
    },
    {
      "key": "grey",
      "label": "Grey Zone",
      "min": -5,
      "max": 5,
      "color": "#94a3b8",
      "bg": "#94a3b812",
      "desc": "-5\u20135"
    },
    {
      "key": "optimal",
      "label": "Optimal",
      "min": -30,
      "max": -5,
      "color": "#4ade80",
      "bg": "#4ade8014",
      "desc": "-30\u2013-5"
    },
    {
      "key": "high_risk",
      "label": "High Risk",
      "min": null,
      "max": -30,
      "color": "#ef4444",
      "bg": "#ef444414",
      "desc": "< -30"
    }
  ],
  "unknown": "grey"
};
