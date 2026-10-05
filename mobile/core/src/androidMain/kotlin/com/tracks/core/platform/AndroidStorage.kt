// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.tracks.core.api.DeviceKeyCredential
import com.tracks.core.api.StoredSession
import com.tracks.core.api.TokenStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import android.util.Base64

/**
 * The Android side of storage: an encrypted database and a Keystore-backed
 * place to keep credentials.
 *
 * Both matter for the same reason. The offline mirror holds decrypted GPS that
 * the server deliberately never stores in the clear, and the device secret
 * decrypts a user's entire history. Left in plain files they would make the
 * phone the weakest link in a system built around per-user envelope encryption
 * — which would be a strange thing to do right after building that system.
 */

private const val PREFS_NAME = "tracks_session"
private const val KEY_ACCESS = "access_token"
private const val KEY_REFRESH = "refresh_token"
private const val KEY_DEVICE_ID = "device_key_id"
private const val KEY_DEVICE_SECRET = "device_key_secret"
private const val KEY_AGENT_TOKEN = "agent_token"
private const val KEY_DB_PASSPHRASE = "db_passphrase"

/**
 * Credentials in `EncryptedSharedPreferences`, whose master key lives in the
 * Android Keystore.
 *
 * EncryptedSharedPreferences rather than raw Keystore calls because it is the
 * platform's own answer to this problem and gets the parts that are easy to get
 * wrong — key rotation, IV handling, AEAD — right by default. The master key is
 * hardware-backed where the device offers it.
 *
 * Not marked `setUserAuthenticationRequired`: that would make every background
 * sync fail while the phone is locked in a pocket, which is precisely when this
 * app needs to work. The trade is deliberate and is the one the user asked for
 * when they said a weekly password prompt was unacceptable; the mitigation is
 * that a lost device is revoked server-side, which a password cannot be.
 */
class AndroidTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun load(): StoredSession = withContext(Dispatchers.IO) {
        val deviceId = prefs.getInt(KEY_DEVICE_ID, -1)
        val deviceSecret = prefs.getString(KEY_DEVICE_SECRET, null)
        StoredSession(
            accessToken = prefs.getString(KEY_ACCESS, null),
            refreshToken = prefs.getString(KEY_REFRESH, null),
            deviceKey = if (deviceId >= 0 && deviceSecret != null) {
                DeviceKeyCredential(deviceId, deviceSecret)
            } else null,
            agentToken = prefs.getString(KEY_AGENT_TOKEN, null),
        )
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            putStringOrRemove(KEY_ACCESS, session.accessToken)
            putStringOrRemove(KEY_REFRESH, session.refreshToken)
            putStringOrRemove(KEY_AGENT_TOKEN, session.agentToken)
            val key = session.deviceKey
            if (key != null) {
                putInt(KEY_DEVICE_ID, key.id)
                putString(KEY_DEVICE_SECRET, key.secret)
            } else {
                remove(KEY_DEVICE_ID)
                remove(KEY_DEVICE_SECRET)
            }
        }.apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // Deliberately not clearing the database passphrase: it lives here too,
        // and wiping it on logout would strand an encrypted database nobody can
        // open. The caller deletes the database itself if it wants that.
        //
        // The agent token IS cleared, and has to be: it uploads on behalf of the
        // account that just signed out, and it needs no session to keep doing so.
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_SECRET)
            .remove(KEY_AGENT_TOKEN)
            .apply()
    }

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }
}

/**
 * Opens the offline mirror through SQLCipher.
 *
 * The passphrase is 32 random bytes generated on first run and kept in the same
 * Keystore-backed preferences as the credentials — so the database is readable
 * only by this app on this device, and an extracted database file is inert.
 */
object EncryptedDatabase {

    private const val DB_NAME = "tracks.db"

    fun driver(context: Context): SqlDriver {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphrase(context))
        return AndroidSqliteDriver(
            // Not TracksDb.Schema: the version and the migrations are kept by
            // hand, starting from the 1.0.0 baseline. See TracksSchema.
            schema = TracksSchema,
            context = context.applicationContext,
            name = DB_NAME,
            factory = factory,
            // Write-ahead logging, so readers are not locked out while a
            // writer works. Without it SQLite hands out one connection: a
            // first sync's history import then blocks every screen's reads,
            // and a read on the main thread becomes "app not responding".
            callback = object : AndroidSqliteDriver.Callback(TracksSchema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                }
            },
        )
    }

    /**
     * Deletes the whole database: the mirror, which re-syncs, but also the
     * outbox and the replica's synced rows (Replica.sq), which may hold edits
     * the server has not seen — and, for a phone with no server, the only
     * copy. Callers must mean that.
     */
    fun delete(context: Context) {
        context.applicationContext.deleteDatabase(DB_NAME)
    }

    private fun passphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let {
            return Base64.decode(it, Base64.NO_WRAP)
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }
}
