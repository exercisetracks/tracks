// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/fueling.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

package com.tracks.core.spec

internal val CARB_BREAKPOINTS: List<CarbBreakpoint> = listOf(
    CarbBreakpoint(1, 30),
    CarbBreakpoint(2, 60),
    CarbBreakpoint(4, 80),
)

internal const val DEFAULT_CARBS_PER_HOUR: Int = 90

internal const val HEAT_THRESHOLD_C: Int = 25

internal const val VERY_HOT_THRESHOLD_C: Int = 32

internal const val HUMIDITY_THRESHOLD_PCT: Int = 70

internal const val HUMIDITY_MIN_TEMP_C: Int = 20

internal val BANDS: Map<String, FuelingBand> = mapOf(
    "normal" to FuelingBand(12, 15, 0),
    "hot" to FuelingBand(8, 12, 1),
    "very_hot" to FuelingBand(6, 10, 2),
)
