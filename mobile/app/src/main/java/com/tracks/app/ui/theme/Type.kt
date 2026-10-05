// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.tracks.app.R

/**
 * Inter, the same face the web app uses, so the two read as one product.
 *
 * It was once deliberately absent — "a typeface is a megabyte or two in an APK"
 * — which held for a static family. Inter's variable font, subset to Latin, is
 * ~220 KB for every weight, which is the trade that made unifying the apps
 * worth it. One file, each weight a point on its `wght` axis.
 */
// Variation settings are still marked experimental in Compose 1.7; the API has
// been stable in practice since 1.5 and is the only way to address a variable
// font's weight axis.
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.inter,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

/** One step of the shared type scale: size and line height (see spec/design.yaml). */
data class TypeStep(val size: TextUnit, val lineHeight: TextUnit)

private fun style(step: TypeStep, weight: FontWeight, tracking: TextUnit = Tokens.Tracking.normal) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = weight,
    fontSize = step.size,
    lineHeight = step.lineHeight,
    letterSpacing = tracking,
)

/**
 * Material's roles, each pinned to a step of the web's type scale rather than
 * Material's own (57sp displays, 22sp titles) — which is what made the phone
 * look like a different app from the browser at the same content.
 */
val TracksTypography = Typography(
    displayLarge = style(Tokens.Type.xl4, Tokens.Weight.bold, Tokens.Tracking.tight),
    displayMedium = style(Tokens.Type.xl3, Tokens.Weight.bold, Tokens.Tracking.tight),
    displaySmall = style(Tokens.Type.xl2, Tokens.Weight.bold, Tokens.Tracking.tight),
    headlineLarge = style(Tokens.Type.xl3, Tokens.Weight.semibold, Tokens.Tracking.tight),
    headlineMedium = style(Tokens.Type.xl2, Tokens.Weight.semibold),
    headlineSmall = style(Tokens.Type.xl, Tokens.Weight.semibold),
    titleLarge = style(Tokens.Type.xl, Tokens.Weight.semibold),
    titleMedium = style(Tokens.Type.base, Tokens.Weight.semibold),
    titleSmall = style(Tokens.Type.sm, Tokens.Weight.semibold),
    bodyLarge = style(Tokens.Type.base, Tokens.Weight.normal),
    bodyMedium = style(Tokens.Type.sm, Tokens.Weight.normal),
    bodySmall = style(Tokens.Type.xs, Tokens.Weight.normal),
    labelLarge = style(Tokens.Type.sm, Tokens.Weight.medium),
    labelMedium = style(Tokens.Type.xs, Tokens.Weight.medium),
    labelSmall = style(Tokens.Type.xs2, Tokens.Weight.medium, Tokens.Tracking.wide),
)

/**
 * A single large figure: a distance, a pace, a heart rate. Tabular figures, so
 * a changing number does not make its neighbours jump.
 */
val statValue: TextStyle = Tokens.Stat.value

/** The label under a [statValue]. */
val statLabel: TextStyle = Tokens.Stat.label
