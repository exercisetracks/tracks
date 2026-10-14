// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import android.content.Context
import android.os.StatFs
import com.tracks.core.api.PackManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * The map packs this phone holds, the areas they were saved for, and the
 * answer to "is this tile here?".
 *
 * ## Why packs, and why this file
 *
 * Offline maps used to be MapLibre offline regions: every tile fetched one
 * request at a time into one SQLite database, keyed by its URL. That could not
 * hold a state (millions of requests), and a version in the URL that moved
 * with every server change orphaned every saved tile (server audit F1). A pack
 * is a whole PMTiles file for one layer of one z8 cell (about 110 × 156 km);
 * see the server's services/map_packs.py. The phone downloads packs as files
 * ([PackDownloader]) and answers MapLibre's tile requests from them
 * ([PackInterceptor]) — any number of them, up to whatever storage the person
 * gives it.
 *
 * ## The registry
 *
 * One JSON file, written whole to a temp file and renamed over the old one, so
 * a crash mid-write leaves the previous registry intact. It holds:
 *
 * - **areas** — what the person saved: a name, a box, the cells it needs;
 * - **packs** — per key ("8/x/y/layer" or "shared/name"): the version wanted,
 *   the file currently serving (kept until a newer version has verified), and
 *   how far a download has got;
 * - **settings** — storage volume, budget, Wi-Fi only.
 *
 * A pack is held while any area needs it; removing the last area that does
 * deletes its file at once, so space comes back immediately.
 */
class PackStore(private val context: Context) {

    data class Settings(
        /** Index into [volumes]: 0 is the phone's own storage, then SD cards. */
        val volume: Int = 0,
        /** Most the packs may take; null until chosen (then: half the free space). */
        val budgetBytes: Long? = null,
        val wifiOnly: Boolean = true,
    )

    data class Area(
        val id: String,
        val name: String,
        /** [west, south, east, north] */
        val bbox: List<Double>,
        /** "8/x/y" cells this area needs. */
        val cells: List<String>,
        val createdAt: Long,
        /** The server area it came from, when there is one (for the list). */
        val serverId: Int? = null,
        /** Routing data and the offline search index have been fetched for it. */
        val extras: Boolean = false,
    )

    enum class State { QUEUED, DOWNLOADING, READY, FAILED }

    data class Pack(
        val key: String,
        /** The version wanted (from the latest manifest). */
        val version: String,
        val url: String,
        /** Expected size: exact when the server had built it, else its estimate. */
        val bytes: Long,
        val state: State,
        /** The file serving now — possibly an older version, until the new one lands. */
        val file: String? = null,
        val fileVersion: String? = null,
        val fileBytes: Long = 0,
        val done: Long = 0,
        val error: String? = null,
        val failures: Int = 0,
    ) {
        val current: Boolean get() = state == State.READY && fileVersion == version
    }

    data class Snapshot(
        val areas: List<Area> = emptyList(),
        val packs: Map<String, Pack> = emptyMap(),
        val settings: Settings = Settings(),
    ) {
        fun packsFor(area: Area): List<Pack> =
            packs.values.filter { it.key.startsWith("shared/") || cellOf(it.key) in area.cells }

        /** Bytes on disk now. */
        val usedBytes: Long get() = packs.values.sumOf { if (it.file != null) it.fileBytes else 0 }

        /** Bytes still to fetch for everything wanted. */
        val pendingBytes: Long get() = packs.values.filter { !it.current }.sumOf { (it.bytes - it.done).coerceAtLeast(0) }
    }

    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // ── Storage ──────────────────────────────────────────────────────────────

    /** The places packs can live: the phone's storage, then any SD cards. */
    fun volumes(): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(context.filesDir) }

    fun root(settings: Settings = _state.value.settings): File {
        val all = volumes()
        return File(all.getOrElse(settings.volume) { all.first() }, "maps")
    }

    fun freeBytes(dir: File = root()): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.path).availableBytes
    }.getOrDefault(0)

    /** Never fill the volume: keep the larger of 1 GB or 5% of it free. */
    fun reserveBytes(dir: File = root()): Long = runCatching {
        maxOf(1L shl 30, StatFs(dir.path).totalBytes / 20)
    }.getOrDefault(1L shl 30)

    fun totalBytes(dir: File = root()): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.path).totalBytes
    }.getOrDefault(0)

    /**
     * Most the packs may take: everything the volume has, less its reserve.
     * Offline maps are what the person chose to keep; they are not rationed
     * against free space they would rather fill. (`Settings.budgetBytes` is
     * no longer offered and is ignored, so a limit set by an earlier version
     * cannot quietly refuse downloads.)
     */
    fun budget(): Long = (freeBytes() + _state.value.usedBytes - reserveBytes()).coerceAtLeast(0)

    // ── Areas ────────────────────────────────────────────────────────────────

    /**
     * Save an area: its cells' packs and the shared ones, queued unless held at
     * this version already. Returns the area.
     */
    fun addArea(name: String, bbox: List<Double>, manifest: PackManifest, serverId: Int? = null): Area {
        val area = Area(
            id = UUID.randomUUID().toString(), name = name, bbox = bbox,
            cells = manifest.cells.map { it.cell }, createdAt = System.currentTimeMillis(), serverId = serverId,
        )
        update { s -> s.copy(areas = s.areas + area, packs = merged(s.packs, manifest)) }
        return area
    }

    /** Take in a newer manifest for an area: versions that moved are queued again. */
    fun applyManifest(manifest: PackManifest) {
        update { s -> s.copy(packs = merged(s.packs, manifest)) }
    }

    /** Remove an area, and every pack no remaining area needs. */
    fun removeArea(id: String) {
        val doomed = mutableListOf<File>()
        update { s ->
            val areas = s.areas.filterNot { it.id == id }
            val cells = areas.flatMap { it.cells }.toSet()
            val keep = s.packs.filter { (key, _) ->
                if (key.startsWith("shared/")) areas.isNotEmpty() else cellOf(key) in cells
            }
            s.packs.values.filter { it.key !in keep }.forEach { p ->
                p.file?.let { doomed += File(it) }
                doomed += partFile(p)
            }
            s.copy(areas = areas, packs = keep)
        }
        doomed.forEach { it.delete() }
        closeAll()
    }

    fun markExtras(id: String) = update { s ->
        s.copy(areas = s.areas.map { if (it.id == id) it.copy(extras = true) else it })
    }

    fun setSettings(transform: (Settings) -> Settings) = update { s -> s.copy(settings = transform(s.settings)) }

    // ── Pack lifecycle (the downloader's half) ───────────────────────────────

    fun destination(p: Pack): File {
        val ext = if (p.key == "shared/assets") "zip" else "pmtiles"
        return File(root(), "${p.key}.${p.version}.$ext")
    }

    fun partFile(p: Pack): File = File(destination(p).path + ".part")

    /** Packs to fetch, smallest first: shared ones and small cells finish early. */
    fun pending(): List<Pack> = _state.value.packs.values
        .filter { !it.current && it.state != State.FAILED }
        .sortedBy { it.bytes }

    fun markDownloading(key: String, done: Long) = updatePack(key) { it.copy(state = State.DOWNLOADING, done = done, error = null) }

    fun progress(key: String, done: Long) {
        // In memory only: a registry rewrite per chunk would be thousands of
        // file writes per pack. The file holds the real count anyway.
        _state.value = _state.value.let { s ->
            val p = s.packs[key] ?: return
            s.copy(packs = s.packs + (key to p.copy(done = done)))
        }
    }

    fun markReady(key: String, version: String, file: File) {
        var old: String? = null
        updatePack(key) {
            if (it.version != version) return@updatePack it   // superseded meanwhile
            old = it.file
            it.copy(state = State.READY, file = file.path, fileVersion = version, fileBytes = file.length(),
                    done = file.length(), error = null, failures = 0)
        }
        if (old != null && old != file.path) {
            close(old!!)
            File(old!!).delete()
        }
    }

    fun markFailed(key: String, reason: String, retry: Boolean) = updatePack(key) {
        val failures = it.failures + 1
        it.copy(state = if (retry && failures < MAX_FAILURES) State.QUEUED else State.FAILED,
                error = reason, failures = failures)
    }

    fun markQueued(key: String) = updatePack(key) { it.copy(state = State.QUEUED) }

    /** A pack the server has nothing for: no longer part of any area's needs. */
    fun forget(key: String) = update { s -> s.copy(packs = s.packs - key) }

    /** Failed packs get another go (the person pressed retry). */
    fun retryFailed() = update { s ->
        s.copy(packs = s.packs.mapValues { (_, p) ->
            if (p.state == State.FAILED) p.copy(state = State.QUEUED, failures = 0, error = null) else p
        })
    }

    // ── Lookup (the interceptor's half) ──────────────────────────────────────

    private val open = object : LinkedHashMap<String, PmTiles>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PmTiles>): Boolean {
            if (size > OPEN_FILES) { runCatching { eldest.value.close() }; return true }
            return false
        }
    }
    @Volatile private var assets: ZipFile? = null
    @Volatile private var assetsPath: String? = null

    /**
     * A tile for one of the style's tilesets, from whatever pack holds it, or
     * null. `set` is the path segment the style uses: basemap, master_overlay,
     * master_contours, master_dem, planet_dem_z7, master_routes.
     */
    fun tile(set: String, z: Int, x: Int, y: Int): ByteArray? {
        val packs = _state.value.packs
        val keys: List<String> = when (set) {
            "basemap" -> listOf(if (z < CELL_ZOOM) "shared/world" else cellKey(z, x, y, "basemap"))
            "master_overlay" -> listOf(if (z < CELL_ZOOM) "shared/overlay-low" else cellKey(z, x, y, "overlay"))
            "master_contours" -> if (z < CELL_ZOOM) emptyList() else listOf(cellKey(z, x, y, "contours"))
            "master_dem" -> if (z < CELL_ZOOM) emptyList() else listOf(cellKey(z, x, y, "dem"))
            "master_routes" -> listOf("shared/routes")
            "planet_dem_z7" -> when {
                z <= 5 -> listOf("shared/world-dem")
                // z6-7 terrain tiles span several cells; every cell's dem pack
                // carries its own ancestors, so any held cell under it answers.
                else -> packs.keys.filter { k ->
                    k.endsWith("/dem") && cellOf(k).let { c ->
                        val (_, cx, cy) = c.split("/").map(String::toInt)
                        (cx shr (CELL_ZOOM - z)) == x && (cy shr (CELL_ZOOM - z)) == y
                    }
                }
            }
            else -> emptyList()
        }
        for (k in keys) {
            val path = packs[k]?.file ?: continue
            val bytes = runCatching { reader(path)?.tileDecoded(z, x, y) }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    /** A glyph range or sprite file from the assets pack: "fonts/…" or "sprite/…". */
    fun asset(path: String): ByteArray? {
        val file = _state.value.packs["shared/assets"]?.file ?: return null
        val zip = synchronized(lock) {
            if (assetsPath != file) {
                runCatching { assets?.close() }
                assets = runCatching { ZipFile(file) }.getOrNull()
                assetsPath = file
            }
            assets
        } ?: return null
        val entry = zip.getEntry(path) ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    val hasAny: Boolean get() = _state.value.packs.values.any { it.file != null }

    private fun reader(path: String): PmTiles? = synchronized(open) {
        open[path] ?: runCatching { PmTiles(File(path)) }.getOrNull()?.also { open[path] = it }
    }

    private fun close(path: String) = synchronized(open) { open.remove(path)?.let { runCatching { it.close() } } }

    private fun closeAll() = synchronized(open) {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    /**
     * Startup check: a pack whose file is gone (SD card out, files cleared) or
     * is not a PMTiles archive any more is queued again rather than served.
     */
    fun verifyFiles() = update { s ->
        s.copy(packs = s.packs.mapValues { (_, p) ->
            val f = p.file?.let(::File)
            val ok = f != null && f.exists() && f.length() == p.fileBytes && (p.key == "shared/assets" || looksLikePmTiles(f))
            if (p.file != null && !ok) p.copy(state = State.QUEUED, file = null, fileVersion = null, fileBytes = 0, done = 0) else p
        })
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private val registry: File get() = File(context.filesDir, "map-packs.json")

    private fun update(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val next = transform(_state.value)
            save(next)
            _state.value = next
        }
    }

    private fun updatePack(key: String, transform: (Pack) -> Pack) = update { s ->
        val p = s.packs[key] ?: return@update s
        s.copy(packs = s.packs + (key to transform(p)))
    }

    private fun save(s: Snapshot) {
        val json = JSONObject()
            .put("areas", JSONArray(s.areas.map { a ->
                JSONObject().put("id", a.id).put("name", a.name).put("bbox", JSONArray(a.bbox))
                    .put("cells", JSONArray(a.cells)).put("createdAt", a.createdAt).put("serverId", a.serverId ?: JSONObject.NULL)
                    .put("extras", a.extras)
            }))
            .put("packs", JSONArray(s.packs.values.map { p ->
                JSONObject().put("key", p.key).put("version", p.version).put("url", p.url).put("bytes", p.bytes)
                    .put("state", p.state.name).put("file", p.file ?: JSONObject.NULL)
                    .put("fileVersion", p.fileVersion ?: JSONObject.NULL).put("fileBytes", p.fileBytes)
                    .put("done", p.done).put("error", p.error ?: JSONObject.NULL).put("failures", p.failures)
            }))
            .put("settings", JSONObject().put("volume", s.settings.volume)
                .put("budgetBytes", s.settings.budgetBytes ?: JSONObject.NULL).put("wifiOnly", s.settings.wifiOnly))
        val tmp = File(registry.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(registry)) {
            registry.delete()
            tmp.renameTo(registry)
        }
    }

    private fun load(): Snapshot = runCatching {
        if (!registry.exists()) return Snapshot()
        val j = JSONObject(registry.readText())
        val areas = j.getJSONArray("areas").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Area(o.getString("id"), o.getString("name"),
                     o.getJSONArray("bbox").let { b -> (0 until b.length()).map(b::getDouble) },
                     o.getJSONArray("cells").let { c -> (0 until c.length()).map(c::getString) },
                     o.getLong("createdAt"), if (o.isNull("serverId")) null else o.getInt("serverId"),
                     o.optBoolean("extras", false))
            }
        }
        val packs = j.getJSONArray("packs").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Pack(o.getString("key"), o.getString("version"), o.getString("url"), o.getLong("bytes"),
                     // A download that was running when the process died is
                     // queued again; its .part file is still there to resume.
                     State.valueOf(o.getString("state")).let { if (it == State.DOWNLOADING) State.QUEUED else it },
                     o.optStringOrNull("file"), o.optStringOrNull("fileVersion"), o.getLong("fileBytes"),
                     o.getLong("done"), o.optStringOrNull("error"), o.getInt("failures"))
            }.associateBy { it.key }
        }
        val st = j.getJSONObject("settings")
        Snapshot(areas, packs, Settings(st.getInt("volume"),
                                        if (st.isNull("budgetBytes")) null else st.getLong("budgetBytes"),
                                        st.getBoolean("wifiOnly")))
    }.getOrElse { Snapshot() }

    companion object {
        const val CELL_ZOOM = 8
        private const val OPEN_FILES = 48
        const val MAX_FAILURES = 5

        fun cellOf(key: String): String = key.split("/").take(3).joinToString("/")

        fun cellKey(z: Int, x: Int, y: Int, layer: String): String {
            val shift = z - CELL_ZOOM
            return "$CELL_ZOOM/${x shr shift}/${y shr shift}/$layer"
        }

        private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

        private fun looksLikePmTiles(f: File): Boolean = runCatching {
            val head = ByteArray(7)
            f.inputStream().use { it.read(head) == 7 && String(head, Charsets.US_ASCII) == "PMTiles" }
        }.getOrDefault(false)

        /** Every pack a manifest names, merged over what is held. */
        internal fun merged(held: Map<String, Pack>, manifest: PackManifest): Map<String, Pack> {
            val out = held.toMutableMap()
            fun take(key: String, version: String, url: String, bytes: Long) {
                val p = out[key]
                out[key] = when {
                    p == null -> Pack(key, version, url, bytes, State.QUEUED)
                    p.version == version -> p.copy(url = url, bytes = if (p.current) p.fileBytes else bytes)
                    // A newer version: fetch it, keep serving the old file meanwhile.
                    else -> p.copy(version = version, url = url, bytes = bytes,
                                   state = State.QUEUED, done = 0, error = null, failures = 0)
                }
            }
            manifest.cells.forEach { c -> c.layers.forEach { (layer, e) -> take("${c.cell}/$layer", e.version, e.url, e.bytes) } }
            manifest.shared.forEach { (name, e) -> take("shared/$name", e.version, e.url, e.bytes) }
            return out
        }
    }
}
