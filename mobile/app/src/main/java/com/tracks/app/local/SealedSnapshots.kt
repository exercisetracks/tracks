// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The screens' last state between launches (com.tracks.core.local.ScreenSnapshots),
 * sealed as the FIT files are.
 *
 * ## Why sealed, when it is "only a cache"
 *
 * What is in it is the dashboard's and the Health page's figures — resting
 * heart rate, sleep, weight, medications, injuries. The database is SQLCipher
 * and the FIT files are AES-GCM under a Keystore key (see [AndroidBlobs]) so
 * that nothing on this phone is weaker than the server's copy; a plain file
 * of the same records beside them would be. Its own Keystore key, not
 * user-authentication-bound for the same reason as the blobs' (the screens
 * need it before anyone has touched anything), in `noBackupFilesDir` so it is
 * never copied into a cloud backup. Erasing the phone's data clears it.
 *
 * ## Why read ahead
 *
 * [preload] runs as the process starts, on its own thread, so by the time a
 * screen's view model is built the text is already in memory and [peek] can
 * hand it over synchronously — the first frame shows the old figures rather
 * than an empty chart. A screen built before it finishes waits for it with
 * [await] off the main thread; either way it is far quicker than the
 * computation it stands in for.
 */
class SealedSnapshots(context: Context) {

    private val dir = File(context.noBackupFilesDir, "snapshots").apply { mkdirs() }
    private val memo = ConcurrentHashMap<String, String>()
    private val ready = CountDownLatch(1)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "snapshots").apply { isDaemon = true } }

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** Read every snapshot into memory, once, in the background. */
    fun preload() {
        writer.execute {
            try {
                dir.listFiles()?.filter { it.name.endsWith(SUFFIX) }?.forEach { f ->
                    open(f.readBytes())?.let { memo[f.name.removeSuffix(SUFFIX)] = it }
                }
            } catch (_: Throwable) {
                // A key lost to a restore or an OS upgrade: no snapshots, which
                // only means one slow start.
            } finally {
                ready.countDown()
            }
        }
    }

    /** The snapshot if [preload] has finished; null if it has not, or there is none. */
    fun peek(name: String): String? = if (ready.count == 0L) memo[name] else null

    /** The snapshot, waiting briefly for [preload]. Call off the main thread. */
    fun await(name: String): String? {
        ready.await(2, TimeUnit.SECONDS)
        return memo[name]
    }

    /** Keep [text] as [name]'s snapshot. Sealed and written on the store's own thread. */
    fun write(name: String, text: String) {
        if (memo[name] == text) return
        memo[name] = text
        writer.execute {
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val sealed = cipher.iv + cipher.doFinal(text.toByteArray())
                val tmp = File(dir, "$name$SUFFIX.part")
                tmp.writeBytes(sealed)
                tmp.renameTo(File(dir, "$name$SUFFIX"))
            }
        }
    }

    /** Forget every snapshot — with the rest of the phone's data. */
    fun clear() {
        memo.clear()
        writer.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun open(blob: ByteArray): String? {
        if (blob.size < IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_BYTES))
        return runCatching { String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)) }.getOrNull()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tracks_snapshots"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val SUFFIX = ".sealed"
    }
}
