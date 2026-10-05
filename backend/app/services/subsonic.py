# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Subsonic API client — the protocol Navidrome speaks.

Targeting the Subsonic API rather than Navidrome specifically is deliberate:
it is what Navidrome, Gonic, Airsonic and Ampache all implement, so one client
covers whichever self-hosted server a user already runs. Someone self-hosting
Tracks very likely already self-hosts their music.

Auth is the salted-token scheme (`t = md5(password + salt)`, fresh salt per
request) rather than sending the password. It is not strong — the server stores
a recoverable password for it to work at all — but it is the scheme every
Subsonic server accepts, and it keeps the password out of URLs and access logs.

Errors arrive as HTTP 200 with a failure body, so every response goes through
`_unwrap`, which turns that into an exception instead of letting a caller treat
an error payload as data.
"""

from __future__ import annotations

import hashlib
import logging
import secrets
from dataclasses import dataclass
from typing import Any, Iterator
from urllib.parse import urlsplit

import httpx

log = logging.getLogger(__name__)

# The client identifier every request carries, and the protocol level we ask
# for. 1.16.1 is what Navidrome advertises; getStarred2/getAlbumList2 need it.
CLIENT_NAME = "Tracks"
API_VERSION = "1.16.1"

_TIMEOUT = 30.0
_STREAM_TIMEOUT = 300.0

# How many hops a music server may bounce us through before we assume something
# is wrong. Real servers redirect at most once (an http->https upgrade).
_MAX_REDIRECTS = 3


class SubsonicError(RuntimeError):
    """The server refused, or could not be reached."""


def _same_service(configured: str, hop: str) -> bool:
    """Is `hop` still the music server the user configured?

    Host must match exactly. Scheme may only improve: a server upgrading
    http->https is the ordinary case and refusing it would push working
    deployments onto cleartext, while https->http is a downgrade nobody
    legitimate asks for.
    """
    a, b = urlsplit(configured), urlsplit(hop)
    if a.hostname != b.hostname:
        return False
    if a.scheme == "https" and b.scheme == "http":
        return False
    if a.scheme == b.scheme:
        # Normalise before comparing, or `https://host` and `https://host:443`
        # — the same place — would read as two different origins.
        return _port(a) == _port(b)
    # An http->https upgrade, which only counts as one when neither side pins a
    # port. A redirect that also moves to some other port is not an upgrade.
    return a.port is None and b.port is None


def _port(parts) -> int | None:
    """The effective port: explicit if given, otherwise the scheme's default."""
    if parts.port is not None:
        return parts.port
    return {"http": 80, "https": 443}.get(parts.scheme)


def _check_redirects(base_url: str, resp: httpx.Response) -> None:
    """Refuse a response that was redirected off the configured server.

    The base URL is user-supplied, so a music server (or anything able to answer
    as one over plain HTTP) can reply with a Location pointing anywhere — and
    without this, httpx would follow it and hand the body back to the caller.
    That turns "connect my music server" into a read/write window onto whatever
    the backend can reach but the user cannot: the other containers on the
    Docker network, and anything else on the host's LAN.

    Checked after the fact rather than per hop because httpx exposes the chain
    on `resp.history` and offers no per-hop veto. So the off-origin request is
    still *made* — what this prevents is its response ever being read, which is
    the half that carries data back. The same allow-list-every-hop rule is
    applied on the phone's watch proxy; see WatchHttpProxy.

    How many hops is capped by `max_redirects` on the client (see `_client`),
    so this only has to judge where each one went.
    """
    for hop in list(resp.history) + [resp]:
        if not _same_service(base_url, str(hop.url)):
            log.warning(
                "music server redirected off its own origin (%s -> %s); refusing",
                urlsplit(base_url).hostname, urlsplit(str(hop.url)).hostname,
            )
            raise SubsonicError(
                "The music server redirected somewhere else — refusing to follow it"
            )


#: Where requests go. None is the network; tests put an httpx.MockTransport
#: here, so they run through real httpx and a bad argument fails them.
_transport: httpx.BaseTransport | None = None


def _client(timeout) -> httpx.Client:
    """A client for one call. `max_redirects` is a Client setting — the
    module-level `httpx.get` rejects it, which once took every music request
    down with a TypeError while tests that faked `httpx.get` stayed green."""
    return httpx.Client(timeout=timeout, follow_redirects=True,
                        max_redirects=_MAX_REDIRECTS, transport=_transport)


@dataclass(frozen=True)
class SubsonicSong:
    id: str
    title: str
    artist: str | None
    album: str | None
    duration_s: float | None
    track_no: int | None
    suffix: str | None
    size: int | None
    # The album's id, which is also its cover art's. The watch app keys its
    # art on this, one image per album rather than per song.
    album_id: str | None = None

    @classmethod
    def from_json(cls, raw: dict) -> "SubsonicSong":
        def _int(v):
            try:
                return int(v)
            except (TypeError, ValueError):
                return None

        return cls(
            id=str(raw.get("id")),
            title=raw.get("title") or "Untitled",
            artist=raw.get("artist"),
            album=raw.get("album"),
            duration_s=float(raw["duration"]) if raw.get("duration") is not None else None,
            track_no=_int(raw.get("track")),
            suffix=raw.get("suffix"),
            size=_int(raw.get("size")),
            album_id=raw.get("albumId"),
        )


@dataclass(frozen=True)
class SubsonicPlaylist:
    id: str
    name: str
    song_count: int
    duration_s: float | None


class SubsonicClient:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password

    # ── plumbing ─────────────────────────────────────────────────────────────

    def _auth_params(self) -> dict[str, str]:
        salt = secrets.token_hex(8)
        token = hashlib.md5(f"{self.password}{salt}".encode()).hexdigest()
        return {
            "u": self.username,
            "t": token,
            "s": salt,
            "v": API_VERSION,
            "c": CLIENT_NAME,
            "f": "json",
        }

    def _url(self, method: str) -> str:
        # The ".view" suffix is the traditional spelling and is accepted by
        # every implementation; the bare form is not.
        return f"{self.base_url}/rest/{method}.view"

    def _get(self, method: str, params: dict[str, Any] | None = None) -> dict:
        query = {**self._auth_params(), **(params or {})}
        try:
            with _client(_TIMEOUT) as http:
                resp = http.get(self._url(method), params=query)
        except httpx.HTTPError as exc:
            raise SubsonicError(f"Could not reach the music server: {exc}") from exc
        _check_redirects(self.base_url, resp)
        return self._unwrap(resp, method)

    @staticmethod
    def _unwrap(resp: httpx.Response, method: str) -> dict:
        """Subsonic reports failure with HTTP 200 and an error body, so a naive
        caller would read an error as data. Everything funnels through here."""
        if resp.status_code >= 400:
            raise SubsonicError(f"Music server returned HTTP {resp.status_code}")
        try:
            body = resp.json().get("subsonic-response") or {}
        except ValueError as exc:
            raise SubsonicError("Music server did not return JSON — is that a Subsonic URL?") from exc

        if body.get("status") != "ok":
            error = body.get("error") or {}
            message = error.get("message") or "unknown error"
            code = error.get("code")
            if code in (40, 41):
                raise SubsonicError("Music server rejected the username or password")
            raise SubsonicError(f"Music server refused {method}: {message}")
        return body

    # ── reads ────────────────────────────────────────────────────────────────

    def ping(self) -> str | None:
        """Verify URL + credentials. Returns the server's reported type/version."""
        body = self._get("ping")
        return body.get("type") or body.get("serverVersion")

    def playlists(self) -> list[SubsonicPlaylist]:
        body = self._get("getPlaylists")
        raw = (body.get("playlists") or {}).get("playlist") or []
        return [
            SubsonicPlaylist(
                id=str(p.get("id")),
                name=p.get("name") or "Playlist",
                song_count=int(p.get("songCount") or 0),
                duration_s=float(p["duration"]) if p.get("duration") is not None else None,
            )
            for p in raw
        ]

    def playlist_songs(self, playlist_id: str) -> list[SubsonicSong]:
        body = self._get("getPlaylist", {"id": playlist_id})
        raw = (body.get("playlist") or {}).get("entry") or []
        return [SubsonicSong.from_json(s) for s in raw]

    def starred_songs(self) -> list[SubsonicSong]:
        body = self._get("getStarred2")
        raw = (body.get("starred2") or {}).get("song") or []
        return [SubsonicSong.from_json(s) for s in raw]

    def recently_played_count(self, limit: int = 50) -> int | None:
        """How many songs the watch's "Recently played" would take: the latest
        `limit` by last play, stopping at the first never played — the same
        walk as watchapp's TracksSyncDelegate. Subsonic has no song-level
        history, so this is Navidrome's own API, as on the watch; None from any
        other server, or when that API will not answer."""
        try:
            with _client(_TIMEOUT) as http:
                login = http.post(f"{self.base_url}/auth/login",
                                  json={"username": self.username, "password": self.password})
                _check_redirects(self.base_url, login)
                token = login.json().get("token") if login.status_code == 200 else None
                if not token:
                    return None
                resp = http.get(f"{self.base_url}/api/song",
                                params={"_sort": "play_date", "_order": "DESC", "_start": 0, "_end": limit},
                                headers={"x-nd-authorization": f"Bearer {token}"})
                _check_redirects(self.base_url, resp)
                rows = resp.json() if resp.status_code == 200 else None
        except (httpx.HTTPError, ValueError):
            return None
        if not isinstance(rows, list):
            return None
        count = 0
        for row in rows:
            if not (isinstance(row, dict) and row.get("playCount")):
                break
            count += 1
        return count

    def album_songs(self, album_id: str) -> list[SubsonicSong]:
        body = self._get("getAlbum", {"id": album_id})
        raw = (body.get("album") or {}).get("song") or []
        return [SubsonicSong.from_json(s) for s in raw]

    def album_list(self, list_type: str, size: int = 20) -> list[str]:
        """Album ids for a named list. `recent` and `frequent` are the
        listening-history views — the server ranks by plays, we just read it."""
        body = self._get("getAlbumList2", {"type": list_type, "size": size})
        raw = (body.get("albumList2") or {}).get("album") or []
        return [str(a.get("id")) for a in raw if a.get("id")]

    def random_songs(self, size: int = 50) -> list[SubsonicSong]:
        body = self._get("getRandomSongs", {"size": size})
        raw = (body.get("randomSongs") or {}).get("song") or []
        return [SubsonicSong.from_json(s) for s in raw]

    def search_songs(self, query: str, count: int = 50) -> list[SubsonicSong]:
        body = self._get("search3", {
            "query": query, "songCount": count, "albumCount": 0, "artistCount": 0,
        })
        raw = (body.get("searchResult3") or {}).get("song") or []
        return [SubsonicSong.from_json(s) for s in raw]

    # ── audio ────────────────────────────────────────────────────────────────

    def stream(self, song_id: str, *, max_bitrate: int = 192) -> bytes:
        """Download one song as mp3.

        The server does the transcoding: asking for `format=mp3` means an
        already-mp3 file is sent untouched and a FLAC arrives converted, so most
        libraries never touch our own ffmpeg pass. It still runs afterwards —
        Subsonic servers happily embed cover art, which is exactly what the
        watch's scanner refuses.
        """
        params = {**self._auth_params(), "id": song_id,
                  "format": "mp3", "maxBitRate": max_bitrate}
        try:
            with _client(_STREAM_TIMEOUT) as http, \
                    http.stream("GET", self._url("stream"), params=params) as resp:
                # Before any body is read — this is a whole track, and the
                # point is not to stream megabytes back from somewhere that is
                # not the music server.
                _check_redirects(self.base_url, resp)
                if resp.status_code >= 400:
                    raise SubsonicError(f"Music server returned HTTP {resp.status_code}")
                # An error here still arrives as JSON with HTTP 200.
                if "json" in (resp.headers.get("content-type") or ""):
                    resp.read()
                    self._unwrap(resp, "stream")
                    raise SubsonicError("Music server sent no audio for that track")
                return b"".join(resp.iter_bytes())
        except httpx.HTTPError as exc:
            raise SubsonicError(f"Could not download from the music server: {exc}") from exc


#: The derived playlists Tracks offers. Subsonic has no "smart playlist" of its
#: own — what it has are ranked *album* lists plus a starred set, so each of
#: these is a query shape rather than something the server stores.
#:
#: `id` is what the API takes; `label` is what a user reads. Kept here rather
#: than in the API layer so the client and the resolver cannot disagree about
#: which kinds exist.
SMART_KINDS = [
    {"id": "recent", "label": "Recently played"},
    {"id": "frequent", "label": "Most played"},
    {"id": "newest", "label": "Recently added"},
    {"id": "starred", "label": "Favourites"},
    {"id": "random", "label": "Surprise me"},
]

SMART_IDS = frozenset(k["id"] for k in SMART_KINDS)


def smart_songs(client: SubsonicClient, kind: str, limit: int) -> list[SubsonicSong]:
    """Resolve one derived playlist to actual songs.

    The album-ranked kinds (`recent`, `frequent`, `newest`) are expanded track
    by track: Subsonic keeps play counts on albums, not songs, so "recently
    played" genuinely means "songs from albums played recently". Worth knowing
    when the result includes a track from an album you only half-listened to.
    """
    if kind not in SMART_IDS:
        raise SubsonicError(f"Unknown smart playlist: {kind}")

    if kind == "starred":
        return client.starred_songs()[:limit]
    if kind == "random":
        return client.random_songs(size=limit)

    out: list[SubsonicSong] = []
    seen: set[str] = set()
    for album_id in client.album_list(kind, size=max(limit // 4, 10)):
        for song in client.album_songs(album_id):
            if song.id in seen:
                continue
            seen.add(song.id)
            out.append(song)
            if len(out) >= limit:
                return out
    return out


def rotation_songs(client: SubsonicClient, limit: int) -> list[SubsonicSong]:
    """Songs to carry, drawn from what the user actually listens to.

    Subsonic has no "recently played songs" call — play counts live on albums —
    so recent and frequent albums are expanded into their tracks. Starred songs
    come first because an explicit favourite outranks an inference, and random
    songs backfill a thin library so a new install still gets a full rotation.

    Order is preserved and duplicates dropped, so the head of the list is the
    most-deliberate choice available.

    A single source failing is tolerated — not every server implements every
    call, and a missing `getStarred2` should not cost the whole rotation. Every
    source failing is a different thing: that is an unreachable or broken
    server, and returning an empty list would be indistinguishable from an empty
    library, which would silently unload the watch. So that case raises.
    """
    seen: set[str] = set()
    out: list[SubsonicSong] = []
    succeeded = 0
    last_error: SubsonicError | None = None

    def take(songs: Iterator[SubsonicSong] | list[SubsonicSong]) -> bool:
        for song in songs:
            if song.id in seen:
                continue
            seen.add(song.id)
            out.append(song)
            if len(out) >= limit:
                return True
        return False

    try:
        full = take(client.starred_songs())
        succeeded += 1
        if full:
            return out
    except SubsonicError as exc:
        last_error = exc
        log.warning("rotation: starred songs unavailable: %s", exc)

    for list_type in ("frequent", "recent"):
        try:
            album_ids = client.album_list(list_type, size=25)
            succeeded += 1
            for album_id in album_ids:
                if take(client.album_songs(album_id)):
                    return out
        except SubsonicError as exc:
            last_error = exc
            log.warning("rotation: %s albums unavailable: %s", list_type, exc)

    try:
        take(client.random_songs(size=limit))
        succeeded += 1
    except SubsonicError as exc:
        last_error = exc
        log.warning("rotation: random songs unavailable: %s", exc)

    if succeeded == 0:
        raise last_error or SubsonicError("The music server answered nothing")

    return out
