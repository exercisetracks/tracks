// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.LocationSymbol
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLocation

/** One saved place, in the shape [LocationsFitEncoder] needs to write it. */
data class WaypointLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val elevationMetres: Double?,
    val icon: String,
)

/**
 * A watch's whole `Locations.fit`, built on the phone with no server to ask.
 *
 * Ported from the backend's `fit_locations.py`, which hand-packed every byte
 * itself because the Python FIT SDK's generated profile has no message 29 at
 * all. This build's own profile does — see [FitLocation] — so this is an
 * ordinary use of the same generated builder API
 * [com.tracks.device.garmin.CourseFitEncoder] uses, not a second byte-packer
 * to keep in step with the first.
 *
 * A watch keeps every saved place in ONE file, so this always takes the
 * whole desired set and rebuilds it — see
 * `com.tracks.core.sync.WaypointPushJob`. An empty list is a deliberate,
 * valid input: it produces a file with a `file_id` and no location records,
 * which is what gets pushed when the last saved place is unloaded and there
 * is no delete on this transport to ask for instead.
 */
object LocationsFitEncoder {

    /** A saved place's name has 32 bytes of wire capacity; one is held for the NUL terminator. */
    private const val NAME_BYTES = 31

    private const val PRODUCT_CONNECT = 65534

    private val DEFAULT_SYMBOL = LocationSymbol.Pin_Blue

    /**
     * App icon name → Garmin location symbol, mirroring `_SYMBOL` in
     * `fit_locations.py` id for id, so a place looks the same on the wrist
     * whichever side encoded it last.
     */
    private val ICON_TO_SYMBOL: Map<String, LocationSymbol> = buildMap {
        put("marker", LocationSymbol.Pin_Blue)
        put("water", LocationSymbol.Drinking_Water)
        put("camp", LocationSymbol.Campground)
        put("parking", LocationSymbol.Parking_Area)
        put("summit", LocationSymbol.Summit)
        put("viewpoint", LocationSymbol.Scenic_Area)
        put("shelter", LocationSymbol.Lodging)
        put("food", LocationSymbol.Restaurant)
        put("danger", LocationSymbol.Skull_and_Crossbones)
        put("car", LocationSymbol.Car)
        put("camp_site", LocationSymbol.Campground)
        put("alpine_hut", LocationSymbol.Lodging)
        put("wilderness_hut", LocationSymbol.Lodging)
        put("toilets", LocationSymbol.Restroom)
        put("fast_food", LocationSymbol.Fast_Food)
        put("cafe", LocationSymbol.Restaurant)
        put("ranger_station", LocationSymbol.Information)
        put("marina", LocationSymbol.Boat_Ramp)
        put("drinking_water", LocationSymbol.Drinking_Water)
        put("water_source", LocationSymbol.Water_Source)
        put("spring", LocationSymbol.Water_Source)
        put("campground", LocationSymbol.Campground)
        put("peak", LocationSymbol.Summit)
        put("trailhead", LocationSymbol.Trail_Head)
        put("scenic", LocationSymbol.Scenic_Area)
        put("lodge", LocationSymbol.Lodge)
        put("lodging", LocationSymbol.Lodging)
        put("restaurant", LocationSymbol.Restaurant)
        put("picnic", LocationSymbol.Picnic_Area)
        put("restroom", LocationSymbol.Restroom)
        put("shower", LocationSymbol.Shower)
        put("fuel", LocationSymbol.Gas_Station)
        put("gas_station", LocationSymbol.Gas_Station)
        put("medical", LocationSymbol.Medical_Facility)
        put("first_aid", LocationSymbol.Medical_Facility)
        put("pharmacy", LocationSymbol.Pharmacy)
        put("information", LocationSymbol.Information)
        put("park", LocationSymbol.Park)
        put("beach", LocationSymbol.Beach)
        put("fishing", LocationSymbol.Fishing_Area)
        put("swimming", LocationSymbol.Swimming_Area)
        put("skiing", LocationSymbol.Skiing_Area)
        put("bike", LocationSymbol.Bike_Trail)
        put("bridge", LocationSymbol.Bridge)
        put("dam", LocationSymbol.Dam)
        put("building", LocationSymbol.Building)
        put("school", LocationSymbol.School)
        put("bank", LocationSymbol.Bank)
        put("store", LocationSymbol.Convenience_Store)
        put("geocache", LocationSymbol.Geocache)
    }

    fun encode(
        waypoints: List<WaypointLocation>,
        timeCreatedEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): ByteArray {
        val records = mutableListOf<RecordData>()

        records += FitFileId.Builder()
            .setType(FileType.FILETYPE.LOCATION)
            .setManufacturer(1)
            .setProduct(PRODUCT_CONNECT)
            .setSerialNumber(1L)
            .setTimeCreated(timeCreatedEpochSeconds)
            .build(0)

        waypoints.forEachIndexed { index, point ->
            records += FitLocation.Builder()
                .setMessageIndex(index)
                .setTimestamp(timeCreatedEpochSeconds)
                .setName(truncateUtf8(point.name.ifBlank { "Waypoint" }, NAME_BYTES))
                .setPositionLat(point.lat)
                .setPositionLong(point.lng)
                .setSymbol(ICON_TO_SYMBOL[point.icon.lowercase()] ?: DEFAULT_SYMBOL)
                .setAltitude(point.elevationMetres?.toFloat())
                .build(1)
        }

        return FitFile(records).outgoingMessage
    }

    /**
     * Read a watch's saved-places file back: every location with a position,
     * as the same [WaypointLocation] [encode] writes. A port of the server's
     * `parse_locations_fit` field for field (message 29: 0 name, 1 latitude,
     * 2 longitude, 3 symbol, 4 altitude), so the phone and the server read one
     * file the same way. Never throws: a malformed tail ends the list early.
     */
    fun decode(data: ByteArray): List<WaypointLocation> {
        if (data.size < 14 || String(data, 8, 4, Charsets.US_ASCII) != ".FIT") return emptyList()
        val headerSize = data[0].toInt() and 0xff
        val declared = le(data, 4, 4, signed = false)
        val end = minOf(headerSize + declared, data.size.toLong()).toInt()
        val defs = HashMap<Int, Triple<Int, List<Pair<Int, Int>>, Boolean>>()
        val out = mutableListOf<WaypointLocation>()
        val symbols = LocationSymbol.values().associateBy { it.num }
        val iconFor = HashMap<LocationSymbol, String>().apply { ICON_TO_SYMBOL.forEach { (icon, sym) -> putIfAbsent(sym, icon) } }
        var at = headerSize
        while (at < end) {
            val header = data[at].toInt() and 0xff
            at++
            if (header and 0x80 != 0) break
            if (header and 0x40 != 0) {
                if (at + 5 > end) break
                val little = data[at + 1].toInt() == 0
                val global = if (little) le(data, at + 2, 2, false).toInt() else be(data, at + 2, 2).toInt()
                val count = data[at + 4].toInt() and 0xff
                at += 5
                val fields = mutableListOf<Pair<Int, Int>>()
                repeat(count) {
                    if (at + 3 > end) return out
                    fields += (data[at].toInt() and 0xff) to (data[at + 1].toInt() and 0xff)
                    at += 3
                }
                if (header and 0x20 != 0) {
                    if (at >= end) return out
                    val dev = data[at].toInt() and 0xff
                    at++
                    repeat(dev) {
                        if (at + 3 > end) return out
                        fields += -1 to (data[at + 1].toInt() and 0xff)
                        at += 3
                    }
                }
                defs[header and 0x0f] = Triple(global, fields, little)
                continue
            }
            val (global, fields, little) = defs[header and 0x0f] ?: break
            val values = HashMap<Int, IntRange>()
            for ((number, size) in fields) {
                if (at + size > end) return out
                values[number] = at until at + size
                at += size
            }
            if (global != 29) continue
            fun int(field: Int, signed: Boolean): Long? {
                val r = values[field] ?: return null
                val n = r.last - r.first + 1
                val v = if (little) le(data, r.first, n, signed) else be(data, r.first, n, signed)
                val invalid = (1L shl (n * 8 - if (signed) 1 else 0)) - 1
                return v.takeIf { it != invalid }
            }
            val lat = int(1, true) ?: continue
            val lng = int(2, true) ?: continue
            val name = values[0]?.let { r ->
                val raw = data.copyOfRange(r.first, r.last + 1)
                val nul = raw.indexOf(0).let { if (it < 0) raw.size else it }
                String(raw, 0, nul, Charsets.UTF_8)
            }.orEmpty()
            val symbol = int(3, false)?.let { symbols[it.toInt()] }
            out += WaypointLocation(
                name = name.ifEmpty { "Waypoint" },
                lat = lat * 180.0 / 2147483648.0,
                lng = lng * 180.0 / 2147483648.0,
                elevationMetres = int(4, false)?.let { it / 5.0 - 500.0 },
                icon = symbol?.let(iconFor::get) ?: "marker",
            )
        }
        return out
    }

    private fun le(b: ByteArray, at: Int, n: Int, signed: Boolean = false): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((b[at + k].toLong() and 0xff) shl (8 * k))
        return if (signed && n < 8 && v and (1L shl (n * 8 - 1)) != 0L) v - (1L shl (n * 8)) else v
    }

    private fun be(b: ByteArray, at: Int, n: Int, signed: Boolean = false): Long {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (b[at + k].toLong() and 0xff)
        return if (signed && n < 8 && v and (1L shl (n * 8 - 1)) != 0L) v - (1L shl (n * 8)) else v
    }
}
