// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import com.tracks.app.AppContainer
import com.tracks.app.map.PendingRegions
import com.tracks.core.api.PackManifest

/**
 * Turning "save this box" into queued packs — shared by the downloads sheet
 * and the background job, so an area whose server build finishes while the
 * app is closed is saved by the job without waiting for the sheet to reopen.
 */
object PackSaver {

    sealed interface Result {
        data object Saved : Result
        data class Refused(val reason: String) : Result
    }

    /** Bytes a manifest would add to what is held at the current versions. */
    fun newBytes(store: PackStore, m: PackManifest): Long {
        val held = store.state.value.packs
        fun extra(key: String, version: String, bytes: Long) =
            if (held[key]?.current == true && held[key]?.version == version) 0 else bytes
        return m.cells.sumOf { c -> c.layers.entries.sumOf { (l, e) -> extra("${c.cell}/$l", e.version, e.bytes) } } +
            m.shared.entries.sumOf { (n, e) -> extra("shared/$n", e.version, e.bytes) }
    }

    /**
     * Fetch the manifest, check the budget and the volume's free space, and
     * queue the packs. Refused, with the numbers, when the area would not fit:
     * a download that fails a third of the way for want of space is the one
     * thing worse than being told before it starts.
     */
    suspend fun save(container: AppContainer, name: String, bbox: List<Double>, serverId: Int?): Result {
        val store = container.packs
        val m = runCatching { container.client().packManifest(bbox.joinToString(",")) }.getOrElse { e ->
            return Result.Refused("Could not ask the server what to save: ${e.message}")
        }
        val need = newBytes(store, m)
        val room = minOf(store.budget() - store.state.value.usedBytes, store.freeBytes() - store.reserveBytes())
        serverId?.let { PendingRegions.settled(container.appContext, it) }
        if (need > room) {
            return Result.Refused("$name needs ${gb(need)} and there is room for ${gb(room.coerceAtLeast(0))}. " +
                "Raise the storage limit or remove an area.")
        }
        store.addArea(name, bbox, m, serverId)
        return Result.Saved
    }

    private fun gb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
}
