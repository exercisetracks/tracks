// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/strength.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

package com.tracks.core.spec

internal val EQUIPMENT: List<EquipmentOption> = listOf(
    EquipmentOption("bodyweight", "Bodyweight", "No equipment needed"),
    EquipmentOption("dumbbell", "Dumbbells", "Fixed or adjustable"),
    EquipmentOption("barbell", "Barbell", "Olympic bar + plates"),
    EquipmentOption("cable", "Cable machine", "Pulley / functional trainer"),
    EquipmentOption("machine", "Machines", "Leg press, lat pulldown, etc."),
    EquipmentOption("kettlebell", "Kettlebells", "One or more kettlebells"),
    EquipmentOption("band", "Bands", "Resistance or pull-up bands"),
    EquipmentOption("pullup_bar", "Pull-up bar", "Doorway, wall or rig bar you can hang from"),
)

internal val VALID_EQUIPMENT: Set<String> = setOf("bodyweight", "dumbbell", "barbell", "cable", "machine", "kettlebell", "band", "pullup_bar")

internal val EXPERIENCE_LEVELS: List<String> = listOf("brand_new", "returning", "regular", "advanced")

internal val EXPERIENCE_TABLE: Map<String, ExperienceEntry> = mapOf(
    "brand_new" to ExperienceEntry(
        label = "Brand new",
        blurb = "New to lifting \u2014 teach me the basics with lighter, simpler movements.",
        defaultTierEndurance = 1,
        defaultTierStrength = 2,
        maxDifficulty = 2,
        stageFloor = 0,
        startingWeightFactor = 0.8,
    ),
    "returning" to ExperienceEntry(
        label = "Returning",
        blurb = "Trained before but it's been a while \u2014 ease me back in.",
        defaultTierEndurance = 2,
        defaultTierStrength = 3,
        maxDifficulty = 3,
        stageFloor = 0,
        startingWeightFactor = 1.0,
    ),
    "regular" to ExperienceEntry(
        label = "Train regularly",
        blurb = "I lift consistently and know the main movements.",
        defaultTierEndurance = 2,
        defaultTierStrength = 4,
        maxDifficulty = null,
        stageFloor = 20,
        startingWeightFactor = 1.0,
    ),
    "advanced" to ExperienceEntry(
        label = "Advanced",
        blurb = "Experienced lifter \u2014 bring the barbell and heavier programming.",
        defaultTierEndurance = 2,
        defaultTierStrength = 5,
        maxDifficulty = null,
        stageFloor = 100,
        startingWeightFactor = 1.0,
    ),
)

internal const val UNKNOWN_FALLBACK_TIER: Int = 3

internal val EXPERIENCE_FROM_FREQUENCY: Map<String, String> = mapOf(
    "never" to "brand_new",
    "occasional" to "returning",
    "1_2" to "regular",
    "3_4" to "regular",
    "5_plus" to "advanced",
)
