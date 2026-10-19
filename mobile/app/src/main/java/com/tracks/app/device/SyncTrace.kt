// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few hundred lines of what watch syncs did, kept on the phone.
 *
 * Exists for one bug: syncs that kept failing until the app was force-stopped,
 * two or three times a day, with no pattern anyone could see. Logcat is gone
 * by the time someone notices, so each sync's phases -- and each time the
 * runner gave up on a connection and rebuilt it -- are appended here, and
 * Settings > Watch shares the file. A pattern needs many occurrences to show.
 *
 * What is written is the phase summary the runner already logs: counts and
 * error messages, never file contents, positions or anything from the user's
 * data. Kept in app-private storage, and shared only when the person taps
 * the button and picks where it goes.
 */
object SyncTrace {
    private const val FILE = "watch-sync-trace.log"
    private const val MAX_LINES = 400

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun record(context: Context, outcome: WatchSyncRunner.Outcome) {
        val line = buildString {
            append(outcome.phases.joinToString("; "))
            if (outcome.recordingOnWatch) append(" [watch recording]")
            if (outcome.refusedByWatch > 0) append(" [refused ${outcome.refusedByWatch}]")
        }
        note(context, line)
    }

    @Synchronized
    fun note(context: Context, message: String) {
        runCatching {
            val f = file(context)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp  ${message.replace('\n', ' ')}"
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }
}
