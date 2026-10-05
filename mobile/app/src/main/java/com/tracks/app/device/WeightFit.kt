// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitWeightScale

/**
 * A weigh-in, in the shape a Garmin watch already knows how to import.
 *
 * ## Why the watch gets this and the water does not
 *
 * Because weight is the one entered figure the watch has a use for. It feeds
 * the calorie model, so a watch running on a body mass from last spring
 * quietly mis-estimates every burn until somebody corrects it on the device —
 * and correcting it on the device is four levels of menu. A scale that talks
 * to Garmin solves this by dropping a `WEIGHT` file into `GARMIN/NewFiles`,
 * and that is exactly what this builds: file type 9, one `weight_scale`
 * record, the same thing an Index scale sends.
 *
 * Water and food have no such path. Garmin does not model hydration or intake
 * on the watch at all — both live in Connect, which the watch reads from
 * rather than writes to — so there is no file to send and nothing on the
 * device that would change if there were. They stay phone-and-server figures,
 * and the sheet says so rather than implying a sync that cannot happen.
 *
 * ## Why kilograms
 *
 * FIT stores mass in kilograms and always has, whatever the display unit. The
 * conversion happens once, where the value is typed.
 */
object WeightFit {

    /**
     * One weigh-in as a FIT file, or null for a figure no scale would report.
     *
     * The bound is not decoration: this file goes to a device that will use
     * the number in its own arithmetic, and a mistyped 1,750 would leave the
     * watch computing a fortnight of calorie targets for something that is not
     * a person. Rejecting it here costs a push; accepting it costs a manual
     * correction on the watch.
     */
    fun build(kilograms: Double, atEpochSeconds: Long): ByteArray? {
        if (kilograms !in MIN_KG..MAX_KG) return null

        val records = mutableListOf<RecordData>()
        records += FitFileId.Builder().apply {
            setType(FileType.FILETYPE.WEIGHT)
            setManufacturer(MANUFACTURER_DEVELOPMENT)
            setProduct(1)
            // Derived from the instant, so the same weigh-in encoded twice is
            // the same file. The watch de-duplicates on file identity, and a
            // random serial would let a retried push land twice.
            setSerialNumber(atEpochSeconds)
            setTimeCreated(atEpochSeconds)
        }.build(LOCAL_FILE_ID)

        records += FitWeightScale.Builder().apply {
            setTimestamp(atEpochSeconds)
            setWeight(kilograms.toFloat())
        }.build(LOCAL_WEIGHT)

        return FitFile(records).outgoingMessage
    }

    /** The name the watch files it under. Unique per weigh-in, and sortable. */
    fun filename(atEpochSeconds: Long): String = "TRKW$atEpochSeconds.FIT"

    /** Garmin's "development" manufacturer, as [RunFit] uses for the same reason. */
    private const val MANUFACTURER_DEVELOPMENT = 255

    private const val LOCAL_FILE_ID = 0
    private const val LOCAL_WEIGHT = 1

    /** Beyond these a figure is a typo rather than a person. */
    private const val MIN_KG = 20.0
    private const val MAX_KG = 400.0
}
