// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.Media;
using Toybox.PersistedContent;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;
using Toybox.WatchUi;

//! Brings the watch up to date with the playlists the user ticked.
//!
//! Runs over the watch's own Wi-Fi with nothing else involved — not the phone,
//! not a Tracks server. The system starts it when the user asks, and again on
//! its own whenever the watch is charging on Wi-Fi and `isSyncNeeded` says
//! yes, which is how Garmin's own providers keep themselves current.
//!
//! The shape is a chain of callbacks, because that is the only shape Connect IQ
//! allows: one request in flight, each response issuing the next. It reads
//! top to bottom in the order things happen:
//!
//!   ask the phone           its music settings, applied if newer (TracksPairing)
//!   login                   one POST; every later request rides on its tokens
//!   playlists               what the server offers now
//!   per chosen playlist     its songs, paged (see NavidromeList)
//!   reconcile               what is missing, what is no longer wanted
//!   per missing song        stream it into the media store
//!   retry                   once, for songs that failed the first time
//!   per new album           its cover art, small, into storage
//!   delete                  what is no longer wanted
//!   notifySyncComplete      which also tells the system to drop Wi-Fi
//!
//! Forgetting that last call leaves the radio up and the user watching a
//! spinner, so every exit path funnels through `done` — and a watchdog ends
//! the sync anyway if a callback simply never arrives. An unfinished sync is
//! not a stalled download, it is a flat battery by morning.
//!
//! One download at a time, deliberately. An audio provider on a fēnix 6 has
//! 512 KB for code and data together and every in-flight download holds a
//! buffer; running them in parallel is how this app would run out of memory on
//! a long playlist rather than merely take a while.
class TracksSyncDelegate extends Communications.SyncDelegate {

    //! Consecutive download failures before the network is judged gone.
    const GIVE_UP_AFTER = 3;

    //! How long the sync may make no progress at all before it is judged dead.
    //!
    //! A sync that never finishes is not a stalled download, it is a radio held
    //! on until the battery is flat — an overnight drain, found the hard way. A
    //! callback that never arrives cannot be detected any other way, so this is
    //! the backstop: generous enough for one slow song on poor Wi-Fi, short
    //! enough that nothing can hold the radio up for hours.
    const STALL_MS = 120 * 1000;

    //! How long an automatic sync waits after a successful one. A music
    //! library is not a live feed; the charger is visited most nights.
    const DAILY = 24 * 60 * 60;

    //! How long it waits after a failed one. Short, because the usual cause is
    //! a network that was not up yet rather than anything that needs a day.
    const RETRY_AFTER = 60 * 60;

    private var _queue as Lang.Array<Lang.String>;         // playlist ids still to resolve
    private var _wanted as Lang.Dictionary<Lang.String, Lang.String>;    // { songId => albumId }
    private var _order as Lang.Array<Lang.String>;         // song ids, first-seen order = download order
    private var _toDownload as Lang.Array<Lang.String>;
    private var _retry as Lang.Array<Lang.String>;         // failed once; tried again at the end
    private var _retrying as Lang.Boolean;
    private var _art as Lang.Array<Lang.String>;           // album ids whose art is still to fetch
    private var _stale as Lang.Array;                      // ContentRefs to delete
    private var _total as Lang.Number;
    private var _done as Lang.Number;
    private var _skipped as Lang.Number;                   // playlists that could not be resolved
    private var _failed as Lang.Number;                    // songs that did not download, even on retry
    private var _streak as Lang.Number;                    // consecutive download failures
    private var _cancelled as Lang.Boolean;
    private var _playlists as Lang.Number;       // how many were queued, for the listing progress
    private var _byRequest as Lang.Boolean;      // the user pressed for this one
    private var _watchdog;                                 // Timer, or null once stopped
    private var _finished as Lang.Boolean;                 // notifySyncComplete has been called
    private var _candidate;                                // a typed account this sync is checking, or null
    private var _artStreak as Lang.Number;                 // consecutive cover-art failures
    private var _later;                                    // Timer that issues the next art request

    function initialize() {
        SyncDelegate.initialize();
        _queue = [];
        _wanted = {};
        _order = [];
        _toDownload = [];
        _retry = [];
        _retrying = false;
        _art = [];
        _stale = [];
        _total = 0;
        _done = 0;
        _skipped = 0;
        _failed = 0;
        _streak = 0;
        _cancelled = false;
        _playlists = 0;
        _byRequest = false;
        _watchdog = null;
        _finished = false;
        _candidate = null;
        _artStreak = 0;
        _later = null;
    }

    //! Asked by the system before an automatic sync — which, on the charger,
    //! it asks often.
    //!
    //! Once a day is the answer for a music library. Every yes here is the
    //! Wi-Fi radio brought up and the whole library re-listed, and playlists do
    //! not change by the hour; saying yes every time the watch touches a
    //! charger spends battery to discover that nothing moved.
    //!
    //! Two things overtake the daily rule. A sync the user actually asked for
    //! is always needed — otherwise "Sync now" would do nothing for the rest of
    //! the day. And a sync that *failed* is retried after an hour rather than
    //! tomorrow, because the usual reason is a network that was not there yet.
    function isSyncNeeded() {
        if (TracksConfig.candidate() != null) {
            // A typed account is waiting to be checked over Wi-Fi.
            return true;
        }
        if (!TracksConfig.isConfigured() || TracksLibrary.selected().size() == 0) {
            return false;
        }
        if (TracksLibrary.syncRequested()) {
            return true;
        }
        var now = Time.now().value();
        if (!elapsed(TracksLibrary.lastAttempt(), now, RETRY_AFTER)) {
            return false;
        }
        return elapsed(TracksLibrary.lastSync(), now, DAILY);
    }

    //! True when `since` is unset, or `seconds` have passed since it. A clock
    //! that has gone backwards (a time zone, a reset) also reads as elapsed —
    //! better an extra sync than one that never runs again.
    private function elapsed(since, now, seconds) {
        if (since == null) {
            return true;
        }
        var age = now - since;
        return age < 0 || age >= seconds;
    }

    function onStartSync() {
        // Whatever asked for this sync has been served; the next automatic one
        // goes back to the daily rule. Remember which it was, because only a
        // sync the user pressed for should end up playing anything.
        _byRequest = TracksLibrary.syncRequested();
        TracksLibrary.setSyncRequested(false);
        pet();
        // Something visible immediately: login and listing happen before a
        // single byte of audio, and on a big library that is long enough for a
        // sync sitting at 0% to look stuck.
        Communications.notifySyncProgress(1);

        _candidate = TracksConfig.candidate();
        if (_candidate != null) {
            // This sync exists to check an account typed on the watch; see
            // onCandidate. Cleared now, so a check that dies half-way is not
            // retried on every charge for ever.
            TracksConfig.setCandidate(null);
            Navidrome.loginAs(_candidate, method(:onCandidate));
            return;
        }

        // Ask the phone first, so a change made there since the app last ran
        // is what this sync works from. Harmless without a phone — or when
        // the system routes this over Wi-Fi, where the .invalid host cannot
        // resolve — the request fails and the stored account stands.
        TracksPairing.fetch(method(:onPhoneConfig));
    }

    function onPhoneConfig(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator) as Void {
        pet();
        if (_cancelled) {
            return;
        }
        var outcome = (responseCode == 200) ? TracksPairing.apply(data, false) : :none;
        if (outcome == :switched || outcome == :signedOut) {
            // The old account's music has just been deleted. Stop here rather
            // than go on to download the new account's: on the simulator a
            // download issued straight after a batch of deletions dies inside
            // the system's callback dispatch (see deleteStale). Asked for
            // again, so the next chance to sync is taken.
            if (outcome == :switched) {
                TracksLibrary.setSyncRequested(true);
            }
            done(WatchUi.loadResource(outcome == :switched ? Rez.Strings.AccountChanged : Rez.Strings.NotSignedIn));
            return;
        }
        if (!TracksConfig.isConfigured()) {
            done(WatchUi.loadResource(Rez.Strings.ErrNoServer));
            return;
        }
        Navidrome.login(method(:onLogin));
    }

    //! The typed account's login, over Wi-Fi. Accepted: it becomes the
    //! account, and the sync ends there — a new account has nothing chosen
    //! yet, and the old account's music has just been deleted (see
    //! onPhoneConfig for why nothing is downloaded straight after that).
    //! Refused: the old account stands, untouched.
    function onCandidate(ok) as Void {
        pet();
        if (_cancelled) {
            return;
        }
        if (ok) {
            TracksPairing.adoptTyped(_candidate);
            done(WatchUi.loadResource(Rez.Strings.SignedIn));
            return;
        }
        done(WatchUi.loadResource(Navidrome.lastError == :auth ? Rez.Strings.ErrLoginRejected : Rez.Strings.ErrNetwork));
    }

    //! The user backed out. What already landed is kept — a half-finished sync
    //! that keeps its downloads is strictly better than one that discards them.
    function onStopSync() {
        _cancelled = true;
        if (_later != null) {
            _later.stop();
        }
        Communications.cancelAllRequests();
        done(null);
    }

    // ── staying alive, or not ────────────────────────────────────────────

    //! Restart the stall clock. Called wherever the sync makes progress.
    private function pet() {
        if (_finished) {
            return;
        }
        if (_watchdog == null) {
            _watchdog = new Timer.Timer();
        } else {
            _watchdog.stop();
        }
        _watchdog.start(method(:onStall), STALL_MS, false);
    }

    //! Nothing has moved for STALL_MS, so whatever the sync was waiting for is
    //! not coming. End it: an unfinished sync holds the Wi-Fi radio up, and the
    //! battery with it.
    function onStall() as Void {
        System.println("sync: stalled; ending it so the radio comes down");
        Communications.cancelAllRequests();
        abort(:network);
    }

    //! The one place the system is told the sync is over, and the only place
    //! that stops the watchdog. Every exit path goes through here, once.
    private function done(message) {
        if (_finished) {
            return;
        }
        _finished = true;
        if (_watchdog != null) {
            _watchdog.stop();
            _watchdog = null;
        }
        if (_later != null) {
            _later.stop();
            _later = null;
        }
        TracksLibrary.setLastAttempt(Time.now().value());
        Communications.notifySyncComplete(message);
    }

    // ── 1. login, then what the server offers ────────────────────────────

    function onLogin(ok) as Void {
        pet();
        if (_cancelled) {
            return;
        }
        if (!ok) {
            abort(Navidrome.lastError);
            return;
        }
        new NavidromeList(Navidrome.playlistsPath(), Navidrome.PLAYLIST_FIELDS, null, null).run(method(:onPlaylists));
    }

    function onPlaylists(rows) as Void {
        pet();
        if (_cancelled) {
            return;
        }
        if (rows == null) {
            abort(Navidrome.lastError);
            return;
        }

        var list = TracksLibrary.builtins();
        list.addAll(rows);
        TracksLibrary.savePlaylists(list);

        // The user's choices, restricted to what still exists and put in the
        // server's order. A playlist deleted on the server is dropped from the
        // selection here rather than left to fail on every sync forever.
        var chosen = TracksLibrary.selected();
        _queue = [];
        for (var i = 0; i < list.size(); i++) {
            var id = list[i][0];
            if (TracksLibrary.PICKED.equals(id)) {
                // Picked songs are their own list, kept on the watch; they are
                // wanted exactly when there are any.
                if (TracksLibrary.songsOf(id).size() > 0) {
                    _queue.add(id);
                }
            } else if (chosen.indexOf(id) >= 0) {
                _queue.add(id);
            } else {
                // Not chosen (any more): its song list must go, or playback
                // would keep offering a playlist the user un-ticked.
                TracksLibrary.forgetPlaylist(id);
            }
        }
        TracksLibrary.setSelected(_queue);
        _playlists = _queue.size();
        System.println("sync: " + list.size() + " playlists offered, " + _queue.size() + " chosen");

        resolveNext();
    }

    // ── 2. each chosen playlist's songs ──────────────────────────────────

    function resolveNext() {
        if (_cancelled) {
            return;
        }
        if (_queue.size() == 0) {
            reconcile();
            return;
        }
        // The listing phase gets the first tenth of the bar. Without it the
        // sync reads as stuck at 0% for as long as the playlists take, which
        // on an API paged four rows at a time is a while.
        if (_playlists > 0) {
            Communications.notifySyncProgress(1 + (9 * (_playlists - _queue.size())) / _playlists);
        }

        var id = _queue[0];
        var walker;
        if (TracksLibrary.PICKED.equals(id)) {
            // Chosen one by one, on the watch or the phone: the list is
            // already here, and the server has nothing to add to it.
            onSongs(TracksLibrary.songsOf(id));
            return;
        } else if (TracksLibrary.STARRED.equals(id)) {
            walker = new NavidromeList(Navidrome.starredPath(), Navidrome.SONG_FIELDS, null, null);
        } else if (TracksLibrary.RECENT.equals(id)) {
            // Sorted by last play, so the walk ends at the first song that was
            // never played: playCount is what says so.
            walker = new NavidromeList(Navidrome.recentPath(), Navidrome.SONG_FIELDS, Navidrome.RECENT_LIMIT, "playCount");
        } else {
            walker = new NavidromeList(Navidrome.playlistTracksPath(id), Navidrome.TRACK_FIELDS, null, null);
        }
        walker.run(method(:onSongs));
    }

    //! The playlist at the head of the queue resolved to these songs — as
    //! [songId, albumId] pairs — or to null when it could not be read.
    function onSongs(songs) as Void {
        pet();
        if (_cancelled) {
            return;
        }
        var id = _queue[0];
        _queue = _queue.slice(1, null);

        if (songs == null) {
            var why = Navidrome.lastError;
            if (why == :auth || why == :network) {
                // Every later request would fail the same way.
                abort(why);
                return;
            }
            // Server-side trouble with this one list: keep the last known
            // contents, skip it, carry on — the others are still worth having,
            // and the count is reported at the end.
            System.println("sync: skipping " + id + " (" + why + ")");
            _skipped++;
            resolveNext();
            return;
        }

        System.println("sync: " + id + " -> " + songs.size() + " songs");
        TracksLibrary.saveSongsOf(id, songs);
        for (var i = 0; i < songs.size(); i++) {
            var songId = songs[i][0];
            if (!_wanted.hasKey(songId)) {
                _wanted[songId] = songs[i][1];
                _order.add(songId);
            }
        }
        resolveNext();
    }

    // ── 3. the difference between wanted and held ────────────────────────

    private function reconcile() {
        // The media store is the truth about what is on the watch; the
        // r:<ref> keys say which song each item is.
        var present = {};
        _stale = [];
        var iter = TracksLibrary.contentRefs();
        var ref = (iter == null) ? null : iter.next();
        while (ref != null) {
            var songId = TracksLibrary.songFor(ref.getId());
            if (songId != null && _wanted.hasKey(songId)) {
                present[songId] = true;
            } else {
                _stale.add(ref);
            }
            ref = iter.next();
        }

        _toDownload = [];
        for (var i = 0; i < _order.size(); i++) {
            if (!present.hasKey(_order[i])) {
                _toDownload.add(_order[i]);
            }
        }
        _total = _toDownload.size();
        _done = 0;
        System.println("sync: " + present.size() + " held, " + _stale.size() + " to remove, " + _total + " to download");
        if (_total == 0) {
            beginArt();
            return;
        }
        // 10%: the listing is done and the audio is about to start. Not 0 —
        // the bar has already climbed through the listing and must not fall
        // back to the start just as the real work begins.
        Communications.notifySyncProgress(10);
        downloadNext();
    }

    // ── 4. downloads ─────────────────────────────────────────────────────

    private function downloadNext() {
        if (_cancelled) {
            return;
        }
        if (_toDownload.size() == 0) {
            if (_retry.size() > 0 && !_retrying) {
                // Second chance for what failed. The usual reason is a song
                // the server had to transcode from scratch and could not start
                // sending in time; by now it has the result cached and answers
                // at once. Once only — a song that fails twice is not going to
                // start working on the third try.
                System.println("sync: retrying " + _retry.size());
                _toDownload = _retry;
                _retry = [];
                _retrying = true;
            } else {
                beginArt();
                return;
            }
        }
        Communications.makeWebRequest(
            Navidrome.streamUrl(_toDownload[0]),
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                // These two make the system write the response into the
                // encrypted media store and hand back a ContentRef, instead of
                // trying to hold megabytes of audio as a response body.
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_AUDIO,
                :mediaEncoding => Media.ENCODING_MP3
            },
            method(:onSong)
        );
    }

    function onSong(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator) as Void {
        if (_cancelled) {
            return;
        }
        pet();
        var songId = _toDownload[0];
        _toDownload = _toDownload.slice(1, null);

        // A media download hands back a Media.ContentRef. The SDK types this
        // callback's `data` as a PersistedContent union — that module is for
        // courses and waypoints and never carries audio — so the declared type
        // is wrong here, and `has` asks the object itself.
        var content = data as Lang.Object;
        if (responseCode == 200 && content != null && content has :getId) {
            TracksLibrary.recordDownload(songId, _wanted[songId], content.getId());
            _done++;
            _streak = 0;
        } else if (responseCode == Communications.STORAGE_FULL) {
            System.println("sync: storage full at " + songId);
            abort(:storage);
            return;
        } else if (responseCode == 401 || responseCode == 403) {
            abort(:auth);
            return;
        } else {
            System.println("sync: " + songId + " failed (" + responseCode + ")");
            _streak++;
            if (_streak >= GIVE_UP_AFTER) {
                // Three in a row is not three bad songs, it is no network.
                // Stopping keeps what landed; grinding on would only produce
                // more of the same failure and drain the battery doing it.
                abort(:network);
                return;
            }
            if (_retrying) {
                _failed++;
            } else {
                _retry.add(songId);
            }
        }

        Communications.notifySyncProgress(10 + ((_done + _failed) * 90) / _total);
        downloadNext();
    }

    // ── 5. cover art ─────────────────────────────────────────────────────

    //! One small image per album that has a downloaded song and no art yet.
    //!
    //! Art is fetched here, at sync time, because playback has to work with
    //! the phone at home: the player asks for art the moment a song starts,
    //! and the only place it can come from then is storage. One per album
    //! rather than per song keeps storage bounded, and a failure is not worth
    //! reporting — the song plays either way, under the default art.
    private function beginArt() {
        var have = {};
        _art = [];
        for (var i = 0; i < _order.size(); i++) {
            var albumId = _wanted[_order[i]];
            if (albumId != null && !have.hasKey(albumId)
                && TracksLibrary.refFor(_order[i]) != null && !TracksLibrary.hasArt(albumId)) {
                have[albumId] = true;
                _art.add(albumId);
            }
        }
        System.println("sync: " + _art.size() + " covers to fetch");
        artNext();
    }

    //! Public, not private: it is also the deferral timer's callback.
    function artNext() as Void {
        if (_cancelled || _finished) {
            return;
        }
        if (_art.size() == 0) {
            finish();
            return;
        }
        Communications.makeImageRequest(
            Navidrome.coverArtUrl(_art[0]),
            null,
            // Scaled and dithered for this screen by the system, which knows
            // its palette better than this app does.
            { :maxWidth => Navidrome.ART_SIZE, :maxHeight => Navidrome.ART_SIZE },
            method(:onArt)
        );
    }

    function onArt(responseCode as Lang.Number, data as WatchUi.BitmapResource or Graphics.BitmapReference or Null) as Void {
        if (_cancelled || _finished) {
            return;
        }
        pet();
        var albumId = _art[0];
        _art = _art.slice(1, null);
        if (responseCode == 200 && data != null) {
            _artStreak = 0;
            try {
                TracksLibrary.saveArt(albumId, data);
            } catch (ex) {
                // Storage is full, or the image too big for one value. The
                // song plays without it.
                System.println("sync: could not keep art for " + albumId);
            }
        } else {
            System.println("sync: no art for " + albumId + " (" + responseCode + ")");
            _artStreak++;
            if (_artStreak >= GIVE_UP_AFTER) {
                // Three albums in a row is not three missing covers, it is
                // image requests not working at all from here — the same
                // answer for every album left. Skip the rest; the songs are
                // already down and play under the default art.
                System.println("sync: giving up on art, " + _art.size() + " left");
                _art = [];
            }
        }
        // Never straight into the next request from here. On a fenix 6X Pro
        // (firmware 28.02) a failing makeImageRequest calls onArt back
        // *synchronously*, from inside makeImageRequest itself, so calling
        // artNext() directly recursed onArt → artNext → onArt once per album
        // and overflowed the stack ("Stack Overflow Error", CIQ_LOG.YML) with
        // a hundred songs down — the sync died before finish(), so nothing
        // was recorded as synced and the next open or charge tried again and
        // crashed again. A timer runs the next request from a fresh stack.
        // The simulator never showed this: there the callback arrives later,
        // as the SDK docs suggest it always will.
        if (_later == null) {
            _later = new Timer.Timer();
        }
        _later.start(method(:artNext), 50, false);
    }

    // ── 6. done ──────────────────────────────────────────────────────────

    //! Drop what is no longer wanted. Last, not first: on the simulator a
    //! download issued right after a batch of deletions dies inside the
    //! system's own callback dispatch, and the space argument for deleting
    //! first is weak on a watch with gigabytes of it.
    private function deleteStale() {
        var playing = TracksLibrary.playing();
        for (var i = 0; i < _stale.size(); i++) {
            var gone = _stale[i];
            if (gone.getId().equals(playing)) {
                // The player is on it. Deleting it out from under the player
                // crashes the player when it comes back; next sync instead.
                System.println("sync: keeping the playing song for now");
                continue;
            }
            var songId = TracksLibrary.songFor(gone.getId());
            try {
                Media.deleteCachedItem(gone);
            } catch (ex) {
                // Already gone. Forgetting it is still right — a retry would
                // fail the same way every sync.
            }
            TracksLibrary.forgetSong(songId, gone.getId());
        }
        _stale = [];
    }

    //! The sync ran to the end. Anything that was skipped along the way is
    //! reported now, as the sync's result, rather than swallowed.
    private function finish() {
        deleteStale();
        TracksLibrary.pruneArt();
        System.println("sync: done, " + _done + "/" + _total + " downloaded, " + _failed + " failed, " + _skipped + " playlists skipped");
        TracksLibrary.setLastSync(Time.now().value());
        if (_byRequest && _done > 0) {
            TracksLibrary.armPlayAfterSync();
        }
        var message = null;
        if (_skipped > 0) {
            message = Lang.format(WatchUi.loadResource(Rez.Strings.ErrSkipped), [_skipped]);
        } else if (_failed > 0) {
            message = Lang.format(WatchUi.loadResource(Rez.Strings.ErrSomeFailed), [_failed]);
        }
        done(message);
    }

    //! The sync cannot continue. Whatever landed is kept and already recorded.
    private function abort(why) {
        System.println("sync: aborted (" + why + ")");
        var id = Rez.Strings.ErrNetwork;
        if (why == :auth) {
            id = Rez.Strings.ErrAuth;
        } else if (why == :storage) {
            id = Rez.Strings.ErrStorage;
        } else if (why == :server) {
            id = Rez.Strings.ErrServer;
        }
        done(WatchUi.loadResource(id));
    }
}
