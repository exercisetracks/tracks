// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tracks.core.local.RawBlobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * FIT files in app-private storage, each sealed with AES-GCM under a key that
 * never leaves the Android Keystore.
 *
 * Encrypted because a FIT file is a GPS trace: the server only ever stores
 * these sealed, and the phone's database is SQLCipher for the same reason, so
 * plain files beside it would make the phone the weakest copy. App-private
 * storage alone is not enough — it is readable on a rooted phone and in any
 * backup that includes it.
 *
 * The key is not user-authentication-bound: watch sync runs in the background
 * with the screen off, and a key that needed a fingerprint would stop every
 * overnight sync. It is bound to this install, which is the same guarantee
 * the database key gives.
 *
 * Layout: `files/blobs/<sha256>`, holding `iv (12 bytes) || ciphertext+tag`.
 */
class AndroidBlobs(context: Context) : RawBlobs {
    private val dir = File(context.filesDir, "blobs").apply { mkdirs() }

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

    private fun file(sha: String): File {
        // A hash is 64 hex characters; anything else is not ours to open.
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "not a sha256: $sha" }
        return File(dir, sha)
    }

    override suspend fun has(sha256: String): Boolean = withContext(Dispatchers.IO) { file(sha256).exists() }

    override suspend fun get(sha256: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = file(sha256)
        if (!f.exists()) return@withContext null
        val blob = f.readBytes()
        if (blob.size < IV_BYTES) return@withContext null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_BYTES))
        runCatching { cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES) }.getOrNull()
    }

    override suspend fun put(sha256: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.iv + cipher.doFinal(bytes)
        // Written aside and renamed, so a process killed mid-write leaves no
        // half file for `has` to report as present.
        val tmp = File(dir, "$sha256.part")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file(sha256))) { "could not store $sha256" }
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        dir.list()?.filter { it.length == 64 }.orEmpty()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tracks_blobs"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
