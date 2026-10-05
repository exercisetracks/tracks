// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Media;
using Toybox.WatchUi;

//! The shuffle and repeat toggles in the player's control menu.
//!
//! These exist as CustomButtons rather than PLAYBACK_CONTROL_SHUFFLE and
//! PLAYBACK_CONTROL_REPEAT for one reason: how the player treats a press.
//! Naming the built-in controls put every option behind its own screen — pick
//! the option, land on a second list, toggle there, then press back twice to
//! get to the song. A CustomButton does not do that. The documented behaviour
//! is that pressing one calls ContentDelegate.onCustomButton() directly,
//! with no sub-screen, so the toggle happens under the cursor and the menu
//! stays where it was. That is how the watch's own player behaves, and the
//! only way found to match it.
//!
//! The player asks each button for its state, then for the text and the icon
//! to draw for it, so the label carries the value — "Shuffle on", "Repeat
//! all" — and the icon changes with it. The icons are this app's own: null
//! from getImage draws nothing at all rather than falling back to the
//! system's artwork, which is how these came out blank the first time.

//! Shuffle: a plain on/off toggle, stored so it survives a relaunch.
class ShuffleButton extends Media.CustomButton {

    function initialize() {
        CustomButton.initialize();
    }

    function getState() {
        return TracksLibrary.shuffle() ? Media.BUTTON_STATE_ON : Media.BUTTON_STATE_OFF;
    }

    //! Reads the store rather than the state handed in: the player passes a
    //! state that does not track this toggle, so trusting it left the label
    //! stuck on "Shuffle off" while the icon beside it flipped correctly.
    function getText(state) {
        return WatchUi.loadResource(
            TracksLibrary.shuffle() ? Rez.Strings.ShuffleOn : Rez.Strings.ShuffleOff);
    }

    function getImage(image, highlighted) {
        if (TracksLibrary.shuffle()) {
            return WatchUi.loadResource(
                highlighted ? Rez.Drawables.IconShuffleOnHi : Rez.Drawables.IconShuffleOn);
        }
        return WatchUi.loadResource(
            highlighted ? Rez.Drawables.IconShuffleOffHi : Rez.Drawables.IconShuffleOff);
    }
}

//! Repeat: three modes, so "on" means anything but off and the label says
//! which. BUTTON_STATE has no third value, and the text is where the
//! difference belongs anyway.
class RepeatButton extends Media.CustomButton {

    function initialize() {
        CustomButton.initialize();
    }

    function getState() {
        return TracksLibrary.repeatMode() == Media.REPEAT_MODE_OFF
            ? Media.BUTTON_STATE_OFF
            : Media.BUTTON_STATE_ON;
    }

    function getText(state) {
        var mode = TracksLibrary.repeatMode();
        var id = Rez.Strings.RepeatOff;
        if (mode == Media.REPEAT_MODE_ALL) {
            id = Rez.Strings.RepeatAll;
        } else if (mode == Media.REPEAT_MODE_ONE) {
            id = Rez.Strings.RepeatOne;
        }
        return WatchUi.loadResource(id);
    }

    //! Three modes, three drawings. Off used to share the loop with All, so
    //! the menu showed the same icon whichever of the two it was in; off now
    //! has a slash through the loop.
    function getImage(image, highlighted) {
        var mode = TracksLibrary.repeatMode();
        if (mode == Media.REPEAT_MODE_ONE) {
            return WatchUi.loadResource(
                highlighted ? Rez.Drawables.IconRepeatOneHi : Rez.Drawables.IconRepeatOne);
        }
        if (mode == Media.REPEAT_MODE_OFF) {
            return WatchUi.loadResource(
                highlighted ? Rez.Drawables.IconRepeatOffHi : Rez.Drawables.IconRepeatOff);
        }
        return WatchUi.loadResource(
            highlighted ? Rez.Drawables.IconRepeatHi : Rez.Drawables.IconRepeat);
    }
}

//! Library: the player's own control, wearing an icon.
//!
//! A SystemButton is the built-in action with its artwork overridden — the
//! behaviour stays the player's (it opens the library), only the drawing is
//! ours. Needed because PLAYBACK_CONTROL_LIBRARY named on its own came out
//! blank, the same as the toggles did. Note the three-argument getImage: a
//! SystemButton is handed the state as well, unlike a CustomButton.
class LibraryButton extends Media.SystemButton {

    function initialize() {
        SystemButton.initialize(Media.PLAYBACK_CONTROL_LIBRARY, null);
    }

    function getImage(image, state, highlighted) {
        return WatchUi.loadResource(
            highlighted ? Rez.Drawables.IconLibraryHi : Rez.Drawables.IconLibrary);
    }
}
