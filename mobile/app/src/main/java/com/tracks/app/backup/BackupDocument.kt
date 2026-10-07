// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.io.OutputStream

/**
 * Write a backup into a document, and take the document away again if that
 * fails.
 *
 * The system picker creates the file the moment someone chooses where it goes,
 * before a byte is written. Left behind after a failure — empty, or cut off
 * partway — it sits in their backup folder looking like a backup, and is
 * found to be nothing only on the day it is needed. So any failure, an
 * OutOfMemoryError or a cancelled coroutine included, discards it before the
 * error goes on.
 */
inline fun writeOrDiscard(open: () -> OutputStream, discard: () -> Unit, write: (OutputStream) -> Unit) {
    try {
        open().use(write)
    } catch (e: Throwable) {
        runCatching(discard)
        throw e
    }
}
