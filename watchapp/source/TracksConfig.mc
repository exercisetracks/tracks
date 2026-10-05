// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Application.Storage;
using Toybox.Lang;

//! Which music server this watch belongs to.
//!
//! Three strings, handed over by the Tracks phone app (see TracksPairing) or
//! typed on the watch (see AccountMenu), and kept in app storage:
//!
//!   url       the Navidrome server, no trailing slash
//!   user      the account name
//!   password  its password
//!
//! The password itself, because Navidrome's own API (which the lists need —
//! see Navidrome.mc) is only reachable through a login with it. It never goes
//! anywhere but that one server, over TLS, and a Navidrome account is worth
//! exactly one music library. Storage is the app's own sandbox, wiped when the
//! app is removed.
//!
//! Beside it, two revisions of the phone's music settings (see TracksPairing
//! for why there is a revision at all):
//!
//!   rev       the phone's revision this watch last applied
//!   revSeen   the newest revision the phone has reported, applied or not
module TracksConfig {

    const KEY = "cfg";
    const REVISION = "rev";
    const REVISION_SEEN = "revSeen";

    //! Stored in place of a config after a log out. Not simply deleted: an
    //! absent config falls back to devConfig(), so on a dev build a log out
    //! would sign straight back in to the baked-in server and could never be
    //! tested in the simulator.
    const SIGNED_OUT = false;

    function load() {
        var cfg = Storage.getValue(KEY);
        if (cfg == null) {
            cfg = devConfig();
        }
        return (cfg instanceof Lang.Dictionary) ? cfg : null;
    }

    function store(cfg) {
        Storage.setValue(KEY, cfg);
    }

    function signOut() {
        Storage.setValue(KEY, SIGNED_OUT);
    }

    function isConfigured() {
        return load() != null;
    }

    //! The server's host, for showing who this watch is signed in as — the
    //! full URL does not fit a line on a 280-pixel circle and says nothing the
    //! host does not.
    function host(url) {
        if (url == null) {
            return null;
        }
        var at = url.find("://");
        var rest = (at == null) ? url : url.substring(at + 3, url.length());
        var slash = rest.find("/");
        return (slash == null) ? rest : rest.substring(0, slash);
    }

    //! True when two configs name the same account — the same server and the
    //! same user. A changed password is the same account; its downloads stay.
    function sameAccount(a, b) {
        if (a == null || b == null) {
            return false;
        }
        return a["url"].equals(b["url"]) && a["user"].equals(b["user"]);
    }

    //! An account typed on the watch that could not be checked over the
    //! phone's link, waiting for a sync to check it over Wi-Fi. Not the
    //! account until the server has accepted it; see AccountMenu.
    const CANDIDATE = "cand";

    function candidate() {
        var cand = Storage.getValue(CANDIDATE);
        return (cand instanceof Lang.Dictionary) ? cand : null;
    }

    function setCandidate(cfg) {
        if (cfg == null) {
            Storage.deleteValue(CANDIDATE);
        } else {
            Storage.setValue(CANDIDATE, cfg);
        }
    }

    // ── revisions ────────────────────────────────────────────────────────

    function appliedRevision() {
        return Storage.getValue(REVISION);
    }

    //! True when the phone's `revision` is one this watch has not applied.
    //! A watch that has never applied one takes the first it hears. A missing
    //! revision reads as 0: an older phone app, whose answer still pairs a
    //! watch that has nothing, and never overrides one that has something.
    function isNewer(revision) {
        var rev = (revision == null) ? 0 : revision;
        var applied = appliedRevision();
        return applied == null || rev > applied;
    }

    function setAppliedRevision(revision) {
        Storage.setValue(REVISION, (revision == null) ? 0 : revision);
    }

    function seenRevision() {
        return Storage.getValue(REVISION_SEEN);
    }

    function noteSeenRevision(revision) {
        if (revision == null) {
            return;
        }
        var seen = seenRevision();
        if (seen == null || revision > seen) {
            Storage.setValue(REVISION_SEEN, revision);
        }
    }

    //! A build-time configuration for the simulator, which has no phone to pair
    //! with. Compiled in only by `build.sh --dev`; every other build gets these
    //! stubs. See build.sh for where the values come from.
    //!
    //! `:nodev` rather than the compiler's own `:release`: that one is stripped
    //! from every build made without `-r`, which is all of them here.
    (:nodev)
    function devConfig() {
        return null;
    }

    //! A dev build may point the phone's config request at a URL of its own —
    //! a local stand-in for the phone — so revisions and log out can be
    //! exercised in the simulator. Null: the real one.
    (:nodev)
    function devConfigUrl() {
        return null;
    }

    //! Playlists a dev build starts out with selected, so a sync has something
    //! to do before anyone has touched the menu.
    (:nodev)
    function devPlaylists() {
        return null;
    }

    (:nodev)
    function devGeneration() {
        return null;
    }

    //! A dev build may name a playlist to start playing on launch instead of
    //! syncing — the playback menu is only reachable through the GUI.
    (:nodev)
    function devPlay() {
        return null;
    }

    //! A dev build may carry a probe (see build.sh) that runs instead of the
    //! app's normal start. True when it took over.
    (:nodev)
    function devProbe() {
        return false;
    }
}
