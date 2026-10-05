// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Application.Storage;
using Toybox.Lang;
using Toybox.Time;
using Toybox.Media;
using Toybox.System;
using Toybox.WatchUi;

//! What the user asked for, and what this watch actually holds.
//!
//! All of it lives in Application.Storage, laid out flat — one small value per
//! key — because a single value is capped (8 KB on older firmware, 32 KB now)
//! and a library is not small. The blog post Garmin wrote for audio providers
//! recommends exactly this shape, and it is the difference between an app that
//! works up to the 500-song mark and one that silently stops saving at 70.
//!
//!   sel          [playlistId, …]           what the user ticked for download
//!   pls          [[id, name, count], …]    every playlist offered, last we looked
//!   pl:<id>      [[songId, albumId], …]    that playlist's songs as of the last sync
//!   s:<songId>   [refId, albumId]          a downloaded song: media-store id, its album
//!   r:<refId>    songId                    the reverse of the above
//!   art:<album>  bitmap                    cover art, one per album, not per song
//!   arts         [albumId, …]              which art:<album> keys exist
//!   last         Time.now() as a Number when the last sync finished
//!   shuffle      true while the player's shuffle is on
//!   repeat       the player's repeat mode (0 off, 1 one, 2 all)
//!   playing      media-store id of the song the player is on, or absent
//!
//! Titles and artists are not stored at all. The media player reads them from
//! the ID3 tags of the downloaded file, which Navidrome writes when it
//! transcodes, so keeping a copy here would be memory spent on nothing.
//!
//! Three playlists are not on the server. Subsonic has no playlist for "songs
//! I starred" or "songs I played lately" — those are queries — and "songs I
//! picked one by one on the watch" is this app's own idea. They live under ids
//! no server would ever mint, and the sync knows to resolve each differently.
module TracksLibrary {

    const SELECTED = "sel";
    const PLAYLISTS = "pls";
    const LAST_SYNC = "last";
    const LAST_ATTEMPT = "lastTry";
    const SYNC_REQUESTED = "askedSync";
    const PLAY_AFTER = "playAfter";

    //! Seconds an armed hand-off stays good for.
    const PLAY_AFTER_WINDOW = 120;
    const SHUFFLE = "shuffle";
    const REPEAT = "repeat";
    const PLAYING = "playing";
    const ART_INDEX = "arts";
    const PL_PREFIX = "pl:";
    const SONG_PREFIX = "s:";
    const REF_PREFIX = "r:";
    const ART_PREFIX = "art:";

    const STARRED = "~starred";
    const RECENT = "~recent";
    const PICKED = "~picked";

    //! The derived playlists, in the [id, name, count] shape server playlists
    //! use. Count is unknown until they are resolved — except for picked
    //! songs, which are counted right here.
    function builtins() {
        return [
            [STARRED, WatchUi.loadResource(Rez.Strings.LikedSongs), null],
            [RECENT, WatchUi.loadResource(Rez.Strings.RecentlyPlayed), null],
            [PICKED, WatchUi.loadResource(Rez.Strings.PickedSongs), songsOf(PICKED).size()]
        ];
    }

    // ── playlists on offer ───────────────────────────────────────────────

    function playlists() {
        var stored = Storage.getValue(PLAYLISTS);
        return (stored == null) ? [] : stored;
    }

    function savePlaylists(list) {
        Storage.setValue(PLAYLISTS, list);
    }

    // ── what the user chose ──────────────────────────────────────────────

    function selected() {
        var stored = Storage.getValue(SELECTED);
        return (stored == null) ? [] : stored;
    }

    function setSelected(ids) {
        Storage.setValue(SELECTED, ids);
    }

    function isSelected(playlistId) {
        return selected().indexOf(playlistId) >= 0;
    }

    function setSelectedFlag(playlistId, on) {
        var ids = selected();
        var at = ids.indexOf(playlistId);
        if (on && at < 0) {
            ids.add(playlistId);
        } else if (!on && at >= 0) {
            ids.remove(playlistId);
        }
        setSelected(ids);
    }

    // ── each playlist's songs ────────────────────────────────────────────

    //! [[songId, albumId], …] in play order.
    function songsOf(playlistId) {
        var stored = Storage.getValue(PL_PREFIX + playlistId);
        return (stored == null) ? [] : stored;
    }

    function saveSongsOf(playlistId, songs) {
        Storage.setValue(PL_PREFIX + playlistId, songs);
    }

    function forgetPlaylist(playlistId) {
        Storage.deleteValue(PL_PREFIX + playlistId);
    }

    //! Take a playlist off the watch, now, without a sync.
    //!
    //! Its songs are deleted unless some other ticked playlist also names
    //! them; its song list and its tick are dropped. Works with no network
    //! at all — deleting needs nothing from the server — which is the point
    //! of having it separate from the sync.
    function purgePlaylist(playlistId) {
        setSelectedFlag(playlistId, false);
        var keep = {};
        var others = selected();
        for (var i = 0; i < others.size(); i++) {
            var songs = songsOf(others[i]);
            for (var j = 0; j < songs.size(); j++) {
                keep[songs[j][0]] = true;
            }
        }
        var mine = songsOf(playlistId);
        for (var i = 0; i < mine.size(); i++) {
            var songId = mine[i][0];
            if (!keep.hasKey(songId)) {
                deleteSong(songId);
            }
        }
        forgetPlaylist(playlistId);
        pruneArt();
    }

    // ── picked songs ─────────────────────────────────────────────────────

    function isPicked(songId) {
        var songs = songsOf(PICKED);
        for (var i = 0; i < songs.size(); i++) {
            if (songs[i][0].equals(songId)) {
                return true;
            }
        }
        return false;
    }

    //! Add a song to the picked list and make sure the list is ticked, so the
    //! next sync fetches it.
    function pickSong(songId, albumId) {
        if (isPicked(songId)) {
            return;
        }
        var songs = songsOf(PICKED);
        songs.add([songId, albumId]);
        saveSongsOf(PICKED, songs);
        setSelectedFlag(PICKED, true);
    }

    //! Drop a song from the picked list and, unless another ticked playlist
    //! has it, from the watch.
    function unpickSong(songId) {
        var songs = songsOf(PICKED);
        var kept = [];
        for (var i = 0; i < songs.size(); i++) {
            if (!songs[i][0].equals(songId)) {
                kept.add(songs[i]);
            }
        }
        saveSongsOf(PICKED, kept);
        if (kept.size() == 0) {
            setSelectedFlag(PICKED, false);
        }
        var others = selected();
        for (var i = 0; i < others.size(); i++) {
            var list = songsOf(others[i]);
            for (var j = 0; j < list.size(); j++) {
                if (list[j][0].equals(songId)) {
                    return;
                }
            }
        }
        deleteSong(songId);
        pruneArt();
    }

    //! Merge songs picked elsewhere (the phone) into the list.
    function pickSongs(pairs) {
        for (var i = 0; i < pairs.size(); i++) {
            pickSong(pairs[i][0], pairs[i][1]);
        }
    }

    // ── downloaded songs ↔ media store ───────────────────────────────────

    function refFor(songId) {
        var entry = Storage.getValue(SONG_PREFIX + songId);
        return (entry instanceof Lang.Array) ? entry[0] : null;
    }

    function albumOf(songId) {
        var entry = Storage.getValue(SONG_PREFIX + songId);
        return (entry instanceof Lang.Array) ? entry[1] : null;
    }

    function songFor(refId) {
        return Storage.getValue(REF_PREFIX + refId.toString());
    }

    function recordDownload(songId, albumId, refId) {
        Storage.setValue(SONG_PREFIX + songId, [refId, albumId]);
        Storage.setValue(REF_PREFIX + refId.toString(), songId);
    }

    //! Forget a song. The caller has already deleted the audio — order matters,
    //! because a media file nothing can name any more is storage nobody can
    //! reclaim without a factory reset.
    function forgetSong(songId, refId) {
        if (songId != null) {
            Storage.deleteValue(SONG_PREFIX + songId);
        }
        if (refId != null) {
            Storage.deleteValue(REF_PREFIX + refId.toString());
        }
    }

    //! Delete a song's audio and forget it, by song id.
    //!
    //! Except the one the player is on: deleting that out from under it
    //! crashes the player when it comes back (seen on the simulator, not
    //! worth finding out about on a watch). It stays until a later sync
    //! finds it no longer playing.
    function deleteSong(songId) {
        var refId = refFor(songId);
        if (refId != null && refId.equals(playing())) {
            return;
        }
        if (refId != null) {
            try {
                Media.deleteCachedItem(new Media.ContentRef(refId, Media.CONTENT_TYPE_AUDIO));
            } catch (ex) {
                // Already gone. Forgetting it is still right — a retry would
                // fail the same way every time.
            }
        }
        forgetSong(songId, refId);
    }

    //! The media-store ids for these songs, in this order, skipping any that
    //! were never downloaded. A sync cut short leaves playlists that are only
    //! partly here, and a short playlist beats one that stalls on a gap.
    function refsFor(songs) {
        var out = [];
        for (var i = 0; i < songs.size(); i++) {
            var ref = refFor(songs[i][0]);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    //! The media store's own list of what it holds, or null when it holds
    //! nothing. The SDK types getContentRefIter as never null; the runtime
    //! returns null for an empty store and crashes on the first next() —
    //! found in the simulator, and the cast is what lets the null check
    //! survive the type checker.
    function contentRefs() as Media.ContentRefIterator or Null {
        var iter = Media.getContentRefIter({ :contentType => Media.CONTENT_TYPE_AUDIO, :shuffle => false }) as Lang.Object;
        if (iter == null) {
            return null;
        }
        return iter as Media.ContentRefIterator;
    }

    //! Every downloaded song, straight from the media store — the one source
    //! that cannot disagree with itself about what is on the watch.
    function allRefs() {
        var out = [];
        var it = contentRefs();
        if (it == null) {
            return out;
        }
        var ref = it.next();
        while (ref != null) {
            out.add(ref.getId());
            ref = it.next();
        }
        return out;
    }

    //! The playlists that have at least one song on the watch, as
    //! [id, name, downloadedCount].
    function downloaded() {
        var out = [];
        var list = playlists();
        for (var i = 0; i < list.size(); i++) {
            var id = list[i][0];
            var refs = refsFor(songsOf(id));
            if (refs.size() > 0) {
                out.add([id, list[i][1], refs.size()]);
            }
        }
        return out;
    }

    // ── cover art ────────────────────────────────────────────────────────

    function artFor(albumId) {
        return (albumId == null) ? null : Storage.getValue(ART_PREFIX + albumId);
    }

    function hasArt(albumId) {
        return artIndex().indexOf(albumId) >= 0;
    }

    function artIndex() {
        var stored = Storage.getValue(ART_INDEX);
        return (stored == null) ? [] : stored;
    }

    function saveArt(albumId, bitmap) {
        Storage.setValue(ART_PREFIX + albumId, bitmap);
        var index = artIndex();
        if (index.indexOf(albumId) < 0) {
            index.add(albumId);
            Storage.setValue(ART_INDEX, index);
        }
    }

    //! Drop art for albums no downloaded song belongs to any more.
    function pruneArt() {
        var wanted = {};
        var it = contentRefs();
        if (it != null) {
            var ref = it.next();
            while (ref != null) {
                var songId = songFor(ref.getId());
                var albumId = (songId == null) ? null : albumOf(songId);
                if (albumId != null) {
                    wanted[albumId] = true;
                }
                ref = it.next();
            }
        }
        var index = artIndex();
        var kept = [];
        for (var i = 0; i < index.size(); i++) {
            if (wanted.hasKey(index[i])) {
                kept.add(index[i]);
            } else {
                Storage.deleteValue(ART_PREFIX + index[i]);
            }
        }
        Storage.setValue(ART_INDEX, kept);
    }

    // ── the whole library ────────────────────────────────────────────────

    //! Delete every downloaded song and everything recorded about them and
    //! about what was chosen — for a log out or a change of account, because
    //! the music belongs to the account it came from.
    //!
    //! The media store is walked rather than the s:/r: keys, because it is the
    //! one record that cannot miss a file; a song whose key was lost is still
    //! deleted. The song the player is on is the exception, as everywhere
    //! (see deleteSong): it stays in the media store, unnamed, and the next
    //! sync deletes it as a file nothing wants.
    //!
    //! Then storage is cleared wholesale — Storage cannot list its keys, and
    //! per-playlist and per-song keys would otherwise linger for ever — and
    //! the few values that are not about the library are put back.
    function wipe() {
        var current = playing();
        var it = contentRefs();
        if (it != null) {
            // Collected first: deleting while iterating the store is not
            // something the SDK promises to survive.
            var refs = [];
            var ref = it.next();
            while (ref != null) {
                refs.add(ref);
                ref = it.next();
            }
            System.println("library: wiping " + refs.size() + " songs");
            for (var i = 0; i < refs.size(); i++) {
                if (refs[i].getId().equals(current)) {
                    continue;
                }
                try {
                    Media.deleteCachedItem(refs[i]);
                } catch (ex) {
                    // Already gone.
                }
            }
        }
        var keep = [];
        for (var i = 0; i < KEEP_ON_WIPE.size(); i++) {
            keep.add(Storage.getValue(KEEP_ON_WIPE[i]));
        }
        Storage.clearValues();
        for (var i = 0; i < KEEP_ON_WIPE.size(); i++) {
            if (keep[i] != null) {
                Storage.setValue(KEEP_ON_WIPE[i], keep[i]);
            }
        }
    }

    //! What survives a wipe: the account and revisions (the caller replaces
    //! the account straight after), the player's own settings, which song it
    //! is on (so the next sync, not this wipe, is what deletes it), and the dev
    //! build's once-per-build marker — losing that would start a sync the
    //! moment the simulator next launched the app.
    const KEEP_ON_WIPE = ["cfg", "rev", "revSeen", SHUFFLE, REPEAT, PLAYING, "dev:generation"];

    // ── player state and bookkeeping ─────────────────────────────────────

    function lastSync() {
        return Storage.getValue(LAST_SYNC);
    }

    function setLastSync(seconds) {
        Storage.setValue(LAST_SYNC, seconds);
    }

    //! When a sync last *ended*, however it ended. Distinct from lastSync,
    //! which only moves on success: one throttles retries after a failure, the
    //! other is what keeps the daily sync daily.
    function lastAttempt() {
        return Storage.getValue(LAST_ATTEMPT);
    }

    function setLastAttempt(seconds) {
        Storage.setValue(LAST_ATTEMPT, seconds);
    }

    //! Set when the user asks for a sync, so that request can overtake the
    //! once-a-day rule. Cleared as the sync begins.
    function syncRequested() {
        return Storage.getValue(SYNC_REQUESTED) == true;
    }

    function setSyncRequested(on) {
        Storage.setValue(SYNC_REQUESTED, on);
    }

    //! Set when a sync the user asked for has just finished, so the next time
    //! the app opens it goes to the music rather than to a list. Deliberately
    //! not set by the automatic charger sync: that one can finish at four in
    //! the morning, and it must not arm playback hours ahead of being opened.
    //! True only for a couple of minutes after a sync armed it.
    //!
    //! It used to be a plain flag, which meant that if anything consumed it
    //! late — opening the library an hour later, say — the app jumped into
    //! playback and started a song nobody had asked for. Arming is a moment,
    //! not a state, so it is stored as one and expires.
    function playAfterSync() {
        var at = Storage.getValue(PLAY_AFTER);
        if (!(at instanceof Lang.Number)) {
            return false;
        }
        var age = Time.now().value() - at;
        return age >= 0 && age < PLAY_AFTER_WINDOW;
    }

    function armPlayAfterSync() {
        Storage.setValue(PLAY_AFTER, Time.now().value());
    }

    function clearPlayAfterSync() {
        Storage.deleteValue(PLAY_AFTER);
    }

    function shuffle() {
        return Storage.getValue(SHUFFLE) == true;
    }

    function setShuffle(on) {
        Storage.setValue(SHUFFLE, on);
    }

    //! Repeat as the media player's own three-state mode, stored as its enum
    //! value (0 off, 1 one, 2 all) so it survives the app being relaunched
    //! into playback. The player reads it back through the iterator's
    //! repeatMode(), which is how the repeat icon knows which state to show.
    function repeatMode() {
        var v = Storage.getValue(REPEAT);
        return (v == null) ? 0 : v;
    }

    function setRepeatMode(mode) {
        Storage.setValue(REPEAT, mode);
    }

    function playing() {
        return Storage.getValue(PLAYING);
    }

    function setPlaying(refId) {
        if (refId == null) {
            Storage.deleteValue(PLAYING);
        } else {
            Storage.setValue(PLAYING, refId);
        }
    }
}
