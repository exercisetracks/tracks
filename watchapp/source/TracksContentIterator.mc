// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Math;
using Toybox.Media;

//! Walks the downloaded songs for the media player.
//!
//! The player asks for `Media.Content` objects, while what this app persists is
//! the much smaller ContentRef id. `Media.getCachedContentObj` is the bridge:
//! it looks up the real content — including the ID3 tags Navidrome wrote when
//! it transcoded, which is where the player gets titles and artists from.
//!
//! The player does not just walk this iterator; it also *reads its state* to
//! draw the controls. `shuffling()` and `repeatMode()` are what light the
//! shuffle and repeat icons and animate them as they change — without them the
//! player cannot tell which state a toggle left things in, and the whole
//! control ring reads as dead. So the two toggles keep their state here (and,
//! through TracksLibrary, across a relaunch) and report it back on demand.
//!
//! Repeat is the media player's own three-state mode:
//!   OFF   play to the end and stop
//!   ALL   at the end, wrap to the start
//!   ONE   the player replays the current song; skip still moves
//! The iterator wraps for ALL so a loop keeps going on its own; for ONE it
//! walks straight (a manual skip should still skip), and the player is what
//! replays the song on completion, guided by repeatMode().
class TracksContentIterator extends Media.ContentIterator {

    private var _refs;          // media-store ids, in the order being played
    private var _at;
    private var _shuffle;       // whether the order was shuffled
    private var _repeatMode;    // Media.REPEAT_MODE_*
    private var _shuffleButton; // the control-menu toggles, made once
    private var _repeatButton;
    private var _libraryButton;

    function initialize(refs) {
        ContentIterator.initialize();
        _refs = refs;
        _at = 0;
        _shuffle = TracksLibrary.shuffle();
        _repeatMode = TracksLibrary.repeatMode();
        _shuffleButton = new ShuffleButton();
        _repeatButton = new RepeatButton();
        _libraryButton = new LibraryButton();
        if (_shuffle) {
            reorderShuffled();
        }
    }

    //! Resolve the entry at [index], or null when it is out of range or the
    //! audio has gone missing from the media store underneath us.
    private function contentAt(index) {
        if (index < 0 || index >= _refs.size()) {
            return null;
        }
        var ref = new Media.ContentRef(_refs[index], Media.CONTENT_TYPE_AUDIO);
        try {
            return Media.getCachedContentObj(ref);
        } catch (ex) {
            // The index and the media store disagree — the store wins. Skipping
            // is better than stalling the player on a track that is not there.
            return null;
        }
    }

    private function wraps() {
        return _repeatMode == Media.REPEAT_MODE_ALL;
    }

    function get() {
        return contentAt(_at);
    }

    function next() {
        if (_at + 1 >= _refs.size()) {
            if (!wraps() || _refs.size() == 0) {
                return null;
            }
            _at = 0;
            return get();
        }
        _at++;
        return get();
    }

    function previous() {
        if (_at - 1 < 0) {
            if (!wraps() || _refs.size() == 0) {
                return null;
            }
            _at = _refs.size() - 1;
            return get();
        }
        _at--;
        return get();
    }

    function peekNext() {
        if (_at + 1 >= _refs.size()) {
            return wraps() ? contentAt(0) : null;
        }
        return contentAt(_at + 1);
    }

    function peekPrevious() {
        if (_at - 1 < 0) {
            return wraps() ? contentAt(_refs.size() - 1) : null;
        }
        return contentAt(_at - 1);
    }

    //! Whether playback is set to shuffle — read by the player to light the
    //! shuffle icon.
    function shuffling() {
        return _shuffle;
    }

    //! The current repeat mode — read by the player to light the repeat icon,
    //! and to decide what to do at the end of a song and of the list.
    function repeatMode() {
        return _repeatMode;
    }

    //! Turn shuffle on or off, reordering what is still to come.
    function setShuffle(on) {
        if (_shuffle == on) {
            return;
        }
        _shuffle = on;
        reorderShuffled();
    }

    function setRepeatMode(mode) {
        _repeatMode = mode;
    }

    //! Shuffle what is still to come, keeping the current song in place; or put
    //! the songs back in playlist order, again keeping the current one under
    //! the cursor. Called whenever _shuffle flips.
    private function reorderShuffled() {
        if (_refs.size() < 2) {
            return;
        }
        var current = _refs[_at];
        if (_shuffle) {
            var rest = [];
            for (var i = 0; i < _refs.size(); i++) {
                if (i != _at) {
                    rest.add(_refs[i]);
                }
            }
            // Fisher–Yates.
            for (var i = rest.size() - 1; i > 0; i--) {
                var j = Math.rand() % (i + 1);
                var tmp = rest[i];
                rest[i] = rest[j];
                rest[j] = tmp;
            }
            _refs = [current];
            _refs.addAll(rest);
            _at = 0;
        } else {
            // Back to the order the playlists have them in.
            //
            // `have` exists to keep this linear. Testing membership with
            // _refs.indexOf() inside the loop made it quadratic, which on a
            // few hundred songs is tens of thousands of comparisons on a watch
            // CPU every time shuffle is switched off.
            var ordered = [];
            var playlists = TracksLibrary.selected();
            var seen = {};
            var have = {};
            for (var i = 0; i < _refs.size(); i++) {
                have[_refs[i]] = true;
            }
            for (var p = 0; p < playlists.size(); p++) {
                var refs = TracksLibrary.refsFor(TracksLibrary.songsOf(playlists[p]));
                for (var i = 0; i < refs.size(); i++) {
                    if (have.hasKey(refs[i]) && !seen.hasKey(refs[i])) {
                        seen[refs[i]] = true;
                        ordered.add(refs[i]);
                    }
                }
            }
            for (var i = 0; i < _refs.size(); i++) {
                if (!seen.hasKey(_refs[i])) {
                    ordered.add(_refs[i]);
                }
            }
            _refs = ordered;
            _at = _refs.indexOf(current);
            if (_at < 0) {
                _at = 0;
            }
        }
    }

    //! The user's own files: nothing to restrict.
    function canSkip() {
        return true;
    }

    //! The controls the native player offers, in the order the fēnix 6 manual
    //! lists them. VOLUME and SOURCE the system adds and drives itself; SHUFFLE
    //! and REPEAT it hands back to this app (onShuffle/onRepeat), reading the
    //! resulting state from shuffling()/repeatMode(); the rest it drives
    //! through this iterator. This is the user's own music on their own server,
    //! so every control the player can show is on.
    function getPlaybackProfile() {
        var profile = new Media.PlaybackProfile();
        // No PLAYBACK here: play/pause is already the now-playing screen's
        // own button, and a second one in the menu is a row that costs a
        // scroll to reach what is under the thumb anyway.
        // NEXT first, deliberately: "the first entry in the array may be used
        // as a hotkey in the media player" (PlaybackProfile docs), and on the
        // fēnix that hotkey is the DOWN button. Skip-song is the one control
        // worth reaching without opening a menu, mid-run, with gloves on.
        profile.playbackControls = [
            Media.PLAYBACK_CONTROL_NEXT,
            Media.PLAYBACK_CONTROL_PREVIOUS,
            _libraryButton,
            // Shuffle and repeat are CustomButtons, not PLAYBACK_CONTROL_*.
            // The built-in controls put each option behind its own screen and
            // cost two back presses to get out of; a CustomButton is handled
            // in onCustomButton with no sub-screen at all. See TracksButtons.
            _shuffleButton,
            _repeatButton,
            Media.PLAYBACK_CONTROL_VOLUME,
            Media.PLAYBACK_CONTROL_SOURCE
        ];
        // Song-start events are how the cover art goes up — see
        // TracksContentDelegate.onSong.
        profile.requirePlaybackNotification = true;
        return profile;
    }
}
