// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Lang;
using Toybox.PersistedContent;
using Toybox.WatchUi;

//! The "what should be on this watch" screen.
//!
//! Opens by fetching the server's playlists — through the phone's Bluetooth
//! link, since Wi-Fi only comes up inside a sync — and offers them as a list
//! of toggles. Tick what you want, press Sync. The chosen ids are saved the
//! moment they are toggled, so backing out without syncing still leaves the
//! next automatic sync (on the charger, on Wi-Fi) knowing what to fetch.
//! Un-ticking takes the playlist off the watch there and then — deleting
//! needs no network — so freeing space never has to wait for a sync.
//!
//! "Pick songs" is the other way in: browse albums and tick single songs.
//!
//! If the phone is not there, the list from the last look is offered instead.
//! It may be stale, but a stale list a user can tick beats a screen that only
//! says "connect your phone" — and the sync itself re-reads the real list.
//!
//! Before any of that it asks the phone for its music settings, applied only
//! if they changed since the watch last took them (see TracksPairing). A watch
//! with no account says so, and START opens the account menu.
class ConfigureSyncView extends WatchUi.View {

    private var _status;      // what to show instead of the summary, or null
    private var _busy;        // a request is in flight
    private var _opened;      // the menu has been shown once from this view

    function initialize() {
        View.initialize();
        _status = null;
        _busy = false;
        _opened = false;
    }

    function onShow() {
        if (TracksPairing.consumeChanged()) {
            // The account changed or went while something stacked on this
            // screen was open. What this screen showed was the old account's;
            // start over as if just opened.
            _opened = false;
            _status = null;
        }
        if (_busy || _opened) {
            // Back from the menu: show the summary, wait for a press. Reopening
            // the menu from here would push it again every time it was popped.
            return;
        }
        if (!TracksConfig.isConfigured()) {
            beginPairing();
        } else {
            refreshConfig();
        }
    }

    //! Already configured, but ask the phone again anyway — quietly, and
    //! without depending on an answer. This is how a playlist selection made
    //! in the Tracks app, or a changed password, reaches the watch as soon as
    //! it is opened. One Bluetooth round trip; if the
    //! phone is not there the stored configuration stands and the screen
    //! carries on.
    function refreshConfig() {
        _busy = true;
        _status = WatchUi.loadResource(Rez.Strings.Pairing);
        WatchUi.requestUpdate();
        TracksPairing.pull(method(:onConfigRefreshed));
    }

    function onConfigRefreshed(outcome) as Void {
        _busy = false;
        TracksPairing.consumeChanged();
        if (!TracksConfig.isConfigured()) {
            // The phone's music server was removed since the watch last
            // heard, and the watch has signed out with it.
            _status = WatchUi.loadResource(Rez.Strings.NotSignedIn);
            WatchUi.requestUpdate();
            return;
        }
        loadPlaylists();
    }

    //! Ask the phone for this watch's configuration.
    //!
    //! An instance method bound with `method(:…)`, on purpose. A static one
    //! bound with `new Lang.Method(ClassName, :symbol)` is exactly the shape
    //! that fails at invocation time with "Failed invoking <symbol>" and no app
    //! frames, and it is not worth finding out on a watch whether it does.
    function beginPairing() {
        _busy = true;
        _status = WatchUi.loadResource(Rez.Strings.Pairing);
        WatchUi.requestUpdate();
        TracksPairing.fetch(method(:onConfigReceived));
    }

    //! Revision rules apply here too: a watch that was signed out on purpose
    //! stays signed out until the phone's settings change, or the user asks
    //! for them from the account menu.
    function onConfigReceived(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator) as Void {
        _busy = false;
        if (responseCode == 200) {
            TracksPairing.apply(data, false);
        }
        TracksPairing.consumeChanged();
        if (TracksConfig.isConfigured()) {
            loadPlaylists();
            return;
        }
        if (responseCode == 200 && data instanceof Lang.Dictionary && data["server"] == false) {
            // The phone answered but has nothing to give: Tracks is installed
            // and connected, the user just has not set a music server up yet.
            _status = WatchUi.loadResource(Rez.Strings.PairNotReady);
        } else if (responseCode == 200) {
            _status = WatchUi.loadResource(Rez.Strings.NotSignedIn);
        } else {
            _status = WatchUi.loadResource(Rez.Strings.PairFailed);
        }
        WatchUi.requestUpdate();
    }

    //! Sign in (a login lasts for this run of the app), then list.
    function loadPlaylists() {
        _busy = true;
        if (Navidrome.isLoggedIn()) {
            onLogin(true);
            return;
        }
        _status = WatchUi.loadResource(Rez.Strings.LoggingIn);
        WatchUi.requestUpdate();
        Navidrome.login(method(:onLogin));
    }

    function onLogin(ok) as Void {
        if (!ok) {
            onPlaylists(null);
            return;
        }
        _status = WatchUi.loadResource(Rez.Strings.Loading);
        WatchUi.requestUpdate();
        new NavidromeList(Navidrome.playlistsPath(), Navidrome.PLAYLIST_FIELDS, null, null).run(method(:onPlaylists));
    }

    function onPlaylists(rows) as Void {
        _busy = false;
        if (rows != null) {
            var list = TracksLibrary.builtins();
            list.addAll(rows);
            TracksLibrary.savePlaylists(list);
            _status = null;
            openMenu();
            return;
        }

        if (Navidrome.lastError == :auth) {
            _status = WatchUi.loadResource(Rez.Strings.ErrAuth);
        } else if (TracksLibrary.playlists().size() > 0) {
            // Offline, but not empty-handed.
            _status = null;
            openMenu();
            return;
        } else {
            _status = WatchUi.loadResource(Rez.Strings.ErrNoPhone);
        }
        WatchUi.requestUpdate();
    }

    function openMenu() {
        _opened = true;
        WatchUi.pushView(PlaylistMenu.build(), new PlaylistMenuDelegate(), WatchUi.SLIDE_UP);
    }

    //! What Select does from here: retry whatever failed, or reopen the menu.
    function onSelect() {
        if (_busy) {
            return;
        }
        if (!TracksConfig.isConfigured()) {
            // Nothing to list without an account; the way to one is here.
            AccountMenu.open(false);
        } else if (_opened || TracksLibrary.playlists().size() > 0) {
            openMenu();
        } else {
            loadPlaylists();
        }
    }

    function onUpdate(dc) {
        var headline = WatchUi.loadResource(Rez.Strings.AppName);
        var detail;
        if (!_busy && !TracksConfig.isConfigured()) {
            headline = (_status == null) ? WatchUi.loadResource(Rez.Strings.NotSignedIn) : _status;
            detail = WatchUi.loadResource(Rez.Strings.PressForAccount);
        } else if (_status != null) {
            detail = _status;
        } else {
            var count = TracksLibrary.selected().size();
            headline = Lang.format(WatchUi.loadResource(Rez.Strings.PlaylistsChosen), [count]);
            detail = WatchUi.loadResource(Rez.Strings.PressToChange);
        }
        TracksScreen.draw(dc, headline, detail);
    }
}

class ConfigureSyncDelegate extends WatchUi.BehaviorDelegate {

    private var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() {
        _view.onSelect();
        return true;
    }

    //! Holding the menu button leaves the app, the same exit as BACK — back to
    //! Music Providers, where a different provider can be chosen.
    function onMenu() {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        return true;
    }
}

//! The list of toggles.
module PlaylistMenu {

    //! Choices first, sync last — in the order the job is actually done.
    //!
    //! Sync used to lead the menu, on the reasoning that it is the urgent item.
    //! On a watch that someone has just set up it is the opposite: the first
    //! thing offered is "download", before anything has been chosen to
    //! download, which reads as the only thing to press and syncs nothing.
    //! Pick something, then send it.
    function build() {
        var menu = new WatchUi.Menu2({ :title => WatchUi.loadResource(Rez.Strings.Download) });
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.PickSongs), null, :pick, {}
        ));

        var list = TracksLibrary.playlists();
        var onWatch = WatchUi.loadResource(Rez.Strings.OnWatch);
        for (var i = 0; i < list.size(); i++) {
            var id = list[i][0];
            var count = list[i][2];
            var here = TracksLibrary.refsFor(TracksLibrary.songsOf(id)).size();
            // What is on the watch when ticked; the server's count when not.
            var enabled = Lang.format(WatchUi.loadResource(Rez.Strings.SongCount), [here]) + " " + onWatch;
            var disabled = (count == null)
                ? null
                : Lang.format(WatchUi.loadResource(Rez.Strings.SongCount), [count]);
            menu.addItem(new WatchUi.ToggleMenuItem(
                list[i][1],
                { :enabled => enabled, :disabled => disabled },
                id,
                TracksLibrary.isSelected(id),
                {}
            ));
        }

        // Last, under the choices it acts on. A selection is saved the moment
        // it is toggled, so scrolling past everything to reach this is the
        // gesture that says "that is my list, fetch it".
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SyncNow),
            WatchUi.loadResource(Rez.Strings.SyncNowHint), :sync, {}
        ));

        // Below sync: rarely wanted, and a wrong press here costs nothing —
        // everything that deletes asks first.
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.Account), AccountMenu.summary(), :account, {}
        ));
        return menu;
    }
}

class PlaylistMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :sync) {
            // Mark it as asked for, so it overtakes the once-a-day rule the
            // automatic sync follows — otherwise pressing this on the same day
            // as the last sync would appear to do nothing at all.
            TracksLibrary.setSyncRequested(true);
            // No popView first, deliberately. startSync() exits the app into
            // sync mode on its own; popping a view immediately before it queues
            // that pop against the system's "Connecting" sync view instead, and
            // the Wi-Fi bring-up dies the moment it starts — onStartSync never
            // runs and the watch sits forever "searching for network". Just ask
            // for the sync and let the system take the screen.
            Communications.startSync();
            return;
        }
        if (id == :pick) {
            PickSongs.openAlbums();
            return;
        }
        if (id == :account) {
            AccountMenu.open(true);
            return;
        }
        // A ToggleMenuItem has already flipped itself by the time this runs;
        // isEnabled() is the new state. On: saved straight away, so that
        // backing out, or the next automatic sync, sees it. Off: the playlist
        // comes off the watch now.
        var toggle = item as WatchUi.ToggleMenuItem;
        if (toggle.isEnabled()) {
            TracksLibrary.setSelectedFlag(id, true);
        } else {
            TracksLibrary.purgePlaylist(id);
            toggle.setSubLabel(subLabelFor(id));
        }
    }

    private function subLabelFor(id) {
        var list = TracksLibrary.playlists();
        for (var i = 0; i < list.size(); i++) {
            if (list[i][0].equals(id) && list[i][2] != null) {
                return Lang.format(WatchUi.loadResource(Rez.Strings.SongCount), [list[i][2]]);
            }
        }
        return null;
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}
