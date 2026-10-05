// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Lang;
using Toybox.WatchUi;

//! Picking single songs: albums, then the songs on one, each a toggle.
//!
//! Albums rather than a flat song list because a watch has no keyboard and a
//! library has thousands of songs; the albums played lately come first, since
//! that is where the song someone wants usually is, and the rest are a
//! screenful at a time behind "More…".
//!
//! A ticked song goes on the picked list and is fetched at the next sync;
//! un-ticking it takes it off the watch at once, unless a ticked playlist also
//! has it. All of this browses through the phone's proxy — the watch is not on
//! Wi-Fi here — which is fine for a few kilobytes of album names.
//!
//! Every list here is loaded the same way: a plain screen that says
//! "Loading…" until the rows arrive, then becomes the menu. Menu2 has to be
//! built with its items, so the menu cannot exist before the data does.
module PickSongs {

    //! Albums per screenful of "All albums".
    const PAGE = 15;

    //! Recently played albums first, capped to a short list.
    const RECENT = 8;

    function openAlbums() {
        push(Rez.Strings.Albums, new AlbumsLoader(null), new AlbumsBuilder(null));
    }

    function openAllAlbums(start) {
        push(Rez.Strings.AllAlbums, new AlbumsLoader(start), new AlbumsBuilder(start));
    }

    //! "More" is the same screen showing later rows, so it replaces rather
    //! than stacks. Pushing meant backing out of an album walked back through
    //! every page that had been scrolled past before finally leaving.
    function pageAllAlbums(start) {
        var view = new AsyncMenuView(Rez.Strings.AllAlbums, new AlbumsLoader(start), new AlbumsBuilder(start));
        WatchUi.switchToView(view, new AsyncMenuDelegate(view), WatchUi.SLIDE_LEFT);
    }

    //! Ask for a few letters, then show what matches.
    function openSearch() {
        WatchUi.pushView(new WatchUi.TextPicker(""), new SearchTextDelegate(), WatchUi.SLIDE_LEFT);
    }

    function openResults(query) {
        push(Rez.Strings.Search, new SearchLoader(query), new SearchBuilder(query));
    }

    function openAlbum(albumId, name) {
        push(name, new AlbumSongsLoader(albumId), new AlbumSongsBuilder(albumId, name));
    }

    function push(title, loader, builder) {
        var view = new AsyncMenuView(title, loader, builder);
        WatchUi.pushView(view, new AsyncMenuDelegate(view), WatchUi.SLIDE_LEFT);
    }
}

//! Shows "Loading…" while a loader runs, then swaps itself for the menu the
//! builder makes from the rows. Stays, showing why, if the rows never come.
class AsyncMenuView extends WatchUi.View {

    private var _title;
    private var _loader;
    private var _builder;
    private var _status;
    private var _loading;
    private var _failed;

    function initialize(title, loader, builder) {
        View.initialize();
        _title = (title instanceof Lang.String) ? title : WatchUi.loadResource(title);
        _loader = loader;
        _builder = builder;
        _status = WatchUi.loadResource(Rez.Strings.Loading);
        _loading = false;
        _failed = false;
    }

    function onShow() {
        // Load once on first show, and again only via retry — never on the
        // return from the menu we switched to.
        if (_loading || _failed || _status == null) {
            return;
        }
        load();
    }

    function load() {
        _loading = true;
        _failed = false;
        _status = WatchUi.loadResource(Rez.Strings.Loading);
        WatchUi.requestUpdate();
        _loader.load(method(:onLoaded));
    }

    //! START retries a failed load rather than sitting on the error — the same
    //! recovery the pairing screen offers, so no list screen is a dead end.
    function onSelect() {
        if (_failed && !_loading) {
            load();
        }
    }

    function onLoaded(items) as Void {
        _loading = false;
        if (items == null) {
            _failed = true;
            _status = WatchUi.loadResource(
                (Navidrome.lastError == :auth) ? Rez.Strings.ErrAuth : Rez.Strings.ErrNoPhone);
            WatchUi.requestUpdate();
            return;
        }
        // A null status marks "we handed off to the menu": onShow must not
        // reload if the user backs into this view from there.
        _status = null;
        var pair = _builder.build(items);
        WatchUi.switchToView(pair[0], pair[1], WatchUi.SLIDE_LEFT);
    }

    function onUpdate(dc) {
        TracksScreen.draw(dc, _title, _status);
    }
}

class AsyncMenuDelegate extends WatchUi.BehaviorDelegate {
    private var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() {
        _view.onSelect();
        return true;
    }
}

// ── albums ───────────────────────────────────────────────────────────────

//! Loads either the recently played albums (start == null) or one page of
//! all albums, alphabetical, from `start`.
class AlbumsLoader {
    private var _start;
    private var _done;

    function initialize(start) {
        _start = start;
        _done = null;
    }

    function load(done) {
        _done = done;
        if (_start == null) {
            Navidrome.recentAlbumPage(PickSongs.RECENT, method(:onBody));
        } else {
            Navidrome.albumPage(_start, method(:onBody));
        }
    }

    //! Subsonic answers with one document rather than a page of rows, so the
    //! rows are pulled out here and handed on in the shape the builder wants.
    function onBody(body) as Void {
        (_done as Lang.Method).invoke(body == null ? null : Navidrome.albumRows(body));
    }
}

class AlbumsBuilder {
    private var _start;

    function initialize(start) {
        _start = start;
    }

    //! Rows are [albumId, name, albumArtist].
    function build(albums) {
        var title = (_start == null) ? Rez.Strings.Albums : Rez.Strings.AllAlbums;
        var menu = new WatchUi.Menu2({ :title => WatchUi.loadResource(title) });
        // Search leads: on a library of hundreds of albums, typing three
        // letters beats scrolling, and it is the only thing here that does not
        // get slower as the library grows.
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.Search),
            WatchUi.loadResource(Rez.Strings.SearchHint), :search, {}
        ));
        for (var i = 0; i < albums.size(); i++) {
            menu.addItem(new WatchUi.MenuItem(albums[i][1], albums[i][2], albums[i][0], {}));
        }
        if (_start == null) {
            menu.addItem(new WatchUi.MenuItem(WatchUi.loadResource(Rez.Strings.AllAlbums), null, :all, {}));
        } else if (albums.size() >= Navidrome.ALBUM_PAGE) {
            menu.addItem(new WatchUi.MenuItem(WatchUi.loadResource(Rez.Strings.More), null, :more, {}));
        }
        if (albums.size() == 0 && _start != null) {
            menu.addItem(new WatchUi.MenuItem(WatchUi.loadResource(Rez.Strings.NoAlbums), null, :none, {}));
        }
        return [menu, new AlbumsMenuDelegate(_start, albums)];
    }
}

class AlbumsMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var _start;
    private var _albums;

    function initialize(start, albums) {
        Menu2InputDelegate.initialize();
        _start = start;
        _albums = albums;
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :all) {
            PickSongs.openAllAlbums(0);
        } else if (id == :search) {
            PickSongs.openSearch();
        } else if (id == :more) {
            PickSongs.pageAllAlbums(_start + Navidrome.ALBUM_PAGE);
        } else if (id instanceof Lang.String) {
            PickSongs.openAlbum(id, item.getLabel());
        }
    }
}

// ── one album's songs ────────────────────────────────────────────────────

class AlbumSongsLoader {
    private var _albumId;

    function initialize(albumId) {
        _albumId = albumId;
    }

    function load(done) {
        new NavidromeList(Navidrome.albumSongsPath(_albumId), Navidrome.SONG_PICK_FIELDS, null, null).run(done);
    }
}

class AlbumSongsBuilder {
    private var _albumId;
    private var _name;

    function initialize(albumId, name) {
        _albumId = albumId;
        _name = name;
    }

    //! Rows are [songId, title, albumId].
    //!
    //! The whole album goes first. Wanting all of it is the common case, and
    //! ticking fifteen songs one at a time to say so is the kind of thing a
    //! watch is worst at.
    function build(songs) {
        var menu = new WatchUi.Menu2({ :title => _name });
        var all = allPicked(songs);
        menu.addItem(new WatchUi.ToggleMenuItem(
            WatchUi.loadResource(Rez.Strings.WholeAlbum),
            { :enabled => WatchUi.loadResource(Rez.Strings.WholeAlbumOn),
              :disabled => Lang.format(WatchUi.loadResource(Rez.Strings.SongCount), [songs.size()]) },
            :album, all, {}
        ));
        for (var i = 0; i < songs.size(); i++) {
            menu.addItem(new WatchUi.ToggleMenuItem(
                songs[i][1], null, songs[i][0], TracksLibrary.isPicked(songs[i][0]), {}
            ));
        }
        return [menu, new AlbumSongsDelegate(_albumId, songs)];
    }

    //! True when every song on the album is already picked — so the toggle
    //! opens in the state the album is actually in.
    private function allPicked(songs) {
        if (songs.size() == 0) {
            return false;
        }
        for (var i = 0; i < songs.size(); i++) {
            if (!TracksLibrary.isPicked(songs[i][0])) {
                return false;
            }
        }
        return true;
    }
}

class AlbumSongsDelegate extends WatchUi.Menu2InputDelegate {
    private var _albumId;
    private var _songs;

    function initialize(albumId, songs) {
        Menu2InputDelegate.initialize();
        _albumId = albumId;
        _songs = songs;
    }

    function onSelect(item) {
        var toggle = item as WatchUi.ToggleMenuItem;
        var on = toggle.isEnabled();
        if (item.getId() == :album) {
            // The album row moves every song with it. The individual rows
            // already on screen are left showing their old ticks — Menu2 will
            // not let them be rewritten in place — but the store is right, and
            // reopening the album shows it.
            for (var i = 0; i < _songs.size(); i++) {
                if (on) {
                    TracksLibrary.pickSong(_songs[i][0], _albumId);
                } else {
                    TracksLibrary.unpickSong(_songs[i][0]);
                }
            }
            return;
        }
        if (on) {
            TracksLibrary.pickSong(item.getId(), _albumId);
        } else {
            TracksLibrary.unpickSong(item.getId());
        }
    }
}

// ── search ───────────────────────────────────────────────────────────────

//! Takes what was typed and opens the results.
//!
//! The picker is popped before the results are pushed, so backing out of the
//! results lands on the album list rather than on the keyboard again — which
//! is the one screen nobody wants to meet twice.
class SearchTextDelegate extends WatchUi.TextPickerDelegate {

    function initialize() {
        TextPickerDelegate.initialize();
    }

    function onTextEntered(text, changed) {
        if (text != null && text.length() > 0) {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
            PickSongs.openResults(text);
            return true;
        }
        return false;
    }

    function onCancel() {
        return false;
    }
}

class SearchLoader {
    private var _query;
    private var _done;

    function initialize(query) {
        _query = query;
        _done = null;
    }

    function load(done) {
        _done = done;
        Navidrome.searchSongs(_query, 0, method(:onBody));
    }

    function onBody(body) as Void {
        (_done as Lang.Method).invoke(body == null ? null : Navidrome.songRows(body));
    }
}

//! Results are songs, each a toggle, exactly as inside an album — the point of
//! searching is to pick something, not to browse somewhere else first.
class SearchBuilder {
    private var _query;

    function initialize(query) {
        _query = query;
    }

    //! Rows are [songId, title, albumId].
    function build(songs) {
        var menu = new WatchUi.Menu2({ :title => _query });
        for (var i = 0; i < songs.size(); i++) {
            menu.addItem(new WatchUi.ToggleMenuItem(
                songs[i][1], null, songs[i][0], TracksLibrary.isPicked(songs[i][0]), {}
            ));
        }
        if (songs.size() == 0) {
            menu.addItem(new WatchUi.MenuItem(WatchUi.loadResource(Rez.Strings.NoMatches), null, :none, {}));
        }
        return [menu, new SearchResultsDelegate(songs)];
    }
}

class SearchResultsDelegate extends WatchUi.Menu2InputDelegate {
    private var _songs;

    function initialize(songs) {
        Menu2InputDelegate.initialize();
        _songs = songs;
    }

    function onSelect(item) {
        var id = item.getId();
        if (!(id instanceof Lang.String)) {
            return;
        }
        var toggle = item as WatchUi.ToggleMenuItem;
        // The album id travels with the row, so a song picked from a search
        // still knows which cover art belongs to it come sync time.
        var albumId = null;
        for (var i = 0; i < _songs.size(); i++) {
            if (_songs[i][0].equals(id)) {
                albumId = _songs[i][2];
            }
        }
        if (toggle.isEnabled()) {
            TracksLibrary.pickSong(id, albumId);
        } else {
            TracksLibrary.unpickSong(id);
        }
    }
}
