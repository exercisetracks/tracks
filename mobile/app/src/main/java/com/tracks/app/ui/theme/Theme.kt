// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark first, and not as a style preference.
 *
 * The screen is the dominant battery draw on a phone being used as an
 * expedition tool — everything else this app does to save power is rounding
 * error next to how long a bright display stays lit. On the OLED panels the
 * target hardware ships, a near-black surface costs materially less than a
 * light one, so [ExpeditionBlack] is a true black rather than the charcoal
 * Material's baseline dark scheme uses.
 *
 * Deliberately NOT dynamic colour. Material You would hand the palette to
 * whatever wallpaper the user has, which is fine for a launcher and wrong for
 * a tool whose colours carry meaning: training-zone and form-band colours come
 * from spec/zones.yaml so that the phone and the browser agree, and a
 * wallpaper-derived accent could collide with one of them.
 */
private val DarkColors = darkColorScheme(
    primary = TracksGreen,
    onPrimary = Color.Black,
    primaryContainer = TracksGreenDark,
    onPrimaryContainer = TracksGreenLight,
    secondary = TracksBlue,
    onSecondary = Color.Black,
    background = ExpeditionBlack,
    onBackground = Color(0xFFE3E3E3),
    surface = ExpeditionBlack,
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFB4B4B8),
    outline = Color(0xFF3A3A3C),
    error = TracksRed,
    onError = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = TracksGreenDark,
    onPrimary = Color.White,
    primaryContainer = TracksGreenLight,
    onPrimaryContainer = Color(0xFF00210B),
    secondary = TracksBlue,
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFEDEFEB),
    onSurfaceVariant = Color(0xFF41493F),
    outline = Color(0xFF71796E),
    error = TracksRedDark,
    onError = Color.White,
)

@Composable
fun TracksTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Accent = Accent.default,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // The accent replaces the scheme's primary family, and the container
    // slots Material's own components reach for when selected. Every other
    // colour is the app's own — a picker that repainted surfaces and outlines
    // too would be a skin, and this is an accent.
    //
    // secondaryContainer and the tertiary family are here because they were
    // never set, so Material filled them with its baseline lavender, and a
    // selected segmented button (the Week/Month toggle) or input chip showed
    // a colour from neither the accent nor the web. They follow the accent's
    // container, as the web's selected states do.
    val colors = remember(darkTheme, accent) { schemeFor(darkTheme, accent) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge with the status-bar icons flipped to match, so the
            // clock stays readable against our background rather than the
            // platform's assumption about it.
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = TracksTypography,
        shapes = TracksShapes,
        content = content,
    )
}

/**
 * Material's shape roles on the shared radius scale. `medium` is what cards
 * take, and it is the web's `rounded-xl`, so a card has the same corner in
 * both apps.
 */
val TracksShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.Radius.base),
    small = RoundedCornerShape(Tokens.Radius.lg),
    medium = RoundedCornerShape(Tokens.Card.radius),
    large = RoundedCornerShape(Tokens.Radius.xl2),
    extraLarge = RoundedCornerShape(Tokens.Radius.xl2),
)

/** The colour scheme for a theme mode and accent — see [TracksTheme] for what the accent reaches. */
internal fun schemeFor(darkTheme: Boolean, accent: Accent): androidx.compose.material3.ColorScheme {
    val base = if (darkTheme) DarkColors else LightColors
    val container = accent.container(darkTheme)
    val onContainer = accent.onContainer(darkTheme)
    return base.copy(
        primary = accent.primary(darkTheme),
        onPrimary = accent.onPrimary(darkTheme),
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent.primary(darkTheme),
        onTertiary = accent.onPrimary(darkTheme),
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        // An inverse surface is the other theme's surface, so its accent is
        // the other theme's accent: the light accent reads on the dark pill
        // a light theme shows, and the reverse. Left at Material's default it
        // was lavender whatever the user's colour.
        inversePrimary = accent.primary(!darkTheme),
    )
}
