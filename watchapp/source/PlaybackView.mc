// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Lang;
using Toybox.Media;
using Toybox.WatchUi;

//! The "what do you want to hear" screen.
//!
//! Lists what is actually on the watch — every playlist with at least one
//! downloaded song, plus everything at once — and hands the choice to the
//! player. Nothing here touches the network: the playback side of an audio
//! provider has to work in the middle of a run with the phone at home.
//!
//! When there is nothing to list, the only useful offer is to go and get
//! some. This is the screen the watch actually opens from Music Providers, so
//! it — not the sync-configuration view — has to be the way in to choosing and
//! downloading playlists: START from here opens that flow (ConfigureSyncView).
//! BACK, at any point, returns to the watch's Music Providers list, which is
//! how you switch to a different provider.
class PlaybackView extends WatchUi.View {

    private var _opened;
    private var _passing;   // handing straight on; do not draw a summary
    private var _count;     // songs held, counted on show rather than per frame

    function initialize() {
        View.initialize();
        _opened = false;
        _passing = false;
        _count = null;
    }

    //! Go straight where the user was going.
    //!
    //! Both of the screens this used to stop on existed only to say "press
    //! START": one counting the songs already downloaded, one reporting that
    //! there were none. Neither asked a question, so neither earned a press.
    //! With music, open the list; without, open the flow that goes and gets
    //! some — which starts by talking to the phone and the server, so the
    //! first thing the user sees is progress rather than a prompt.
    //!
    //! Once only. Backing out of what this opens lands here again, and that
    //! time the screen stands: leaving is a decision, and re-opening what
    //! someone just left is a trap.
    function onShow() {
        // Counted here, once. onUpdate runs on every redraw, and counting
        // there meant walking the whole media store per frame to draw a number
        // that only changes when a sync does.
        _count = TracksLibrary.allRefs().size();
        if (_opened) {
            // Back here from whatever was opened, and staying this time. Stop
            // suppressing the summary, or the screen would sit blank forever
            // having passed through once.
            _passing = false;
            WatchUi.requestUpdate();
            return;
        }
        if (TracksLibrary.playAfterSync() && _count > 0) {
            // A sync the user asked for has just finished. Go to the music,
            // not to another list — they pressed sync to listen to something.
            // Done here rather than as the sync ends: navigating out of a sync
            // as it completes is what once left the whole thing wedged.
            TracksLibrary.clearPlayAfterSync();
            _opened = true;
            _passing = true;
            Media.startPlayback(null);
            return;
        }
        _passing = true;
        if (_count > 0) {
            openMenu();
        } else {
            _opened = true;
            openDownload();
        }
    }

    function openMenu() {
        _opened = true;
        WatchUi.pushView(PlayMenu.build(), new PlayMenuDelegate(), WatchUi.SLIDE_UP);
    }

    function onSelect() {
        if (TracksLibrary.allRefs().size() > 0) {
            openMenu();
        } else {
            // Nothing downloaded yet: the useful thing is to choose and fetch
            // it, which is the download view's whole job.
            openDownload();
        }
    }

    //! Open the choose-and-download flow. Shared by the empty-state START and
    //! the "Get music" item in the play menu, so there is one way to reach it.
    static function openDownload() {
        var view = new ConfigureSyncView();
        WatchUi.pushView(view, new ConfigureSyncDelegate(view), WatchUi.SLIDE_UP);
    }

    function onUpdate(dc) {
        // While handing straight on to the menu, this view is on screen for a
        // frame or two — long enough to flash a song count at someone who
        // asked for the library and never wanted a summary.
        if (_passing) {
            TracksScreen.draw(dc, WatchUi.loadResource(Rez.Strings.AppName), null);
            return;
        }
        var count = (_count == null) ? TracksLibrary.allRefs().size() : _count;
        var headline = (count == 0)
            ? WatchUi.loadResource(Rez.Strings.NoMusic)
            : Lang.format(WatchUi.loadResource(Rez.Strings.SongsReady), [count]);
        var detail = (count == 0)
            ? WatchUi.loadResource(Rez.Strings.AddMusicHint)
            : WatchUi.loadResource(Rez.Strings.PressToChoose);
        TracksScreen.draw(dc, headline, detail);
    }
}

class PlaybackDelegate extends WatchUi.BehaviorDelegate {

    private var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() {
        _view.onSelect();
        return true;
    }

    //! Holding the menu button leaves the app the same way BACK does —
    //! returning to the watch's Music Providers list, which is where you
    //! switch to another provider.
    function onMenu() {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        return true;
    }
}

module PlayMenu {

    function build() {
        var menu = new WatchUi.Menu2({ :title => WatchUi.loadResource(Rez.Strings.Play) });
        var songs = WatchUi.loadResource(Rez.Strings.SongCount);

        // Always first: the way to add or re-sync music without leaving.
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.GetMusic), null, :get, {}
        ));
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.AllMusic),
            Lang.format(songs, [TracksLibrary.allRefs().size()]),
            :all,
            {}
        ));

        var list = TracksLibrary.downloaded();
        for (var i = 0; i < list.size(); i++) {
            menu.addItem(new WatchUi.MenuItem(
                list[i][1], Lang.format(songs, [list[i][2]]), list[i][0], {}
            ));
        }
        return menu;
    }
}

class PlayMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    //! `null` means everything; a playlist id means that playlist. Either way
    //! the system relaunches this app in playback mode and hands the value
    //! straight back to getContentDelegate — see TracksContentDelegate.
    function onSelect(item) {
        var id = item.getId();
        if (id == :get) {
            PlaybackView.openDownload();
            return;
        }
        Media.startPlayback(id == :all ? null : id);
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}
