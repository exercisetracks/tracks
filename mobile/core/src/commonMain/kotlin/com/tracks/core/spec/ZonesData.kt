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

package com.tracks.core.spec

internal val HR_MODELS: Map<String, ZoneModel> = mapOf(
    "friel_lthr_run" to ZoneModel(
        basis = "lthr",
        source = "Friel, \"The Triathlete's Training Bible\" 4th ed.",
        zones = listOf(
            Zone(1, "Zone 1", "Recovery", 0.0, 0.85),
            Zone(2, "Zone 2", "Aerobic", 0.85, 0.9),
            Zone(3, "Zone 3", "Tempo", 0.9, 0.95),
            Zone(4, "Zone 4", "Sub-Threshold", 0.95, 1.0),
            Zone(5, "Zone 5a", "Threshold", 1.0, 1.03),
            Zone(6, "Zone 5b", "VO2max", 1.03, 1.06),
            Zone(7, "Zone 5c", "Anaerobic", 1.06, null),
        ),
    ),
    "friel_lthr_bike" to ZoneModel(
        basis = "lthr",
        source = "Friel, \"The Triathlete's Training Bible\" 4th ed.",
        zones = listOf(
            Zone(1, "Zone 1", "Recovery", 0.0, 0.81),
            Zone(2, "Zone 2", "Aerobic", 0.81, 0.9),
            Zone(3, "Zone 3", "Tempo", 0.9, 0.94),
            Zone(4, "Zone 4", "Sub-Threshold", 0.94, 1.0),
            Zone(5, "Zone 5a", "Threshold", 1.0, 1.03),
            Zone(6, "Zone 5b", "VO2max", 1.03, 1.06),
            Zone(7, "Zone 5c", "Anaerobic", 1.06, null),
        ),
    ),
    "display_maxhr" to ZoneModel(
        basis = "max_hr",
        source = "Conventional five-zone %HRmax split.",
        zones = listOf(
            Zone(1, "Z1", "Recovery", 0.0, 0.6, color = "#22c55e"),
            Zone(2, "Z2", "Aerobic", 0.6, 0.7, color = "#84cc16"),
            Zone(3, "Z3", "Tempo", 0.7, 0.8, color = "#eab308"),
            Zone(4, "Z4", "Threshold", 0.8, 0.9, color = "#f97316"),
            Zone(5, "Z5", "VO\u2082 Max", 0.9, null, color = "#ef4444"),
        ),
    ),
)

internal val POWER_MODELS: Map<String, ZoneModel> = mapOf(
    "coggan_ftp" to ZoneModel(
        basis = "ftp",
        source = "Allen & Coggan, \"Training and Racing with a Power Meter\" 2nd ed.",
        zones = listOf(
            Zone(1, "Zone 1", "Active Recovery", 0.0, 0.55),
            Zone(2, "Zone 2", "Endurance", 0.55, 0.75),
            Zone(3, "Zone 3", "Tempo", 0.75, 0.9),
            Zone(4, "Zone 4", "Threshold", 0.9, 1.05),
            Zone(5, "Zone 5", "VO2max", 1.05, 1.2),
            Zone(6, "Zone 6", "Anaerobic", 1.2, 1.5),
            Zone(7, "Zone 7", "Neuromuscular", 1.5, null),
        ),
    ),
)

internal val TSB_BANDS: List<TsbBand> = listOf(
    TsbBand("transition", "Transition", 25, null, "#f59e0b", "#f59e0b14", "> 25"),
    TsbBand("fresh", "Fresh", 5, 25, "#60a5fa", "#60a5fa14", "5\u201325"),
    TsbBand("grey", "Grey Zone", -5, 5, "#94a3b8", "#94a3b812", "-5\u20135"),
    TsbBand("optimal", "Optimal", -30, -5, "#4ade80", "#4ade8014", "-30\u2013-5"),
    TsbBand("high_risk", "High Risk", null, -30, "#ef4444", "#ef444414", "< -30"),
)

internal const val TSB_UNKNOWN_BAND: String = "grey"
