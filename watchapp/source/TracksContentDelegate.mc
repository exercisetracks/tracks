// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Media;

//! Hands the player an iterator over what this watch has downloaded, and
//! answers the player's events.
//!
//! Scoped by whatever `Media.startPlayback` was given. That argument is how a
//! provider says *which* of its content to play, so a playlist id here is the
//! API being used as intended rather than a second mechanism bolted beside it:
//! null plays everything, a playlist id plays that playlist in its own order.
//!
//! Three kinds of event matter. A song starting is when its cover art goes up
//! — the player only knows what is inside the file, and Navidrome's transcode
//! has no art inside it, so the app supplies the copy it fetched at sync time.
//!
//! Shuffle and repeat are commands, not switches: the player asks this app to
//! *change* them and reads the result back off the iterator (shuffling(),
//! repeatMode()). So each toggle updates the persisted state, applies it to
//! the live iterator, and calls requestPlaybackProfileUpdate() to make the
//! player re-read and redraw — without that last nudge the icon can lag a
//! press behind, which reads as the button doing nothing.
//!
//! The play-reporting callbacks are left off. Commercial providers use them to
//! tell a licensor what played; this is the user's own server.
class TracksContentDelegate extends Media.ContentDelegate {

    private var _playlistId;
    private var _iterator;

    function initialize(playlistId) {
        ContentDelegate.initialize();
        _playlistId = playlistId;
        _iterator = null;
    }

    function getContentIterator() {
        var refs = null;
        if (_playlistId != null) {
            refs = TracksLibrary.refsFor(TracksLibrary.songsOf(_playlistId));
        }
        // Fall back to everything rather than to nothing: a playlist that has
        // gone empty should not leave the user staring at a player with no
        // songs in it.
        if (refs == null || refs.size() == 0) {
            refs = TracksLibrary.allRefs();
        }
        // Never null, even for an empty library. The signature allows it, but
        // the player crashes on it — "Failed invoking <symbol>" with no app
        // frames, confirmed in the simulator. The documented way to say
        // "nothing to play" is an iterator whose get() returns null, and an
        // iterator over an empty list does exactly that.
        _iterator = new TracksContentIterator(refs);
        return _iterator;
    }

    function resetContentIterator() {
        return getContentIterator();
    }

    function onSong(contentRefId, songEvent, playbackPosition) {
        if (songEvent == Media.SONG_EVENT_START) {
            // Remembered so a sync never deletes the song under the player.
            TracksLibrary.setPlaying(contentRefId);
            var songId = TracksLibrary.songFor(contentRefId);
            var albumId = (songId == null) ? null : TracksLibrary.albumOf(songId);
            // Null falls back to the system's default art rather than leaving
            // the previous song's cover up.
            Media.setAlbumArt(TracksLibrary.artFor(albumId));
        } else if (songEvent == Media.SONG_EVENT_STOP || songEvent == Media.SONG_EVENT_COMPLETE) {
            TracksLibrary.setPlaying(null);
        }
    }

    //! A control-menu toggle was pressed.
    //!
    //! This is what a CustomButton press arrives as, and why shuffle and
    //! repeat are CustomButtons: the player calls straight into here, with no
    //! screen of its own in between, so the value flips under the cursor and
    //! the menu stays put. Asking for a profile update afterwards is what
    //! makes the label redraw with the new state.
    function onCustomButton(button) {
        if (button instanceof ShuffleButton) {
            toggleShuffle();
        } else if (button instanceof RepeatButton) {
            cycleRepeat();
        }
    }

    //! Kept for the built-in shuffle control, which the player still offers on
    //! devices that do not take a CustomButton in its place.
    function onShuffle() {
        toggleShuffle();
    }

    private function toggleShuffle() {
        var on = !TracksLibrary.shuffle();
        TracksLibrary.setShuffle(on);
        if (_iterator != null) {
            _iterator.setShuffle(on);
        }
        Media.requestPlaybackProfileUpdate();
    }

    //! Cycle the repeat mode the way a music player does: off → all → one →
    //! off. The player reads the new mode back through repeatMode().
    function onRepeat() {
        cycleRepeat();
    }

    private function cycleRepeat() {
        var mode = nextRepeatMode(TracksLibrary.repeatMode());
        TracksLibrary.setRepeatMode(mode);
        if (_iterator != null) {
            _iterator.setRepeatMode(mode);
        }
        Media.requestPlaybackProfileUpdate();
    }

    private function nextRepeatMode(mode) {
        if (mode == Media.REPEAT_MODE_OFF) {
            return Media.REPEAT_MODE_ALL;
        }
        if (mode == Media.REPEAT_MODE_ALL) {
            return Media.REPEAT_MODE_ONE;
        }
        return Media.REPEAT_MODE_OFF;
    }
}
