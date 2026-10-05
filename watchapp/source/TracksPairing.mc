// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Communications;
using Toybox.Lang;
using Toybox.PersistedContent;
using Toybox.System;

//! How this watch learns which music server it belongs to.
//!
//! The app is side-loaded straight from the Tracks phone app over Bluetooth, so
//! at first run it knows nothing. Rather than make the user type a URL and a
//! credential on a watch, it asks the phone.
//!
//! Connect IQ lets a watch app ask the paired phone to fetch a URL. Tracks
//! answers exactly one — this one — from values it already holds. The host is
//! a reserved `.invalid` name, so there is no server anywhere that this could
//! accidentally reach: either the Tracks app is on the other end of the
//! Bluetooth link and answers, or the request fails.
//!
//! This runs over Bluetooth rather than Wi-Fi because it has to work before
//! anything is configured, and Wi-Fi only comes up inside a sync.
//!
//! ## When it asks
//!
//! Often, because a change made on the phone should not wait for someone to
//! open a particular screen: every time the app opens, and before every sync.
//! With no phone in reach the request fails, quietly, and the stored
//! configuration stands.
//!
//! Not from a background event. A five-minute temporal event was built and
//! taken out again: a Bluetooth round trip every five minutes, all day, is
//! battery spent to learn — nearly every time — that nothing changed, on a
//! watch whose app is opened, and synced, whenever the music matters.
//!
//! ## Why a revision
//!
//! Asking that often would be a problem if every answer were obeyed: an
//! account typed on the watch would be overwritten by the phone's the next
//! time the app opened. So the phone numbers its music settings — the number
//! moves when a server is connected, changed or removed, or a selection is
//! sent — and the watch applies an answer only when its revision is newer than the last one
//! it applied. The phone's settings reach the watch when they *change*, and
//! not otherwise. "Sign in from phone" is the one way to take the phone's
//! current answer regardless.
module TracksPairing {

    const CONFIG_URL = "https://tracks.invalid/ciq/config";

    //! Set when the account changed (or went) while the app was open, so
    //! screens built for the old account know to rebuild themselves.
    var changed = false;

    //! Ask the phone for this watch's configuration.
    //! The callback is a standard makeWebRequest responseCallback.
    function fetch(callback) {
        var url = TracksConfig.devConfigUrl();
        Communications.makeWebRequest(
            (url == null) ? CONFIG_URL : url,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            callback
        );
    }

    //! Ask the phone now and apply what it says, subject to the revision.
    //! `callback`, when given, gets the outcome symbol from apply(), or :none
    //! when the phone did not answer. The callback rides in the request's
    //! context rather than a module variable, so two pulls in flight — the
    //! playback and download screens both ask on open — cannot swap answers.
    function pull(callback) {
        var url = TracksConfig.devConfigUrl();
        Communications.makeWebRequest(
            (url == null) ? CONFIG_URL : url,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => (callback == null) ? false : callback
            },
            new Lang.Method(TracksPairing, :onPulled)
        );
    }

    function onPulled(responseCode as Lang.Number, data as Null or Lang.Dictionary or Lang.String or PersistedContent.Iterator, callback as Lang.Object) as Void {
        var outcome = (responseCode == 200) ? apply(data, false) : :none;
        if (callback instanceof Lang.Method) {
            (callback as Lang.Method).invoke(outcome);
        }
    }

    //! Apply one answer from the phone. Returns what happened:
    //!
    //!   :none       nothing to do — not newer, or nothing usable in it
    //!   :stored     same account; credentials and choices brought up to date
    //!   :switched   a different account; the old one's music was deleted
    //!   :signedOut  the phone has no music server any more; so neither does this
    //!   :noServer   (forced only) the phone has no music server to give
    //!   :notArmed   (forced only) the phone has one but is not handing it out
    //!
    //! The answer:
    //!
    //!   { "revision": n, "server": true|false,
    //!     "url": …, "user": …, "password": …,
    //!     "playlists": [id, …], "songs": [[songId, albumId], …] }
    //!
    //! The credentials are only there while the phone is armed (see
    //! WatchAppConfig on the phone: it hands a password out for a short while
    //! after the user did something on the phone, and otherwise says only
    //! which revision it is on). `playlists` and `songs` are optional: choices
    //! made in the phone app. Playlists replace the watch's tick list; songs
    //! are added to the picked list. Left out, the watch's own choice stands.
    //!
    //! `force` applies the answer whatever its revision — the user asked for
    //! the phone's account — and never signs out: someone asking to be signed
    //! in is told there is nothing to sign in to instead.
    function apply(data, force) {
        var outcome = decide(data, force);
        System.println("pairing: revision " + ((data instanceof Lang.Dictionary) ? data["revision"] : null)
            + (force ? " (asked for)" : "") + " -> " + outcome);
        return outcome;
    }

    function decide(data, force) {
        if (!(data instanceof Lang.Dictionary)) {
            return :none;
        }
        var revision = data["revision"];
        TracksConfig.noteSeenRevision(revision);
        if (!force && !TracksConfig.isNewer(revision)) {
            return :none;
        }

        if (data["server"] == false) {
            if (force) {
                return :noServer;
            }
            TracksConfig.setAppliedRevision(revision);
            if (!TracksConfig.isConfigured()) {
                return :none;
            }
            signOut();
            return :signedOut;
        }

        var cfg = credentials(data);
        if (cfg == null) {
            // The phone has a server but is not handing it out right now.
            // Deliberately not marked applied: the change behind this revision
            // has not reached the watch yet, and the next armed answer must
            // still count as new.
            return force ? :notArmed : :none;
        }
        var outcome = adopt(cfg);
        TracksConfig.setAppliedRevision(revision);

        var playlists = data["playlists"];
        if (playlists != null) {
            TracksLibrary.setSelected(playlists);
        }
        var songs = data["songs"];
        if (songs != null) {
            TracksLibrary.pickSongs(songs);
        }
        return outcome;
    }

    //! Make `cfg` this watch's account. A different account means different
    //! music: what was downloaded for the old one is deleted first, because it
    //! is that account's library, not this one's — and a sync would otherwise
    //! spend the next run discovering none of it is wanted.
    function adopt(cfg) {
        var old = TracksConfig.load();
        var switched = old != null && !TracksConfig.sameAccount(old, cfg);
        if (switched) {
            TracksLibrary.wipe();
            changed = true;
        } else if (old == null) {
            changed = true;
        }
        TracksConfig.store(cfg);
        // Whatever session there was belongs to the old credentials.
        Navidrome.logout();
        return switched ? :switched : :stored;
    }

    //! An account typed on the watch and verified against the server.
    //!
    //! It takes the place of whatever the phone last said, so the revision the
    //! phone is on now counts as applied: the next poll must not put the
    //! phone's account straight back. A later change on the phone still wins.
    function adoptTyped(cfg) {
        var outcome = adopt(cfg);
        var seen = TracksConfig.seenRevision();
        if (seen != null && TracksConfig.isNewer(seen)) {
            TracksConfig.setAppliedRevision(seen);
        }
        return outcome;
    }

    //! Forget the account and everything downloaded for it.
    function signOut() {
        TracksLibrary.wipe();
        TracksConfig.signOut();
        Navidrome.logout();
        changed = true;
    }

    //! True once after the account changed; for screens deciding whether to
    //! rebuild.
    function consumeChanged() {
        var was = changed;
        changed = false;
        return was;
    }

    //! The usable config in an answer, or null.
    function credentials(data) {
        var url = text(data["url"]);
        var user = text(data["user"]);
        var password = text(data["password"]);
        if (url == null || user == null || password == null) {
            return null;
        }
        return { "url" => normalizeUrl(url), "user" => user, "password" => password };
    }

    //! A server address as typed or sent, made into what the requests expect.
    //!
    //! No trailing slash: it would produce "//rest/…", which some reverse
    //! proxies redirect and Connect IQ will not follow. And https:// when no
    //! scheme was typed, because Connect IQ will not talk to anything else.
    function normalizeUrl(url) {
        if (url.find("://") == null) {
            url = "https://" + url;
        }
        while (url.length() > 0 && url.substring(url.length() - 1, url.length()).equals("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    //! A non-empty string, or null for anything else.
    function text(value) as Lang.String or Null {
        if (value == null || !(value instanceof Lang.String) || value.length() == 0) {
            return null;
        }
        return value;
    }
}
