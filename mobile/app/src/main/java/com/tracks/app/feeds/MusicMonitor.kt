// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.tracks.device.MusicCommand
import com.tracks.device.MusicState
import com.tracks.device.MusicTrack

/**
 * Watches whatever is playing, and lets the watch control it.
 *
 * ## Why this rides on the notification listener
 *
 * `MediaSessionManager.getActiveSessions` requires a notification-listener
 * component to prove the caller is allowed to observe media. That is the only
 * way to do this without a privileged permission, and it is why music control
 * is gated behind the same optional grant as notifications — one permission
 * buys both, and neither is required for the app to work.
 *
 * ## Why the active session is chosen this way
 *
 * A phone routinely has several media sessions alive at once: a paused podcast
 * app, a browser tab that played a video an hour ago, and the thing actually
 * making sound. Taking the first would show a watch the wrong track. The
 * playing session wins; failing that, the most recently active one, which is
 * what the system itself ranks first.
 */
class MusicMonitor(private val context: Context) {

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val listenerComponent =
        ComponentName(context, TracksNotificationListener::class.java)

    /** The session worth showing, or null when nothing is playing. */
    private fun activeController(): MediaController? {
        val controllers = try {
            sessionManager?.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            // Notification access not granted. Expected, not exceptional.
            null
        } ?: return null

        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
    }

    fun currentTrack(): MusicTrack? {
        val metadata = activeController()?.metadata ?: return null
        return MusicTrack(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
        )
    }

    fun currentState(): MusicState? {
        val controller = activeController() ?: return null
        val playback = controller.playbackState
        val durationMs = controller.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        return MusicState(
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            position = ((playback?.position ?: 0L) / 1000).toInt(),
            // Live streams report -1 or 0; a watch progress bar wants neither.
            duration = (durationMs / 1000).toInt().coerceAtLeast(0),
            volumePercent = volumePercent(),
        )
    }

    /**
     * Apply a command the watch sent.
     *
     * Volume goes through [AudioManager] rather than the session, because
     * session volume is per-app and often not settable, while the user pressing
     * volume on their wrist means the same thing as pressing it on the phone.
     */
    fun handle(command: MusicCommand) {
        val controls = activeController()?.transportControls
        when (command) {
            MusicCommand.PLAY -> controls?.play()
            MusicCommand.PAUSE -> controls?.pause()
            MusicCommand.PLAY_PAUSE ->
                if (currentState()?.isPlaying == true) controls?.pause() else controls?.play()
            MusicCommand.NEXT -> controls?.skipToNext()
            MusicCommand.PREVIOUS -> controls?.skipToPrevious()
            MusicCommand.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            MusicCommand.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
        }
        Log.d(TAG, "music command: $command")
    }

    private fun audioManager() =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun adjustVolume(direction: Int) {
        audioManager()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    private fun volumePercent(): Int? {
        val am = audioManager() ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /**
     * Call [onChange] whenever what is playing changes.
     *
     * Event-driven rather than polled, and that is the whole reason this method
     * exists: a poll fast enough to feel live on a watch face would wake the CPU
     * several times a second forever, which is unacceptable on a device meant to
     * survive a week in the field. The system already knows when playback
     * changes and will tell us for free.
     *
     * Two subscriptions are needed and neither alone is enough — the session
     * listener fires when a *different* app takes over playback, the controller
     * callback fires when the *current* app changes track or pauses.
     *
     * Returns a handle to unsubscribe; leaking one keeps a controller callback
     * alive against a session the user has moved on from.
     */
    fun observe(onChange: () -> Unit): Subscription {
        val manager = sessionManager ?: return Subscription {}

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
            override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        }

        // The controller we are currently subscribed to. Held so it can be
        // unsubscribed when playback moves to a different app — registering
        // against the new one without releasing the old leaks a callback per
        // switch, and on a phone that is one per track for a podcast app.
        var watched: MediaController? = null

        fun rebind() {
            watched?.unregisterCallback(callback)
            watched = activeController()?.also { it.registerCallback(callback) }
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
            rebind()
            onChange()
        }

        // Volume is not part of a media session, so none of the callbacks above
        // fire when it moves — not when the watch asks for it and not when the
        // phone's own rocker is pressed. Without this the watch would raise the
        // volume, the phone would obey, and the watch would go on showing the
        // old level: indistinguishable, from the wrist, from nothing happening.
        //
        // Settings.System covers far more than volume, hence the comparison:
        // the alternative is re-sending the whole music state to the watch
        // every time the screen brightness drifts.
        var lastVolume = volumePercent()
        val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = volumePercent()
                if (now != lastVolume) {
                    lastVolume = now
                    onChange()
                }
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        return try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            rebind()
            Subscription {
                resolver.unregisterContentObserver(volumeObserver)
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
                watched?.unregisterCallback(callback)
                watched = null
            }
        } catch (e: SecurityException) {
            // Notification access not granted, so there is nothing to observe.
            // Expected, not exceptional — same as everywhere else in this class.
            // The volume observer still comes back off: it registered fine and
            // would otherwise outlive the subscription that owns it.
            Log.d(TAG, "no notification access; not observing media sessions")
            resolver.unregisterContentObserver(volumeObserver)
            Subscription {}
        }
    }

    fun interface Subscription {
        fun cancel()
    }

    private companion object {
        const val TAG = "TracksMusic"
    }
}
