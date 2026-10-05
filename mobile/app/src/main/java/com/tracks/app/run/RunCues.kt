// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Telling the runner things without them having to look.
 *
 * A phone in an armband, in a pocket, or in a hand at the bottom of a swinging
 * arm is a phone nobody is reading. Every number this app computes during a run
 * is worthless unless it can arrive some other way, so each kilometre is spoken
 * and buzzed.
 *
 * ## Both, not either
 *
 * The buzz is not a fallback for the speech, it is the half that works. Speech
 * loses to wind, to traffic, and to anyone running with music in; a vibration
 * against the body is felt through all three. What the buzz cannot carry is the
 * split time, which is what the speech is for. Together they say "a kilometre
 * has happened" reliably and "it took four minutes ten" when conditions allow.
 *
 * ## Failing quietly is correct here
 *
 * A device with no TTS engine installed, or no voice for the locale, or no
 * vibrator, is a device where the run still records perfectly. Every call here
 * is best-effort by design — the alternative is a run that refuses to start
 * because the phone cannot talk.
 */
class RunCues(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    // Whatever the phone is set to. A split time read out in the
                    // wrong language is worse than one not read out at all.
                    runCatching { tts?.language = Locale.getDefault() }
                }
            }
        }
    }

    fun announceStart() {
        buzz(START_MS)
        say("Run started.")
    }

    fun announcePause() {
        buzz(SHORT_MS)
        say("Paused.")
    }

    fun announceResume() {
        buzz(SHORT_MS)
        say("Resumed.")
    }

    /** [at] is elapsed moving time at the kilometre mark. */
    fun announceSplit(km: Int, at: Long) {
        buzz(SPLIT_MS)
        say("Kilometre $km. ${spokenClock(at)}.")
    }

    /**
     * The next step of a guided workout.
     *
     * Spoken and buzzed like a split, because it is the same problem: the phone
     * is in a pocket and the interval has just changed. The buzz alone says
     * "something changed, look if you can"; the speech says what to do, for
     * whoever can hear it.
     */
    fun announceStep(text: String) {
        buzz(SPLIT_MS)
        say(text)
    }

    /** A buzz with nothing said — a rest that has ended, for a lifter across the room. */
    fun nudge() = buzz(SPLIT_MS)

    /** Just the words — for a caller that has already buzzed or means not to. */
    fun speak(text: String) = say(text)

    fun announceFinish(distanceM: Double, movingMs: Long) {
        buzz(FINISH_MS)
        val km = distanceM / 1000.0
        say("Run finished. ${"%.2f".format(km)} kilometres in ${spokenClock(movingMs)}.")
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }

    private fun say(text: String) {
        if (!ready) return
        // QUEUE_ADD, not FLUSH: two cues can land within a second of each other
        // at the end of a run, and flushing would drop the first one.
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text) }
    }

    private fun buzz(millis: Long) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(millis)
            }
        }
    }

    private companion object {
        const val SHORT_MS = 120L
        const val START_MS = 250L
        const val SPLIT_MS = 400L
        const val FINISH_MS = 600L
    }
}

/**
 * A duration as it should be *said*: "four minutes ten", not "4:10".
 *
 * A TTS engine reading "4:10" says "four ten" at best and "four colon one zero"
 * at worst, and neither is a time.
 */
internal fun spokenClock(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) append("$hours hour${if (hours == 1L) "" else "s"} ")
        if (minutes > 0) append("$minutes minute${if (minutes == 1L) "" else "s"} ")
        if (seconds > 0 || (hours == 0L && minutes == 0L)) {
            append("$seconds second${if (seconds == 1L) "" else "s"}")
        }
    }.trim()
}
