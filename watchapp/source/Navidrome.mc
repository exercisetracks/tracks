// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Lang;
using Toybox.PersistedContent;
using Toybox.System;

//! Navidrome, as much of it as a watch needs.
//!
//! The watch talks to the music server directly — over the phone's Bluetooth
//! link when browsing, over its own Wi-Fi when syncing — with no Tracks server
//! in the path. Once paired, it keeps itself in sync from the charger with
//! nothing else switched on.
//!
//! Two APIs, and why both. Streaming uses the Subsonic API, which every server
//! in the family speaks. Lists do not: a Connect IQ web response is capped at
//! about 16 KB (measured on the fēnix 6X Pro profile — 12 songs pass, 14 do
//! not), and Subsonic's `getPlaylist` and `getStarred2` return the whole list
//! in one body with no way to page it. Navidrome's own API pages everything,
//! so lists come from there — one row at a time if that is what fits. The
//! price is that lists are Navidrome-specific; streaming is not.
//!
//! One login per session hands back everything both APIs need: a JWT for
//! `/api`, and the Subsonic token and salt for `/rest/stream`. So the watch
//! stores the password and never has to hash anything.
module Navidrome {

    const API_VERSION = "1.16.1";
    const CLIENT = "TracksMusic";

    //! What to ask the server to transcode to. mp3 because it is the one
    //! format every Garmin indexes reliably.
    const FORMAT = "mp3";
    //! 128 kbps, chosen against measurement rather than taste. The server
    //! transcodes and serves a song in about three seconds, so it is not the
    //! slow part: the 5.5 MB a 192 kbps song weighed was, over the watch's own
    //! Wi-Fi and into an encrypted media store. At 128 a song is about 3.7 MB,
    //! which is a third off every sync and half again as many songs in the
    //! same space — and it is going out through Bluetooth earbuds that
    //! re-compress it anyway.
    const MAX_BITRATE = 128;

    //! Rows per list request to start with. A song row is 2–8 KB depending on
    //! how much lyric and comment text is embedded, so this is halved on every
    //! "response too large" until a page fits. Never grown back within a sync:
    //! a size that worked keeps working, and a bigger one that does not costs a
    //! round trip to find out.
    const PAGE_START = 4;

    //! How many recently played songs "Recently played" holds.
    const RECENT_LIMIT = 50;

    // From the last login; valid for this run of the app.
    var _jwt = null;
    var _subsonicToken = null;
    var _subsonicSalt = null;

    //! Why the last call failed, as one of:
    //!   :auth     the server rejected the credentials
    //!   :tooBig   the response did not fit in the watch's memory
    //!   :network  could not reach the server at all
    //!   :server   the server answered, but with a failure
    var lastError = null;

    function isLoggedIn() {
        return _jwt != null;
    }

    function logout() {
        _jwt = null;
        _subsonicToken = null;
        _subsonicSalt = null;
    }

    // ── login ────────────────────────────────────────────────────────────

    //! POST the password once; the callback gets (true) or (false), with
    //! `lastError` set on false.
    function login(callback) {
        loginAs(TracksConfig.load(), callback);
    }

    //! The same, for credentials that are not (yet) the stored ones — an
    //! account typed on the watch is checked this way before it replaces
    //! anything. On success the session is that account's.
    function loginAs(cfg, callback) {
        _jwt = null;
        Communications.makeWebRequest(
            cfg["url"] + "/auth/login",
            { "username" => cfg["user"], "password" => cfg["password"] },
            {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => { "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON },
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => callback
            },
            new Lang.Method(Navidrome, :onLogin)
        );
    }

    function onLogin(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator, callback as Lang.Object) as Void {
        lastError = null;
        if (responseCode == 401 || responseCode == 403) {
            lastError = :auth;
        } else if (responseCode != 200 || !(data instanceof Lang.Dictionary) || data["token"] == null) {
            lastError = (responseCode == 200) ? :server : :network;
        } else {
            _jwt = data["token"];
            _subsonicToken = data["subsonicToken"];
            _subsonicSalt = data["subsonicSalt"];
        }
        (callback as Lang.Method).invoke(_jwt != null);
    }

    // ── lists ────────────────────────────────────────────────────────────

    //! One page of a native-API list. `path` is everything after `/api/`,
    //! including its own query string. The callback gets (rows as Array or
    //! Null); on null, `lastError` says why.
    function page(path, start, count, callback) {
        var cfg = TracksConfig.load();
        var sep = (path.find("?") == null) ? "?" : "&";
        Communications.makeWebRequest(
            cfg["url"] + "/api/" + path + sep + "_start=" + start + "&_end=" + (start + count)
                + "&jwt=" + _jwt,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => callback
            },
            new Lang.Method(Navidrome, :onPage)
        );
    }

    function onPage(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator, callback as Lang.Object) as Void {
        lastError = null;
        var rows = null;
        if (responseCode == Communications.NETWORK_RESPONSE_TOO_LARGE
            || responseCode == Communications.NETWORK_RESPONSE_OUT_OF_MEMORY) {
            lastError = :tooBig;
        } else if (responseCode == 401 || responseCode == 403) {
            lastError = :auth;
        } else if (responseCode != 200) {
            lastError = :network;
        } else if (data instanceof Lang.Array) {
            rows = data;
        } else {
            lastError = :server;
        }
        if (rows == null) {
            System.println("navidrome: page failed " + responseCode);
        }
        (callback as Lang.Method).invoke(rows);
    }

    //! Paths for the lists the app offers, and the fields each row is kept as.
    const PLAYLIST_FIELDS = ["id", "name", "songCount"];
    const TRACK_FIELDS = ["mediaFileId", "albumId"];      // playlist rows name the song indirectly
    const SONG_FIELDS = ["id", "albumId"];
    const SONG_PICK_FIELDS = ["id", "title", "albumId"];
    const ALBUM_FIELDS = ["id", "name", "albumArtist"];

    function playlistsPath() {
        return "playlist?_sort=name";
    }

    function playlistTracksPath(playlistId) {
        return "playlist/" + playlistId + "/tracks";
    }

    function starredPath() {
        return "song?starred=true&_sort=starred_at&_order=DESC";
    }

    function recentPath() {
        return "song?_sort=play_date&_order=DESC";
    }

    function recentAlbumsPath() {
        return "album?_sort=play_date&_order=DESC";
    }

    function albumsPath() {
        return "album?_sort=name";
    }

    function albumSongsPath(albumId) {
        return "song?album_id=" + albumId + "&_sort=track_number";
    }

    // ── browsing, over the Subsonic API ──────────────────────────────────

    //! Browsing uses Subsonic, listing uses Navidrome's own API, and the split
    //! is about bytes rather than taste.
    //!
    //! A Connect IQ response is capped near 16 KB. A Navidrome native row
    //! carries every field it has — measured at 2.9 KB for an album and 4.4 KB
    //! for a song — so a page is five albums, or three songs, and browsing a
    //! library is dozens of round trips. The Subsonic equivalents answer in
    //! about 650 bytes an album: fifteen fit in one response where five did.
    //! It also has a search, which the native API makes no cheaper.
    //!
    //! Sync still uses the native API, because only that can page a playlist.

    //! Albums per browse request. Fifteen is ~10 KB, comfortably inside the cap.
    const ALBUM_PAGE = 15;

    //! Songs per search response, at roughly 1.2 KB each.
    const SEARCH_PAGE = 10;

    //! Module-level, so not `private`: Monkey C does not allow it here.
    function auth() {
        var cfg = TracksConfig.load();
        return "u=" + Communications.encodeURL(cfg["user"])
            + "&t=" + _subsonicToken
            + "&s=" + _subsonicSalt
            + "&v=" + API_VERSION
            + "&c=" + CLIENT
            + "&f=json";
    }

    //! One Subsonic call. The callback gets the inner `subsonic-response`
    //! dictionary, or null with `lastError` set.
    function subsonic(path, params, callback) {
        var cfg = TracksConfig.load();
        Communications.makeWebRequest(
            cfg["url"] + "/rest/" + path + "?" + auth() + params,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => callback
            },
            new Lang.Method(Navidrome, :onSubsonic)
        );
    }

    function onSubsonic(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator, callback as Lang.Object) as Void {
        lastError = null;
        var body = null;
        if (responseCode == Communications.NETWORK_RESPONSE_TOO_LARGE
            || responseCode == Communications.NETWORK_RESPONSE_OUT_OF_MEMORY) {
            lastError = :tooBig;
        } else if (responseCode == 401 || responseCode == 403) {
            lastError = :auth;
        } else if (responseCode != 200) {
            lastError = :network;
        } else if (data instanceof Lang.Dictionary && data["subsonic-response"] != null) {
            body = data["subsonic-response"];
        } else {
            lastError = :server;
        }
        if (body == null) {
            System.println("navidrome: subsonic failed " + responseCode);
        }
        (callback as Lang.Method).invoke(body);
    }

    //! One page of albums, alphabetical.
    function albumPage(offset, callback) {
        subsonic("getAlbumList2.view",
            "&type=alphabeticalByName&size=" + ALBUM_PAGE + "&offset=" + offset, callback);
    }

    //! Albums played most recently, for the short list offered first.
    function recentAlbumPage(count, callback) {
        subsonic("getAlbumList2.view", "&type=recent&size=" + count + "&offset=0", callback);
    }

    //! Songs matching what the user typed.
    function searchSongs(query, offset, callback) {
        subsonic("search3.view",
            "&query=" + Communications.encodeURL(query)
            + "&artistCount=0&albumCount=0"
            + "&songCount=" + SEARCH_PAGE + "&songOffset=" + offset, callback);
    }

    //! Pull the album rows out of a getAlbumList2 reply, as [id, name, artist].
    function albumRows(body) {
        if (body == null || body["albumList2"] == null) {
            return [];
        }
        var albums = body["albumList2"]["album"];
        if (!(albums instanceof Lang.Array)) {
            return [];
        }
        var rows = [];
        for (var i = 0; i < albums.size(); i++) {
            rows.add([albums[i]["id"], albums[i]["name"], albums[i]["artist"]]);
        }
        return rows;
    }

    //! Pull the song rows out of a search3 reply, as [id, title, albumId].
    function songRows(body) {
        if (body == null || body["searchResult3"] == null) {
            return [];
        }
        var songs = body["searchResult3"]["song"];
        if (!(songs instanceof Lang.Array)) {
            return [];
        }
        var rows = [];
        for (var i = 0; i < songs.size(); i++) {
            rows.add([songs[i]["id"], songs[i]["title"], songs[i]["albumId"]]);
        }
        return rows;
    }

    // ── art ──────────────────────────────────────────────────────────────

    //! Square cover art for an album, at the size the player draws it.
    //!
    //! Small on purpose. Art is kept in Application.Storage, which takes a
    //! bitmap but caps a single value at 32 KB, and a 120-pixel square can go
    //! past that on a colour screen — at which point the art is silently not
    //! there and every song falls back to the default icon.
    const ART_SIZE = 60;

    function coverArtUrl(albumId) {
        var cfg = TracksConfig.load();
        return cfg["url"] + "/rest/getCoverArt.view"
            + "?u=" + Communications.encodeURL(cfg["user"])
            + "&t=" + _subsonicToken
            + "&s=" + _subsonicSalt
            + "&v=" + API_VERSION
            + "&c=" + CLIENT
            + "&id=al-" + Communications.encodeURL(albumId)
            + "&size=" + ART_SIZE;
    }

    // ── streaming ────────────────────────────────────────────────────────

    //! Where to download one song from, transcoded for the watch. Subsonic
    //! auth, using the token the login handed back.
    function streamUrl(songId) {
        var cfg = TracksConfig.load();
        return cfg["url"] + "/rest/stream.view"
            + "?u=" + Communications.encodeURL(cfg["user"])
            + "&t=" + _subsonicToken
            + "&s=" + _subsonicSalt
            + "&v=" + API_VERSION
            + "&c=" + CLIENT
            + "&id=" + Communications.encodeURL(songId)
            + "&format=" + FORMAT
            + "&maxBitRate=" + MAX_BITRATE;
    }
}

//! Walks one paged list to the end, collecting a few fields from every row.
//!
//! Handles the page-size dance: start at PAGE_START rows, halve on "too
//! large", and if a single row will not fit, step over it — one song with a
//! novel in its lyrics tag should not cost the user the playlist.
//!
//! A row that fails `requireField` (null, empty, or zero) ends the walk early.
//! That is how "Recently played" stops at the last song that was ever played,
//! rather than paging through the whole library sorted by nothing.
class NavidromeList {

    private var _path;
    private var _fields;
    private var _limit;
    private var _requireField;
    private var _items;
    private var _start;
    private var _size;
    private var _asked;     // rows requested by the page in flight
    private var _done;

    //! @param path          native-API path, see Navidrome.*Path()
    //! @param fields        row fields to keep, in order; each item comes back
    //!                      as an array of just those. Everything else in the
    //!                      row is dropped on the spot — a song row is thirty
    //!                      fields and these lists are kept in storage.
    //! @param limit         stop after this many, or null for all
    //! @param requireField  row field that must be truthy, or null
    function initialize(path, fields, limit, requireField) {
        _path = path;
        _fields = fields;
        _limit = limit;
        _requireField = requireField;
        _items = [];
        _start = 0;
        _size = Navidrome.PAGE_START;
    }

    //! `done` is a Method taking (items as Array or Null). Null means the
    //! list could not be read at all; Navidrome.lastError says why.
    function run(done) {
        _done = done;
        fetch();
    }

    //! Continue from an offset with a page size — for menus that load a
    //! screenful at a time and offer "More".
    function runPage(start, count, done) {
        _start = start;
        _limit = count;
        _done = done;
        fetch();
    }

    private function fetch() {
        _asked = _size;
        if (_limit != null && _limit - _items.size() < _asked) {
            _asked = _limit - _items.size();
        }
        Navidrome.page(_path, _start, _asked, method(:onRows));
    }

    function onRows(rows) as Void {
        if (rows == null) {
            if (Navidrome.lastError == :tooBig) {
                if (_size > 1) {
                    _size = _size / 2;
                } else {
                    _start++;
                }
                fetch();
                return;
            }
            _done.invoke(null);
            return;
        }

        var stop = false;
        for (var i = 0; i < rows.size() && !stop; i++) {
            var row = rows[i];
            if (_requireField != null) {
                var v = row[_requireField];
                if (v == null || v.equals(0) || v.equals("")) {
                    stop = true;
                    break;
                }
            }
            var item = new [_fields.size()];
            for (var f = 0; f < _fields.size(); f++) {
                item[f] = row[_fields[f]];
            }
            if (item[0] != null) {
                _items.add(item);
            }
            if (_limit != null && _items.size() >= _limit) {
                stop = true;
            }
        }

        if (stop || rows.size() < _asked) {
            // A short page is the last page.
            _done.invoke(_items);
            return;
        }
        _start += rows.size();
        fetch();
    }
}
