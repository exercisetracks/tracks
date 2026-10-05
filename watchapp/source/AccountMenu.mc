// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Lang;
using Toybox.PersistedContent;
using Toybox.WatchUi;

//! Who this watch is signed in as, and the three ways to change it.
//!
//! Normally the phone decides (see TracksPairing) and nobody needs this
//! screen. It exists for the other cases: a watch meant to use a different
//! account from the phone's, a phone that is not around, or someone who wants
//! the downloads gone.
//!
//!   Sign in from phone   take the phone's account now, whatever its revision
//!   Sign in on watch     type a server, user and password on the watch
//!   Log out              forget the account, and delete its music
//!
//! Every change of account deletes the music downloaded for the old one: the
//! library is that account's, not the watch's.
module AccountMenu {

    var _menu = null;
    var _underList = false;

    //! `underList` says the download menu is beneath this one. That menu was
    //! built for the old account, so if the account changes it is closed on the
    //! way out rather than left offering the old account's playlists.
    function open(underList) {
        _underList = underList;
        _menu = new WatchUi.Menu2({ :title => WatchUi.loadResource(Rez.Strings.Account) });
        _menu.addItem(new WatchUi.MenuItem(who(), where(), :who, {}));
        _menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SignInPhone), null, :phone, {}
        ));
        _menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SignInWatch), null, :watch, {}
        ));
        _menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.LogOut), null, :logout, {}
        ));
        WatchUi.pushView(_menu, new AccountMenuDelegate(), WatchUi.SLIDE_LEFT);
    }

    //! Bring the first row up to date after a change made from a screen
    //! stacked on top of this one.
    function refresh() {
        if (_menu == null) {
            return;
        }
        var item = _menu.getItem(0);
        if (item != null) {
            item.setLabel(who());
            item.setSubLabel(where());
            _menu.updateItem(item, 0);
        }
    }

    //! The user name, or "Not signed in".
    function who() {
        var cfg = TracksConfig.load();
        return (cfg == null) ? WatchUi.loadResource(Rez.Strings.NotSignedIn) : cfg["user"];
    }

    //! The server's host, or nothing.
    function where() {
        var cfg = TracksConfig.load();
        return (cfg == null) ? null : TracksConfig.host(cfg["url"]);
    }

    //! "user @ host", for the download menu's Account row.
    function summary() {
        var cfg = TracksConfig.load();
        return (cfg == null) ? who() : cfg["user"] + " @ " + TracksConfig.host(cfg["url"]);
    }

    function underList() {
        return _underList;
    }

    function close() {
        _menu = null;
    }
}

class AccountMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :phone) {
            var view = new SignInView(:phone);
            WatchUi.pushView(view, new SignInDelegate(view), WatchUi.SLIDE_LEFT);
        } else if (id == :watch) {
            var view = new SignInView(:url);
            WatchUi.pushView(view, new SignInDelegate(view), WatchUi.SLIDE_LEFT);
        } else if (id == :logout && TracksConfig.isConfigured()) {
            // Asked first: this deletes every downloaded song, and a sync to
            // get them back is hours on a watch's Wi-Fi.
            WatchUi.pushView(
                new WatchUi.Confirmation(WatchUi.loadResource(Rez.Strings.LogOutConfirm)),
                new LogOutDelegate(),
                WatchUi.SLIDE_IMMEDIATE
            );
        }
    }

    function onBack() {
        var changed = TracksPairing.changed;
        AccountMenu.close();
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        if (changed && AccountMenu.underList()) {
            // The download menu underneath lists the old account's playlists.
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        }
    }
}

class LogOutDelegate extends WatchUi.ConfirmationDelegate {

    function initialize() {
        ConfirmationDelegate.initialize();
    }

    function onResponse(response) {
        if (response == WatchUi.CONFIRM_YES) {
            TracksPairing.signOut();
            AccountMenu.refresh();
        }
        return true;
    }
}

//! One screen for both ways of signing in, because both are a short run of
//! steps that ends in the same place: signed in, or told why not, with the
//! old account untouched.
//!
//! From the phone:  :phone → result
//! On the watch:    :url → :user → :password → :checking → result
//!
//! Each typing step opens the system keyboard (WatchUi.TextPicker) by itself
//! the first time it is shown, and this screen underneath says what the step
//! is for — the keyboard has no room for a label, and "which of these am I
//! typing" is not a question to leave to memory. Backing out of the keyboard
//! lands here, where START opens it again and BACK abandons the whole thing.
class SignInView extends WatchUi.View {

    private var _step;
    private var _asked;        // the keyboard has been opened for this step
    private var _url;
    private var _user;
    private var _password;
    private var _headline;
    private var _detail;
    private var _retryOverWifi;

    function initialize(step) {
        View.initialize();
        _step = step;
        _asked = false;
        var cfg = TracksConfig.load();
        // Start from the current account: changing a password should not mean
        // retyping a server address on a watch.
        _url = (cfg == null) ? "https://" : cfg["url"];
        _user = (cfg == null) ? "" : cfg["user"];
        _password = "";
        _headline = null;
        _detail = null;
        _retryOverWifi = false;
    }

    function onShow() {
        if (_step == :phone && !_asked) {
            _asked = true;
            show(Rez.Strings.Pairing, null);
            TracksPairing.fetch(method(:onPhone));
            return;
        }
        if (isTyping() && !_asked) {
            openKeyboard();
        }
    }

    private function isTyping() {
        return _step == :url || _step == :user || _step == :password;
    }

    function openKeyboard() {
        _asked = true;
        var initial = (_step == :url) ? _url : (_step == :user) ? _user : "";
        WatchUi.pushView(new WatchUi.TextPicker(initial), new SignInTextDelegate(self), WatchUi.SLIDE_LEFT);
    }

    //! What the keyboard handed back for the current step.
    function entered(text) {
        if (_step == :url) {
            _url = TracksPairing.normalizeUrl(text);
            _step = :user;
        } else if (_step == :user) {
            _user = text;
            _step = :password;
        } else if (_step == :password) {
            _password = text;
            _step = :checking;
            check();
        }
        _asked = false;
        WatchUi.requestUpdate();
    }

    // ── from the phone ───────────────────────────────────────────────────

    function onPhone(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator) as Void {
        _step = :result;
        if (responseCode != 200) {
            show(Rez.Strings.PairFailed, Rez.Strings.OldAccountKept);
            return;
        }
        var outcome = TracksPairing.apply(data, true);
        if (outcome == :stored || outcome == :switched) {
            signedIn();
        } else if (outcome == :notArmed) {
            // The phone hands a password out only for a while after the user
            // did something there; opening its music screen is that something.
            show(Rez.Strings.OpenTracksMusic, Rez.Strings.ThenTryAgain);
        } else if (outcome == :noServer) {
            show(Rez.Strings.PairNotReady, Rez.Strings.OldAccountKept);
        } else {
            show(Rez.Strings.PairFailed, Rez.Strings.OldAccountKept);
        }
    }

    // ── on the watch ─────────────────────────────────────────────────────

    //! Log in with what was typed before storing any of it. A typo must not
    //! cost the account that was working, or its downloads.
    function check() {
        show(Rez.Strings.LoggingIn, null);
        Navidrome.loginAs(candidate(), method(:onChecked));
    }

    function candidate() {
        return { "url" => _url, "user" => _user, "password" => _password };
    }

    function onChecked(ok) as Void {
        _step = :result;
        if (ok) {
            TracksPairing.adoptTyped(candidate());
            signedIn();
            return;
        }
        var why = Navidrome.lastError;
        if (why == :auth) {
            show(Rez.Strings.ErrLoginRejected, Rez.Strings.OldAccountKept);
        } else if (why == :network) {
            // Off Wi-Fi, this login went through the phone, and the phone
            // relays requests to its own music server and nowhere else — so a
            // different server is unreachable from here by design, not by
            // fault. The watch's own Wi-Fi comes up only for a sync; offer one.
            _retryOverWifi = true;
            show(Rez.Strings.ErrNetwork, Rez.Strings.CheckOverWifi);
        } else {
            show(Rez.Strings.ErrServer, Rez.Strings.OldAccountKept);
        }
    }

    //! Verify the typed account inside a sync, where the watch's own Wi-Fi is
    //! up. The sync checks it before anything else and keeps it only if the
    //! server accepts it (TracksSyncDelegate.onStartSync).
    function checkOverWifi() {
        TracksConfig.setCandidate(candidate());
        TracksLibrary.setSyncRequested(true);
        // No popView first — see PlaylistMenuDelegate: startSync takes the
        // screen itself, and a pop queued just before it kills the Wi-Fi
        // bring-up.
        Communications.startSync();
    }

    // ── shared ───────────────────────────────────────────────────────────

    private function signedIn() {
        AccountMenu.refresh();
        show(Rez.Strings.SignedIn, AccountMenu.summary());
    }

    private function show(headline, detail) {
        _headline = (headline instanceof Lang.String) ? headline : WatchUi.loadResource(headline);
        _detail = (detail == null || detail instanceof Lang.String) ? detail : WatchUi.loadResource(detail);
        WatchUi.requestUpdate();
    }

    //! START: open the keyboard again, check over Wi-Fi, or close.
    function onSelect() {
        if (isTyping()) {
            openKeyboard();
        } else if (_step == :result && _retryOverWifi) {
            checkOverWifi();
        } else if (_step == :result) {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        }
    }

    function onUpdate(dc) {
        if (isTyping()) {
            var prompt = (_step == :url) ? Rez.Strings.ServerAddress
                : (_step == :user) ? Rez.Strings.Username : Rez.Strings.Password;
            TracksScreen.draw(dc, WatchUi.loadResource(prompt), WatchUi.loadResource(Rez.Strings.PressToType));
            return;
        }
        TracksScreen.draw(dc, (_headline == null) ? "" : _headline, _detail);
    }
}

class SignInDelegate extends WatchUi.BehaviorDelegate {

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

//! Hands the typed text to the sign-in screen. The keyboard is popped first,
//! as in search (SearchTextDelegate), so the screen underneath is on top
//! again when it moves to the next step and opens the next keyboard.
class SignInTextDelegate extends WatchUi.TextPickerDelegate {

    private var _view;

    function initialize(view) {
        TextPickerDelegate.initialize();
        _view = view;
    }

    function onTextEntered(text, changed) {
        if (text == null || text.length() == 0) {
            return false;
        }
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        _view.entered(text);
        return true;
    }

    function onCancel() {
        return false;
    }
}
