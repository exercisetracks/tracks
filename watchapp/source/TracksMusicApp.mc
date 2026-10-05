// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Application;
using Toybox.Application.Storage;
using Toybox.Communications;

//! Tracks Music — an audio content provider for the user's own music server.
//!
//! An AudioContentProviderApp is not launched like a normal app. The system
//! media player owns it and calls in at four moments, which is why this class
//! is a set of factory methods:
//!
//!   getSyncConfigurationView()      choose what to download
//!   getSyncDelegate()               download it (over Wi-Fi)
//!   getPlaybackConfigurationView()  choose what to play
//!   getContentDelegate()            play it
//!
//! Configuration comes over Bluetooth from the Tracks phone app (see
//! TracksPairing), asked for on every open and before every sync — or is
//! typed on the watch (AccountMenu). The music itself the watch fetches from
//! the server on its own: neither the phone nor a Tracks server is needed for
//! a sync, and the Connect IQ Store and Garmin's servers are never involved
//! at all.
class TracksMusicApp extends Application.AudioContentProviderApp {

    function initialize() {
        AudioContentProviderApp.initialize();
    }

    function onStart(state) {
        // Simulator only. monkeydo launches an audio provider straight into
        // playback, and the sync menu is only reachable through the GUI — so a
        // dev build applies its baked-in selection and kicks off one sync
        // itself, once per build. Compiled out otherwise.
        if (TracksConfig.devProbe()) {
            return;
        }
        var generation = TracksConfig.devGeneration();
        if (generation != null && !generation.equals(Storage.getValue("dev:generation"))) {
            Storage.setValue("dev:generation", generation);
            if (TracksConfig.devPlay() != null) {
                // Playback instead: see getContentDelegate.
                return;
            }
            TracksLibrary.setSelected(TracksConfig.devPlaylists());
            // As if pressed: otherwise isSyncNeeded's once-an-hour retry rule
            // swallows the sync of any build made within the hour of the last.
            TracksLibrary.setSyncRequested(true);
            Communications.startSync();
        }
    }

    function onStop(state) {
    }

    function getSyncConfigurationView() {
        // The view asks the phone itself, and waits for the answer: it is
        // about to list the server's playlists and needs the right account.
        var view = new ConfigureSyncView();
        return [view, new ConfigureSyncDelegate(view)];
    }

    //! The screen the app opens on from Music Providers, so this is "every
    //! launch": ask the phone, without waiting — playing what is already
    //! downloaded does not depend on the answer.
    function getPlaybackConfigurationView() {
        TracksPairing.pull(null);
        var view = new PlaybackView();
        return [view, new PlaybackDelegate(view)];
    }

    //! The sync asks the phone itself before it starts; see onStartSync.
    function getSyncDelegate() {
        return new TracksSyncDelegate();
    }

    //! `args` is whatever Media.startPlayback was given — a playlist id, or
    //! null for everything. See TracksContentDelegate.
    //!
    //! No pull from the phone here, unlike the other ways in. This is the
    //! player starting, and an answer that switched accounts would delete the
    //! songs it is about to play; the playback screen that led here has
    //! already asked.
    function getContentDelegate(args) {
        // Simulator only: monkeydo launches playback with no argument, and
        // the menu that would supply one needs the GUI. A dev build can name
        // the playlist at build time. Compiled out otherwise.
        var play = TracksConfig.devPlay();
        if (args == null && play != null && play.length() > 0) {
            args = play;
        }
        return new TracksContentDelegate(args);
    }
}
