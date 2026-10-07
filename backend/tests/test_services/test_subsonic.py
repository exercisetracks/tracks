# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Subsonic client, and the rotation it feeds.

The behaviour worth pinning is the protocol's one real trap: Subsonic reports
failure with HTTP 200 and an error body, so anything that does not unwrap
carefully will happily treat "wrong password" as data.
"""

import hashlib

import httpx
import pytest

from app.services import subsonic
from app.services.subsonic import SubsonicClient, SubsonicError, rotation_songs


def _ok(payload):
    return {"subsonic-response": {"status": "ok", "version": "1.16.1", **payload}}


def _fail(code, message):
    return {"subsonic-response": {"status": "failed", "error": {"code": code, "message": message}}}


def _serve(monkeypatch, handler):
    """Answer every outbound request with `handler(request)`.

    Through real httpx, via a mock transport, rather than a fake `httpx.get`:
    fakes that took **kwargs let a keyword httpx rejects (`max_redirects` on
    `httpx.get`) through, and every music request failed in production.
    """
    monkeypatch.setattr(subsonic, "_transport", httpx.MockTransport(handler))


def _method(request):
    return request.url.path.rstrip("/").split("/")[-1].replace(".view", "")


@pytest.fixture
def calls(monkeypatch):
    """Capture outbound requests and serve scripted replies."""
    recorded = []
    replies = {}

    def handler(request):
        recorded.append({"url": str(request.url.copy_with(query=None)),
                         "params": dict(request.url.params)})
        return httpx.Response(200, json=replies.get(_method(request), _ok({})))

    _serve(monkeypatch, handler)
    return {"recorded": recorded, "replies": replies}


@pytest.fixture
def client():
    return SubsonicClient("https://music.example.com/", "alex", "hunter2")


# ── auth + protocol ──────────────────────────────────────────────────────────

def test_auth_uses_salted_token_and_never_sends_the_password(calls, client):
    client.ping()

    params = calls["recorded"][0]["params"]
    assert params["u"] == "alex"
    assert params["t"] == hashlib.md5(f"hunter2{params['s']}".encode()).hexdigest()
    assert params["f"] == "json"
    # The password itself must not appear in the query string; it would land in
    # the server's access log.
    assert "p" not in params
    assert "hunter2" not in str(params)


def test_each_request_gets_a_fresh_salt(calls, client):
    client.ping()
    client.ping()

    salts = [c["params"]["s"] for c in calls["recorded"]]
    assert salts[0] != salts[1]


def test_trailing_slash_in_the_url_does_not_double_up(calls, client):
    client.ping()

    assert calls["recorded"][0]["url"] == "https://music.example.com/rest/ping.view"


def test_a_failure_body_with_http_200_is_raised_not_returned(calls, client):
    calls["replies"]["ping"] = _fail(40, "Wrong username or password")

    # The trap: HTTP 200 with an error payload. Returning this as data would
    # make a bad password look like a working connection.
    with pytest.raises(SubsonicError, match="username or password"):
        client.ping()


# ── redirect containment ─────────────────────────────────────────────────────
# The base URL is user-supplied and the backend fetches it server-side, so a
# followed redirect is an SSRF: it reads whatever the backend can reach and the
# user cannot, and hands it back. These pin which hops are allowed.

def _redirected_to(monkeypatch, final_url):
    """The music server's first answer is a redirect to `final_url`; after that, ok."""
    hops = []

    def handler(request):
        hops.append(request.url)
        if len(hops) == 1:
            return httpx.Response(302, headers={"location": final_url})
        return httpx.Response(200, json=_ok({}))

    _serve(monkeypatch, handler)


def test_a_redirect_off_the_configured_host_is_refused(monkeypatch, client):
    """The case this exists for: the music server points us at a container the
    caller could not otherwise reach."""
    _redirected_to(monkeypatch, "http://redis:6379/")

    with pytest.raises(SubsonicError, match="redirected somewhere else"):
        client.ping()


def test_a_redirect_to_the_cloud_metadata_service_is_refused(monkeypatch, client):
    _redirected_to(monkeypatch, "http://169.254.169.254/latest/meta-data/")

    with pytest.raises(SubsonicError, match="redirected somewhere else"):
        client.ping()


def test_an_http_to_https_upgrade_on_the_same_host_is_allowed(monkeypatch):
    """Refusing this would break a server that redirects to its own TLS port —
    and push a working deployment back onto cleartext."""
    plain = SubsonicClient("http://music.example.com", "alex", "hunter2")
    _redirected_to(monkeypatch, "https://music.example.com/rest/ping.view")

    plain.ping()   # the assertion is that this does not raise


def test_an_https_to_http_downgrade_is_refused(monkeypatch):
    secure = SubsonicClient("https://music.example.com", "alex", "hunter2")
    _redirected_to(monkeypatch, "http://music.example.com/rest/ping.view")

    with pytest.raises(SubsonicError, match="redirected somewhere else"):
        secure.ping()


def test_the_default_port_and_the_explicit_one_are_the_same_origin(monkeypatch, client):
    """https://host and https://host:443 are the same place; comparing the raw
    strings would call them different and refuse a legitimate response."""
    _redirected_to(monkeypatch, "https://music.example.com:443/rest/ping.view")

    client.ping()   # the assertion is that this does not raise


def test_a_redirect_to_another_port_on_the_same_host_is_refused(monkeypatch, client):
    """Same host is not enough — the interesting targets on a homelab box are
    the other ports on it."""
    _redirected_to(monkeypatch, "https://music.example.com:8080/rest/ping.view")

    with pytest.raises(SubsonicError, match="redirected somewhere else"):
        client.ping()


def test_a_download_checks_before_reading_the_body(monkeypatch, client):
    """A track is megabytes. The check has to happen before any of it is read,
    not after."""
    read = []

    def body():
        read.append(True)
        yield b"audio"

    def handler(request):
        if request.url.host == "redis":
            return httpx.Response(200, headers={"content-type": "audio/mpeg"}, content=body())
        return httpx.Response(302, headers={"location": "http://redis:6379/"})

    _serve(monkeypatch, handler)

    with pytest.raises(SubsonicError, match="redirected somewhere else"):
        client.stream("song-1")
    assert read == []


def test_a_non_subsonic_url_fails_clearly(monkeypatch, client):
    _serve(monkeypatch, lambda request: httpx.Response(200, text="<html>not subsonic</html>"))

    with pytest.raises(SubsonicError, match="Subsonic URL"):
        client.ping()


def test_a_network_error_becomes_a_subsonic_error(monkeypatch, client):
    def boom(request):
        raise httpx.ConnectError("refused")

    _serve(monkeypatch, boom)
    with pytest.raises(SubsonicError, match="Could not reach"):
        client.ping()


# ── reads ────────────────────────────────────────────────────────────────────

def test_playlists_are_parsed(calls, client):
    calls["replies"]["getPlaylists"] = _ok({
        "playlists": {"playlist": [
            {"id": "p1", "name": "Long runs", "songCount": 12, "duration": 2400},
        ]}
    })

    playlists = client.playlists()

    assert playlists[0].id == "p1"
    assert playlists[0].name == "Long runs"
    assert playlists[0].song_count == 12


def test_an_empty_library_is_an_empty_list_not_an_error(calls, client):
    calls["replies"]["getPlaylists"] = _ok({"playlists": {}})

    assert client.playlists() == []


def test_songs_tolerate_missing_optional_fields(calls, client):
    calls["replies"]["getPlaylist"] = _ok({
        "playlist": {"entry": [{"id": "s1", "title": "Bare"}]}
    })

    song = client.playlist_songs("p1")[0]

    assert song.title == "Bare"
    assert song.artist is None and song.duration_s is None and song.track_no is None


def test_stream_asks_for_mp3_so_the_server_does_the_transcoding(monkeypatch, client):
    seen = {}

    def handler(request):
        seen.update(request.url.params)
        return httpx.Response(200, headers={"content-type": "audio/mpeg"}, content=b"ID3audio")

    _serve(monkeypatch, handler)

    assert client.stream("s1", max_bitrate=128) == b"ID3audio"
    assert seen["format"] == "mp3"
    assert seen["maxBitRate"] == "128"


def test_stream_surfaces_an_error_body_instead_of_saving_it_as_audio(monkeypatch, client):
    _serve(monkeypatch, lambda request: httpx.Response(200, json=_fail(70, "Song not found")))

    # Without the content-type check this would be written to disk as an "mp3".
    with pytest.raises(SubsonicError):
        client.stream("missing")


def test_a_redirect_chain_longer_than_the_cap_is_not_followed(monkeypatch, client):
    """The hop cap is a Client setting; this also proves it reaches httpx."""
    _serve(monkeypatch, lambda request: httpx.Response(
        302, headers={"location": "https://music.example.com/rest/ping.view"}))

    with pytest.raises(SubsonicError, match="Could not reach"):
        client.ping()


# ── rotation ─────────────────────────────────────────────────────────────────

class FakeClient:
    def __init__(self, starred=(), albums=None, album_songs=None, random=()):
        self._starred = list(starred)
        self._albums = albums or {}
        self._album_songs = album_songs or {}
        self._random = list(random)

    def starred_songs(self):
        return self._starred

    def album_list(self, list_type, size=20):
        return self._albums.get(list_type, [])

    def album_songs(self, album_id):
        return self._album_songs.get(album_id, [])

    def random_songs(self, size=50):
        return self._random


def _song(sid):
    return subsonic.SubsonicSong(
        id=sid, title=f"T{sid}", artist="A", album="Al",
        duration_s=180.0, track_no=1, suffix="mp3", size=5_000_000,
    )


def test_rotation_prefers_starred_over_inferred_plays():
    client = FakeClient(
        starred=[_song("fav")],
        albums={"frequent": ["al1"]},
        album_songs={"al1": [_song("played")]},
    )

    songs = rotation_songs(client, 10)

    # An explicit favourite outranks something the play counter inferred.
    assert songs[0].id == "fav"
    assert {s.id for s in songs} == {"fav", "played"}


def test_rotation_stops_at_the_limit():
    client = FakeClient(starred=[_song(str(i)) for i in range(50)])

    assert len(rotation_songs(client, 5)) == 5


def test_rotation_never_repeats_a_song_across_sources():
    shared = _song("same")
    client = FakeClient(
        starred=[shared],
        albums={"frequent": ["al1"], "recent": ["al1"]},
        album_songs={"al1": [shared]},
    )

    songs = rotation_songs(client, 10)

    assert [s.id for s in songs] == ["same"]


def test_rotation_backfills_from_random_when_history_is_thin():
    client = FakeClient(starred=[], albums={}, random=[_song("r1"), _song("r2")])

    # A new install has no play history; an empty watch is a worse answer than
    # a random one.
    assert {s.id for s in rotation_songs(client, 10)} == {"r1", "r2"}


def test_one_failing_source_does_not_sink_the_whole_rotation():
    class Flaky(FakeClient):
        def starred_songs(self):
            raise SubsonicError("starred is broken on this server")

    client = Flaky(albums={"frequent": ["al1"]}, album_songs={"al1": [_song("ok")]})

    assert [s.id for s in rotation_songs(client, 10)] == ["ok"]


def test_rotation_raises_when_every_source_fails():
    """An unreachable server must not look like an empty library.

    Returning [] here would make run_rotation unload everything the watch is
    carrying, on the strength of a network error.
    """
    class Dead(FakeClient):
        def starred_songs(self): raise SubsonicError("down")
        def album_list(self, list_type, size=20): raise SubsonicError("down")
        def random_songs(self, size=50): raise SubsonicError("down")

    with pytest.raises(SubsonicError):
        rotation_songs(Dead(), 10)


def test_rotation_returns_empty_for_a_genuinely_empty_library():
    # Sources answered; there is simply nothing in them. Distinct from above.
    assert rotation_songs(FakeClient(), 10) == []


# ── the watch's built-in "Recently played" ───────────────────────────────────

def _navidrome(monkeypatch, songs, login_status=200):
    def handler(request):
        if request.url.path == "/auth/login":
            return httpx.Response(login_status, json={"token": "jwt"} if login_status == 200 else {})
        if request.url.path == "/api/song":
            assert request.headers["x-nd-authorization"] == "Bearer jwt"
            return httpx.Response(200, json=songs)
        return httpx.Response(404)
    _serve(monkeypatch, handler)


def test_recently_played_counts_up_to_the_first_unplayed_song(monkeypatch, client):
    """The same walk the watch makes, so the count on the phone is what the
    watch will actually take."""
    _navidrome(monkeypatch, [{"playCount": 3}, {"playCount": 1}, {"playCount": 0}, {"playCount": 5}])

    assert client.recently_played_count() == 2


def test_recently_played_is_unknown_on_a_server_without_navidromes_api(monkeypatch, client):
    _navidrome(monkeypatch, [], login_status=404)

    assert client.recently_played_count() is None


# ── Discovery from a partial address ──────────────────────────────────────────

def test_a_bare_lan_address_gets_both_schemes_and_the_default_ports():
    c = subsonic.candidates_for("10.0.0.5")
    assert c[:2] == ["https://10.0.0.5", "https://10.0.0.5:4533"]
    assert "http://10.0.0.5:4533" in c


def test_an_address_with_a_scheme_and_port_is_tried_as_typed():
    assert subsonic.candidates_for("http://box:4533/") == ["http://box:4533"]


def test_discovery_finds_navidrome_on_its_default_port(monkeypatch):
    """The case the form exists for: someone types the box's IP and nothing else."""
    def handler(request):
        if request.url.host == "10.0.0.5" and request.url.port == 4533 and request.url.scheme == "http":
            return httpx.Response(200, json={"subsonic-response": {"status": "failed"}})
        raise httpx.ConnectError("refused")
    monkeypatch.setattr(subsonic, "_transport", httpx.MockTransport(handler))
    assert subsonic.discover("10.0.0.5") == "http://10.0.0.5:4533"


def test_a_web_page_is_not_mistaken_for_a_music_server(monkeypatch):
    monkeypatch.setattr(subsonic, "_transport",
                        httpx.MockTransport(lambda r: httpx.Response(200, text="<html>hi</html>")))
    assert subsonic.discover("example.com") is None
