// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/fuel_catalog.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

package com.tracks.core.spec

/** One common race food, per serving as sold (spec/fuel_catalog.yaml). */
data class CatalogProduct(val name: String, val kind: String, val carbsG: Double, val sodiumMg: Int,
                          val potassiumMg: Int, val caffeineMg: Int, val fluidMl: Int)

val FUEL_CATALOG: List<CatalogProduct> = listOf(
    CatalogProduct("GU Energy Gel", "gel", 22.0, 60, 40, 0, 0),
    CatalogProduct("GU Energy Gel, salted", "gel", 22.0, 125, 55, 0, 0),
    CatalogProduct("Honey Stinger Energy Gel", "gel", 24.0, 50, 50, 0, 0),
    CatalogProduct("Honey Stinger Waffle", "bar", 19.0, 55, 30, 0, 0),
    CatalogProduct("Honey Stinger Energy Chews", "chew", 39.0, 80, 40, 0, 0),
    CatalogProduct("Clif Shot Bloks (3)", "chew", 24.0, 50, 20, 0, 0),
    CatalogProduct("Maurten Gel 100", "gel", 25.0, 20, 0, 0, 0),
    CatalogProduct("Maurten Gel 100 CAF 100", "gel", 25.0, 22, 0, 100, 0),
    CatalogProduct("Tailwind (1 scoop)", "drink", 25.0, 303, 88, 0, 500),
    CatalogProduct("Gatorade (20 oz)", "drink", 36.0, 270, 75, 0, 591),
    CatalogProduct("Snickers", "bar", 32.0, 130, 190, 0, 0),
    CatalogProduct("Rice Krispies Treat", "bar", 17.0, 105, 0, 0, 0),
    CatalogProduct("Banana", "other", 27.0, 1, 422, 0, 0),
)
