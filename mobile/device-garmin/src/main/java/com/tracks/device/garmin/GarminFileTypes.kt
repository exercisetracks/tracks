// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile

/**
 * Works out what kind of file the server just handed us.
 *
 * ## Why the folder is not enough
 *
 * The Tracks server describes a pushable file by the folder a *cable* sync would
 * write it to — `GARMIN/NewFiles`, `GARMIN/Courses`. That is the right contract
 * for the USB/MTP path the web app uses, and it is the wrong shape for BLE,
 * which has no filesystem at all: `CreateFileMessage` addresses a numeric type.
 *
 * Worse, `GARMIN/NewFiles` is a catch-all. Workouts, race plans and the training
 * schedule all land there, and they are three different Garmin types. So the
 * folder alone genuinely cannot answer the question.
 *
 * ## Why the file can answer it
 *
 * Every FIT file opens with a `file_id` message that states its own type, and
 * the parser for it is already vendored — it is the same thing Gadgetbridge's
 * install handler does before pushing a file a user picked from disk. Reading it
 * is cheap and it is authoritative.
 *
 * The folder is kept as a fallback for the case where parsing fails, which
 * mostly means a file the server generated with a header this build of the FIT
 * profile does not recognise. Better to push a course as a course than to refuse
 * it.
 */
internal object GarminFileTypes {

    fun resolve(folder: String, filename: String, bytes: ByteArray): FileType.FILETYPE? {
        // Ephemeris is not a FIT file at all, so it cannot be sniffed — and it
        // cannot be pushed over BLE either, by anyone. The watch pulls AGPS as
        // an HTTP-proxy request, which Tracks declines; there is no create-file
        // exchange for it and no on-watch path to address, so the folder the
        // server names here only means anything to the USB/MTP path. See
        // HttpHandler for what implementing it actually involves.
        if (isEphemeris(folder, filename)) {
            return null
        }

        // A Connect IQ app is not a FIT file either, so sniffing would fail and
        // the folder fallback would return null. It is the one non-FIT thing
        // the BLE protocol *can* carry: FileType 255/17 exists precisely for
        // it, which is how a watch app gets installed without the Connect IQ
        // Store, a Garmin account, or a cable.
        if (isWatchApp(filename)) {
            return FileType.FILETYPE.PRG
        }

        runCatching { FitFile.parseIncoming(bytes).fileType }
            .getOrNull()
            // The type the file says it is, always. A hook used to sit here for
            // overriding the schedule's type, on the theory that the watch wanted
            // a different number; it was never assigned, and the capture work
            // since has shown the number was never the problem -- the same bytes
            // build a calendar over USB. See AGENTS.md.
            ?.let { sniffed -> return sniffed }

        return when {
            folder.contains("Courses", ignoreCase = true) -> FileType.FILETYPE.COURSES
            folder.contains("Workouts", ignoreCase = true) -> FileType.FILETYPE.WORKOUTS
            else -> null
        }
    }

    fun isWatchApp(filename: String): Boolean =
        filename.endsWith(".prg", ignoreCase = true)

    fun isEphemeris(folder: String, filename: String): Boolean =
        folder.contains("REMOTESW", ignoreCase = true) ||
            filename.endsWith(".bin", ignoreCase = true)
}
