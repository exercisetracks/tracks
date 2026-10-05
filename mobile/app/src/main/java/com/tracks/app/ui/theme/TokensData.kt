// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/design.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.
package com.tracks.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The fixed design layer, from spec/design.yaml — the same steps the web's Tailwind theme uses. */
object Tokens {
    object Radius {
        val none = 0.dp
        val sm = 2.dp
        val base = 4.dp
        val md = 6.dp
        val lg = 8.dp
        val xl = 12.dp
        val xl2 = 16.dp
        val full = 999.dp
    }

    /** Tailwind's spacing steps: `s3_5` is `p-3.5`, 14dp. */
    object Space {
        val s0 = 0.dp
        val s0_5 = 2.dp
        val s1 = 4.dp
        val s1_5 = 6.dp
        val s2 = 8.dp
        val s2_5 = 10.dp
        val s3 = 12.dp
        val s3_5 = 14.dp
        val s4 = 16.dp
        val s5 = 20.dp
        val s6 = 24.dp
        val s8 = 32.dp
        val s10 = 40.dp
        val s12 = 48.dp
        val s16 = 64.dp
    }

    /** Font size and line height of each step of the type scale. */
    object Type {
        val xs2 = TypeStep(10.sp, 14.sp)
        val xs = TypeStep(12.sp, 16.sp)
        val sm = TypeStep(14.sp, 20.sp)
        val base = TypeStep(16.sp, 24.sp)
        val lg = TypeStep(18.sp, 28.sp)
        val xl = TypeStep(20.sp, 28.sp)
        val xl2 = TypeStep(24.sp, 32.sp)
        val xl3 = TypeStep(30.sp, 36.sp)
        val xl4 = TypeStep(36.sp, 40.sp)
    }

    object Weight {
        val normal = FontWeight(400)
        val medium = FontWeight(500)
        val semibold = FontWeight(600)
        val bold = FontWeight(700)
    }

    object Tracking {
        val tight = (-0.025).em
        val normal = (0).em
        val wide = (0.025).em
        val wider = (0.05).em
    }

    object Elevation {
        val card = 0.dp
        val raised = 1.dp
        val overlay = 3.dp
    }

    object Card {
        val radius = Radius.xl
        val padding = Space.s3_5
        val border = 1.dp
    }

    object SectionHeader {
        val style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(600), letterSpacing = (0.05).em)
        const val uppercase = true
    }

    object Stat {
        val value = TextStyle(fontFamily = InterFamily, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight(700), fontFeatureSettings = "tnum")
        val label = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(500), letterSpacing = (0.025).em)
    }

    object Chip {
        val style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(400))
        val radius = Radius.base
        val paddingX = Space.s1_5
        val paddingY = Space.s0_5
    }

    object Pill {
        val style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight(600))
        val radius = Radius.full
        val paddingX = Space.s1_5
        val paddingY = Space.s0_5
    }

    object Button {
        val style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
        val radius = Radius.full
        val paddingX = Space.s4
        val paddingY = Space.s0
        val height = Space.s10
        val heightSm = Space.s8
        val paddingXSm = Space.s3
        const val tonalAlpha = 0.12f
        const val neutralAlpha = 0.08f
    }
}
