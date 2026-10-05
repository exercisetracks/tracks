// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether this person has a watch — the "I have a Garmin watch" answer from
 * onboarding and Settings ([com.tracks.app.AppContainer.hasDevice]).
 *
 * A composition local rather than a parameter because the controls it hides
 * are scattered through a dozen screens several layers deep — "Sync to watch"
 * on a goal card, "Save to my watch" in a builder, "Send to device" on a map
 * track — and threading one boolean through every screen between the nav host
 * and those leaves would touch far more code than the gate itself.
 *
 * Hiding only: every screen still stores and syncs the watch fields it always
 * did (a flow keeps `sync_to_watch`, a track keeps `load_to_device`), so
 * turning the switch back on brings each control back in the state it was left.
 *
 * Defaults to true — the migration-safe answer the container also uses, and
 * what a screenshot test or preview without a provider should draw.
 */
val LocalHasDevice = compositionLocalOf { true }
