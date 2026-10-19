// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context

/**
 * The map's layer switches, kept on this phone so the map opens the way it was
 * left -- across leaving the screen and restarting the app, not only a
 * rotation. Per device, like the browser's (`useLayerToggles.js`): a phone and
 * a desktop are often wanted differently.
 *
 * Only switches the person flipped are kept, each as on or off, and only for
 * groups that still exist; everything else follows its default, so a group
 * added in an update arrives in its default state rather than hidden.
 */
object LayerChoices {
    private const val PREFS = "tracks_map"
    private const val KEY = "layer_choices"

    /** The groups to hide, given what was saved as `id=1` / `id=0`. */
    fun hidden(saved: Set<String>, groups: List<LayerGroup> = LAYER_GROUPS): Set<String> {
        val chosen = saved.mapNotNull { entry ->
            val (id, on) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id to (on == "1")
        }.toMap()
        return groups.filter { !(chosen[it.id] ?: it.onByDefault) }.map { it.id }.toSet()
    }

    /** [saved] with one switch recorded. */
    fun with(saved: Set<String>, id: String, on: Boolean): Set<String> =
        saved.filterNot { it.substringBefore('=') == id }.toSet() + "$id=${if (on) 1 else 0}"

    fun load(context: Context): Set<String> =
        hidden(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null).orEmpty())

    fun save(context: Context, id: String, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A copy: the set getStringSet returns must not be modified or stored back.
        val saved = prefs.getStringSet(KEY, null).orEmpty().toSet()
        prefs.edit().putStringSet(KEY, with(saved, id, on)).apply()
    }
}
