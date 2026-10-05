// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The vendored Gadgetbridge code is upstream plus `vendor/patches`, and nothing
 * edited in place.
 *
 * An edit made straight into a vendored file works — until the next
 * `pull-gadgetbridge.sh`, which overwrites the file and re-applies only the
 * recorded patches. On 2026-09-30, 20 vendored files were found carrying
 * changes no patch recorded, including the recovery listing that brings
 * activities in when a watch's own Wi-Fi upload has hidden them. This makes
 * that mistake fail the day it is made, without a network or an upstream
 * checkout: `vendor/gadgetbridge.lock` holds the hash of every vendored file
 * as upstream + patches produce it, written by `vendor/check-vendored.py
 * --write-lock` only after it has rebuilt that baseline and found the tree
 * identical.
 *
 * To change a vendored file: edit it, record the change as a patch (see
 * device-garmin/SHIMS.md), then re-run check-vendored.py with --write-lock.
 */
class VendoredLockTest {

    private val repo: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "mobile/vendor/gadgetbridge.lock").exists() }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun every_vendored_file_is_upstream_plus_a_recorded_patch() {
        val lock = File(repo, "mobile/vendor/gadgetbridge.lock").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }

        val onDisk = (
            File(repo, "mobile/device-garmin/src/main/java/nodomain").walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") } +
                File(repo, "mobile/device-garmin/src/main/proto").walkTopDown()
                    .filter { it.isFile && it.extension == "proto" }
            ).associateBy { it.relativeTo(repo).invariantSeparatorsPath }

        val edited = lock.filter { (path, hash) -> onDisk[path]?.let(::sha256) != hash }.keys
        val unlisted = onDisk.keys - lock.keys
        if (edited.isNotEmpty() || unlisted.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Vendored Gadgetbridge files no longer match vendor/gadgetbridge.lock.")
                    appendLine("Record the change as a patch in mobile/vendor/patches (see SHIMS.md),")
                    appendLine("then run mobile/vendor/check-vendored.py <gadgetbridge checkout> --write-lock.")
                    edited.forEach { appendLine("  changed: $it") }
                    unlisted.forEach { appendLine("  not in the lock: $it") }
                },
            )
        }
        assertTrue(lock.size > 200, "the lock looks truncated: ${lock.size} entries")
    }
}
