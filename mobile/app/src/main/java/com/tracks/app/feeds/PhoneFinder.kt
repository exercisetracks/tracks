// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.tracks.device.FindPhone
import kotlin.math.ceil

/**
 * Makes the phone findable when the watch asks.
 *
 * ## Why there is no screen
 *
 * Because a screen would be the wrong answer to the question being asked. The
 * phone is under a sofa cushion or in yesterday's coat; nobody is going to look
 * at it, and by the time they can, they have already found it. Everything this
 * feature needs to do is audible.
 *
 * That decision is also what makes it work at all. Showing something would mean
 * a full-screen intent, which on Android 10 and up needs a high-priority
 * notification channel and a companion-device association, and which the system
 * demotes to a heads-up banner whenever it feels like it — Gadgetbridge's own
 * implementation carries a warning notification for the case where the phone
 * refuses to launch the screen. Sound has no such gatekeeping: any process that
 * is alive may play it.
 *
 * ## Why it survives the app being closed
 *
 * It runs wherever the watch connection runs. `WatchManager` attaches this as a
 * feed, and the connection is held by [com.tracks.app.WatchLinkService] — a
 * foreground service — so the path from "button pressed on the watch" to "noise"
 * does not pass through an Activity at any point. A phone you have lost is a
 * phone whose app was swapped out of memory hours ago; anything that needed the
 * UI alive would work only in the case where you already knew where it was.
 *
 * ## Why the alarm stream
 *
 * A phone is lost most often *because* it is silent. Ringtone and notification
 * volume are muted by the ringer switch and by Do Not Disturb; alarms are not,
 * which is the entire reason alarms are their own stream. Playing the user's
 * ringtone *through* the alarm usage keeps the sound they recognise as their
 * phone and the audibility of an alarm clock.
 */
class PhoneFinder(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    /** The alarm volume before this turned it up, so it can be put back. */
    private var previousVolume: Int? = null

    /** Registered only while ringing — see [foundReceiver]. */
    private var receiver: BroadcastReceiver? = null

    private val stopSoon = Runnable {
        Log.i(TAG, "find-my-phone timed out")
        stop()
    }

    /** Whether the phone is currently making itself known. */
    @get:Synchronized
    var ringing: Boolean = false
        private set

    /** Act on what the watch asked for. */
    @Synchronized
    fun handle(request: FindPhone) {
        when (request) {
            FindPhone.RING -> start(sound = true)
            FindPhone.VIBRATE -> start(sound = false)
            FindPhone.STOP -> stop()
        }
    }

    @Synchronized
    fun start(sound: Boolean) {
        // Restart rather than stack. Pressing the watch button twice should not
        // leave two ringtones playing over each other with only one of them
        // reachable by stop().
        if (ringing) stop()
        Log.i(TAG, "find-my-phone: ${if (sound) "ringing" else "vibrating"}")
        ringing = true

        if (sound) {
            takeAudioFocus()
            raiseVolume()
            playRingtone()
        }
        buzz()
        listenForBeingFound()

        // The backstop that matters. The watch sends a cancel when somebody
        // presses stop on it, but a watch that has walked out of range cannot
        // send anything, and a phone that rings until its battery dies is worse
        // than a phone that was never found.
        handler.postDelayed(stopSoon, MAX_RING_MS)
    }

    @Synchronized
    fun stop() {
        if (!ringing) return
        Log.i(TAG, "find-my-phone: stopping")
        ringing = false
        handler.removeCallbacks(stopSoon)

        runCatching {
            player?.stop()
            player?.release()
        }.onFailure { Log.w(TAG, "could not stop the ringtone", it) }
        player = null

        runCatching { vibrator?.cancel() }
        restoreVolume()
        releaseAudioFocus()
        unregisterReceiver()
    }

    private fun playRingtone() {
        val uri = ringtoneUri() ?: run {
            Log.w(TAG, "no ringtone or alarm sound on this phone — vibrating only")
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(ALARM_ATTRIBUTES)
                setDataSource(appContext, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "could not play $uri", it)
            player = null
        }
    }

    /**
     * The sound to make.
     *
     * The user's ringtone first, because the point is for them to recognise it
     * as *their phone* from another room. "Silent" is a legitimate choice of
     * ringtone and gives a null here, in which case the alarm sound is right:
     * they asked for a silent phone, and then asked for this phone to make a
     * noise, and the second request is the more recent one.
     */
    private fun ringtoneUri(): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM)
            ?: runCatching {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }.getOrNull()

    private fun buzz() {
        val device = vibrator ?: return
        runCatching {
            device.vibrate(
                VibrationEffect.createWaveform(BUZZ_PATTERN, BUZZ_REPEAT_FROM),
                ALARM_ATTRIBUTES,
            )
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    /**
     * Turn the alarm stream up, but only out of the range where it cannot be
     * heard, and only for as long as this lasts.
     *
     * A phone whose alarm volume is at ten percent will happily play a ringtone
     * nobody in the next room can hear, which fails the one job. Overriding a
     * deliberately quiet setting is rude, so this does not go to maximum and it
     * does not touch a volume that was already loud enough — see [audibleVolume].
     */
    private fun raiseVolume() {
        val manager = audio ?: return
        runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            val wanted = audibleVolume(current, max) ?: return
            previousVolume = current
            manager.setStreamVolume(AudioManager.STREAM_ALARM, wanted, 0)
        }.onFailure {
            // Do Not Disturb can refuse this outright without notification
            // policy access, which Tracks does not ask for. Ringing quietly is
            // a better answer than not ringing.
            Log.i(TAG, "could not raise the alarm volume: ${it.message}")
        }
    }

    private fun restoreVolume() {
        val manager = audio
        val previous = previousVolume ?: return
        previousVolume = null
        runCatching { manager?.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
            .onFailure { Log.w(TAG, "could not put the alarm volume back", it) }
    }

    /**
     * Ask for exclusive focus, so whatever was playing pauses rather than
     * competing. Music through the same speaker is the one thing that can make
     * a ringtone harder to locate.
     */
    private fun takeAudioFocus() {
        val manager = audio ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(ALARM_ATTRIBUTES)
            .build()
        focus = request
        runCatching { manager.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "could not take audio focus", it) }
    }

    private fun releaseAudioFocus() {
        val manager = audio
        val request = focus ?: return
        focus = null
        runCatching { manager?.abandonAudioFocusRequest(request) }
    }

    /**
     * Stop as soon as the phone is unmistakably in someone's hands.
     *
     * `ACTION_USER_PRESENT` fires on unlock, including a swipe on a phone with
     * no passcode — you cannot unlock a phone you have not found. Deliberately
     * *not* `ACTION_SCREEN_ON`: an ambient display waking on movement would
     * silence the ringing exactly as somebody starts rummaging for it.
     *
     * The watch's own cancel is still the main way this ends. This is for the
     * case where the phone turns up first.
     */
    private fun listenForBeingFound() {
        unregisterReceiver()
        val found = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "find-my-phone: unlocked, so it has been found")
                stop()
            }
        }
        receiver = found
        runCatching {
            // NOT_EXPORTED, through the compat helper: from Android 14 a
            // context-registered receiver must declare which it is, and nothing
            // outside this app has any business telling Tracks the phone has
            // been found. The system's own broadcast still arrives — unlocking
            // is a protected broadcast, sent by the platform.
            ContextCompat.registerReceiver(
                appContext,
                found,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            Log.w(TAG, "could not watch for the phone being picked up", it)
            receiver = null
        }
    }

    private fun unregisterReceiver() {
        val registered = receiver ?: return
        receiver = null
        runCatching { appContext.unregisterReceiver(registered) }
    }

    private companion object {
        const val TAG = "TracksFindPhone"

        /**
         * Alarm usage, sonification content: the combination that survives the
         * ringer being off and tells the system this is not media.
         */
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * Long enough to search a room, short enough that a watch which walked
         * out of range without cancelling does not empty the battery. Garmin
         * asks for about a minute itself.
         */
        const val MAX_RING_MS = 60_000L

        /** Off, buzz, off, buzz — an unmistakably deliberate rhythm. */
        val BUZZ_PATTERN = longArrayOf(0, 700, 500)
        const val BUZZ_REPEAT_FROM = 0
    }
}

/**
 * The alarm volume to switch to while hunting for the phone, or null to leave
 * it alone.
 *
 * Split out as a plain function because it is the one judgement call in the
 * feature: too timid and the phone cannot be heard through a cushion, too
 * aggressive and Tracks is an app that turns your volume up. The rule is that
 * it only ever raises, only into the range where a sound carries, and never to
 * maximum — a phone found is a phone about to be held next to somebody's ear.
 */
internal fun audibleVolume(current: Int, max: Int): Int? {
    if (max <= 0) return null
    val floor = ceil(max * AUDIBLE_FRACTION).toInt().coerceIn(1, max)
    return if (current >= floor) null else floor
}

/** Three fifths of the way up: loud, not startling. */
private const val AUDIBLE_FRACTION = 0.6
