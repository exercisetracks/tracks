# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Connecting a Navidrome/Subsonic server, and the rotation it drives.

The interesting behaviour is laziness and restraint: a remote track is a
reference until something asks for its bytes, and auto-rotation is never allowed
to overrule a track the user picked by hand.
"""

import pytest

from app.models.music import MusicTrack
from app.models.user_settings import UserSettings
from app.services import music_source, music_rotation, subsonic
from app.services.subsonic import SubsonicError, SubsonicSong


def _song(sid, title="Song", artist="Band"):
    return SubsonicSong(id=sid, title=title, artist=artist, album="Album",
                        duration_s=200.0, track_no=1, suffix="mp3", size=4_000_000)


class FakeSubsonic:
    """Stands in for a live music server."""

    def __init__(self, *, songs=None, playlists=(), starred=(), audio=b"", fail=None):
        self.songs = songs or {}
        self._playlists = list(playlists)
        self._starred = list(starred)
        self.audio = audio
        self.fail = fail
        self.streamed = []

    def _check(self):
        if self.fail:
            raise SubsonicError(self.fail)

    def ping(self):
        self._check()
        return "navidrome 0.53"

    def playlists(self):
        self._check()
        return self._playlists

    def playlist_songs(self, pid):
        self._check()
        return self.songs.get(pid, [])

    def starred_songs(self):
        self._check()
        return self._starred

    def recently_played_count(self, limit=50):
        return 7

    def album_list(self, list_type, size=20):
        self._check()
        return []

    def album_songs(self, album_id):
        self._check()
        return []

    def random_songs(self, size=50):
        self._check()
        return []

    def stream(self, song_id, max_bitrate=192):
        self._check()
        self.streamed.append(song_id)
        return self.audio


@pytest.fixture
def fake_server(monkeypatch, real_mp3_bytes):
    """Install a fake server for every code path that builds a client."""
    server = FakeSubsonic(audio=real_mp3_bytes)

    def build(us):
        return None if us is None or not us.music_server_url else server

    monkeypatch.setattr(music_source, "client_for", build)
    monkeypatch.setattr(music_rotation, "client_for", build)
    monkeypatch.setattr(
        "app.api.music.navidrome.client_for", build, raising=False
    )
    monkeypatch.setattr(
        "app.api.music.navidrome.SubsonicClient",
        lambda url, u, p: server,
    )
    return server


@pytest.fixture
def real_mp3_bytes(tmp_path):
    import shutil
    import subprocess
    if shutil.which("ffmpeg") is None:
        pytest.skip("ffmpeg not available")
    path = tmp_path / "remote.mp3"
    subprocess.run([
        "ffmpeg", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-ar", "44100", "-ac", "2", "-b:a", "128k", str(path),
    ], capture_output=True, check=True)
    return path.read_bytes()


@pytest.fixture
def connected(client, user, db, fake_server):
    resp = client.put("/music/server", json={
        "url": "https://music.example.com", "username": "alex", "password": "hunter2",
    })
    assert resp.status_code == 200, resp.text
    return fake_server


# ── connecting ───────────────────────────────────────────────────────────────

def test_connecting_verifies_the_credentials_before_storing_them(client, user, db, fake_server):
    fake_server.fail = "Music server rejected the username or password"

    resp = client.put("/music/server", json={
        "url": "https://music.example.com", "username": "alex", "password": "wrong",
    })

    # Storing first and failing later would surface the mistake in a background
    # rotation, where nobody is watching.
    assert resp.status_code == 502
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    assert us.music_server_url is None


def test_a_stored_password_is_never_returned(connected, client, user, db):
    body = client.get("/music/server").json()

    assert body["configured"] is True
    assert body["username"] == "alex"
    assert "password" not in body
    assert "hunter2" not in str(body)

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    assert us.music_server_password_enc != "hunter2"


def test_the_watch_config_is_the_one_place_the_password_comes_back(connected, client, user):
    """The phone forwards this to the watch, which logs in to the music server
    itself. Nothing else about the server settings ever reveals it."""
    body = client.get("/music/server/watch-config").json()

    assert body == {"url": "https://music.example.com", "username": "alex", "password": "hunter2"}


def test_the_watch_config_needs_a_server_first(client, user, fake_server):
    assert client.get("/music/server/watch-config").status_code == 400


def test_a_url_without_a_scheme_is_rejected(client, user, fake_server):
    resp = client.put("/music/server", json={
        "url": "music.example.com", "username": "alex", "password": "x",
    })

    assert resp.status_code == 400
    assert "http://" in resp.json()["detail"]


def test_editing_without_resending_the_password_keeps_the_stored_one(connected, client, user, db):
    resp = client.put("/music/server", json={
        "url": "https://music2.example.com", "username": "alex",
    })

    assert resp.status_code == 200
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    assert us.music_server_url == "https://music2.example.com"
    assert us.music_server_password_enc is not None


def test_disconnecting_clears_credentials_and_auto_rotation(connected, client, user, db):
    client.patch("/music/server", json={"auto_rotate": True})

    client.delete("/music/server")

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    assert us.music_server_url is None
    assert us.music_server_password_enc is None
    assert us.music_auto_rotate is False


def test_auto_rotation_cannot_be_enabled_without_a_server(client, user, fake_server):
    resp = client.patch("/music/server", json={"auto_rotate": True})

    assert resp.status_code == 400


def test_the_playlist_list_counts_the_watchs_two_built_ins(connected, client):
    """The phone shows "Liked songs" and "Recently played" beside the real
    playlists; without counts they were the only rows that did not say how big
    they were."""
    connected._starred = [_song("s1", "One"), _song("s2", "Two")]

    body = client.get("/music/server/playlists").json()

    assert body["starred_count"] == 2
    assert body["recent_count"] == 7


# ── importing ────────────────────────────────────────────────────────────────

def test_importing_a_playlist_creates_references_without_downloading(connected, client, user, db):
    connected._playlists = [subsonic.SubsonicPlaylist(id="p1", name="Long runs",
                                                      song_count=2, duration_s=400)]
    connected.songs["p1"] = [_song("s1", "One"), _song("s2", "Two")]

    resp = client.post("/music/server/playlists/p1/import")

    assert resp.json()["tracks"] == 2
    tracks = db.query(MusicTrack).filter_by(source="subsonic").all()
    assert {t.title for t in tracks} == {"One", "Two"}
    # Nothing was streamed: a reference costs no storage until it is needed.
    assert connected.streamed == []
    assert all(t.blob_id is None for t in tracks)


def test_reimporting_updates_the_same_playlist(connected, client, user, db):
    connected._playlists = [subsonic.SubsonicPlaylist(id="p1", name="Long runs",
                                                      song_count=1, duration_s=200)]
    connected.songs["p1"] = [_song("s1", "One")]
    first = client.post("/music/server/playlists/p1/import").json()["playlist_id"]

    connected.songs["p1"] = [_song("s1", "One"), _song("s2", "Two")]
    second = client.post("/music/server/playlists/p1/import").json()

    assert second["playlist_id"] == first
    assert second["tracks"] == 2
    assert len(client.get("/music/playlists").json()["playlists"]) == 1


# ── lazy audio ───────────────────────────────────────────────────────────────

def test_audio_is_fetched_and_cached_on_first_request(connected, client, user, db):
    connected._playlists = [subsonic.SubsonicPlaylist(id="p1", name="P", song_count=1, duration_s=200)]
    connected.songs["p1"] = [_song("s1", "One")]
    client.post("/music/server/playlists/p1/import")
    track = db.query(MusicTrack).filter_by(source="subsonic").first()

    resp = client.get(f"/music/tracks/{track.id}/audio")

    assert resp.status_code == 200
    assert connected.streamed == ["s1"]
    db.refresh(track)
    assert track.blob_id is not None
    assert track.size_bytes > 0


def test_a_second_request_serves_the_cache_without_refetching(connected, client, user, db):
    connected._playlists = [subsonic.SubsonicPlaylist(id="p1", name="P", song_count=1, duration_s=200)]
    connected.songs["p1"] = [_song("s1", "One")]
    client.post("/music/server/playlists/p1/import")
    track = db.query(MusicTrack).filter_by(source="subsonic").first()

    client.get(f"/music/tracks/{track.id}/audio")
    client.get(f"/music/tracks/{track.id}/audio")

    assert connected.streamed == ["s1"]


def test_an_uncached_remote_track_is_still_offered_to_the_watch(connected, client, user, db):
    """A reference must appear in the push plan.

    Requiring a blob would hide every remote track from the very push that
    materialises it — the library would look empty until something else
    happened to fetch each track first.
    """
    connected._playlists = [subsonic.SubsonicPlaylist(id="p1", name="P", song_count=1, duration_s=200)]
    connected.songs["p1"] = [_song("s1", "One")]
    client.post("/music/server/playlists/p1/import?load_to_device=true")

    plan = client.get("/music/device-plan").json()

    assert [t["title"] for t in plan["add"]] == ["One"]
    # Size is estimated from duration so the client can still check the
    # transfer against the device's limits before starting.
    assert plan["add"][0]["size"] > 0


# ── rotation ─────────────────────────────────────────────────────────────────

def test_rotation_flags_what_the_server_says_is_played(connected, client, user, db):
    connected._starred = [_song("s1", "One"), _song("s2", "Two")]

    result = client.post("/music/server/rotate", json={}).json()

    assert result["carried"] == 2
    assert result["created"] == 2
    carried = db.query(MusicTrack).filter_by(load_to_device=True).all()
    assert {t.title for t in carried} == {"One", "Two"}


def test_rotation_drops_tracks_that_fell_out_of_favour(connected, client, user, db):
    connected._starred = [_song("s1", "One"), _song("s2", "Two")]
    client.post("/music/server/rotate", json={})

    connected._starred = [_song("s1", "One")]
    result = client.post("/music/server/rotate", json={}).json()

    assert result["dropped"] == 1
    assert [t.title for t in db.query(MusicTrack).filter_by(load_to_device=True).all()] == ["One"]


def test_rotation_never_unloads_a_track_the_user_uploaded(connected, client, user, db, tmp_path, real_mp3_bytes):
    """An explicit choice outranks an inference from play counts."""
    upload = tmp_path / "mine.mp3"
    upload.write_bytes(real_mp3_bytes)
    with upload.open("rb") as fh:
        client.post("/music/tracks", files={"files": ("mine.mp3", fh, "audio/mpeg")},
                    data={"load_to_device": "true"})

    connected._starred = [_song("s1", "One")]
    client.post("/music/server/rotate", json={})

    uploaded = db.query(MusicTrack).filter_by(source="upload").first()
    assert uploaded.load_to_device is True


def test_rotation_reports_an_unreachable_server(connected, client, user):
    connected.fail = "Could not reach the music server"

    resp = client.post("/music/server/rotate", json={})

    assert resp.status_code == 502


def test_rotation_respects_an_explicit_limit(connected, client, user, db):
    connected._starred = [_song(f"s{i}", f"T{i}") for i in range(10)]

    result = client.post("/music/server/rotate", json={"limit": 3}).json()

    assert result["carried"] == 3


# ── demand-driven rotation ───────────────────────────────────────────────────

def test_asking_for_a_plan_refreshes_a_stale_rotation(connected, client, user, db):
    """Rotation happens when the watch asks, not on a cron tick.

    That is the whole scheduling strategy: no beat container, and the plan is
    computed from listening history as of moments ago.
    """
    client.patch("/music/server", json={"auto_rotate": True})
    connected._starred = [_song("s1", "One")]

    plan = client.get("/music/device-plan").json()

    assert [t["title"] for t in plan["add"]] == ["One"]


def test_a_fresh_rotation_is_not_recomputed_on_every_sync(connected, client, user, db):
    client.patch("/music/server", json={"auto_rotate": True})
    connected._starred = [_song("s1", "One")]
    client.get("/music/device-plan")

    # A second sync minutes later must not hammer the music server.
    connected._starred = [_song("s2", "Two")]
    client.get("/music/device-plan")

    titles = {t.title for t in db.query(MusicTrack).all()}
    assert titles == {"One"}


def test_rotation_is_skipped_entirely_when_auto_is_off(connected, client, user, db):
    connected._starred = [_song("s1", "One")]

    client.get("/music/device-plan")

    assert db.query(MusicTrack).count() == 0


def test_a_dead_music_server_does_not_fail_the_sync(connected, client, user, db):
    """The watch should sync what was already chosen, not get an error."""
    client.patch("/music/server", json={"auto_rotate": True})
    connected.fail = "Could not reach the music server"

    resp = client.get("/music/device-plan")

    assert resp.status_code == 200
    assert resp.json()["add"] == []


# ── smart playlists ──────────────────────────────────────────────────────────

def _albums(server, kind, album_id, songs):
    server.album_list = lambda t, size=20, _k=kind, _a=album_id: [_a] if t == _k else []
    server.album_songs = lambda a, _a=album_id, _s=songs: _s if a == _a else []


def test_smart_kinds_are_offered_without_asking_the_server(connected, client, user):
    # These are query shapes Tracks resolves, not something Subsonic stores,
    # so the list is fixed and needs no round trip.
    kinds = client.get("/music/server/smart").json()["kinds"]

    assert {k["id"] for k in kinds} == {"recent", "frequent", "newest", "starred", "random"}
    assert all(k["imported"] is False for k in kinds)


def test_importing_recently_played_carries_its_songs(connected, client, user, db):
    _albums(connected, "recent", "al1", [_song("s1", "One"), _song("s2", "Two")])

    resp = client.post("/music/server/smart/recent/import")

    assert resp.status_code == 200
    carried = db.query(MusicTrack).filter_by(load_to_device=True).all()
    assert {t.title for t in carried} == {"One", "Two"}


def test_a_smart_playlist_is_a_standing_query_not_a_frozen_list(connected, client, user, db):
    """The whole point: "recently played" must keep meaning recently played."""
    _albums(connected, "recent", "al1", [_song("s1", "One")])
    client.post("/music/server/smart/recent/import")

    # What the server considers recent changes.
    _albums(connected, "recent", "al1", [_song("s2", "Two")])
    client.patch("/music/server", json={"auto_rotate": True})
    db.query(UserSettings).filter_by(user_id=user.id).update({"music_rotate_last_run": None})
    db.commit()
    client.get("/music/device-plan")

    carried = {t.title for t in db.query(MusicTrack).filter_by(load_to_device=True).all()}
    assert "Two" in carried


def test_starred_maps_to_favourites(connected, client, user, db):
    connected._starred = [_song("s1", "Fav")]

    client.post("/music/server/smart/starred/import")

    assert [t.title for t in db.query(MusicTrack).filter_by(load_to_device=True).all()] == ["Fav"]


def test_an_unknown_smart_kind_is_refused(connected, client, user):
    assert client.post("/music/server/smart/nonsense/import").status_code == 404


def test_dropping_a_smart_playlist_unloads_only_its_own_tracks(connected, client, user, db):
    _albums(connected, "recent", "al1", [_song("s1", "Shared"), _song("s2", "Only")])
    client.post("/music/server/smart/recent/import")
    shared = db.query(MusicTrack).filter_by(title="Shared").first()

    # A hand-made playlist also wants "Shared".
    pid = client.post("/music/playlists", json={"name": "Mine"}).json()["id"]
    client.put(f"/music/playlists/{pid}/tracks", json={"track_ids": [shared.id]})
    client.patch(f"/music/playlists/{pid}", json={"load_to_device": True})

    resp = client.delete("/music/server/smart/recent")

    assert resp.json()["removed"] is True
    db.refresh(shared)
    # Still carried: another playlist the user is carrying names it.
    assert shared.load_to_device is True
    assert db.query(MusicTrack).filter_by(title="Only").first().load_to_device is False


def test_imported_smart_playlists_are_reported_as_such(connected, client, user):
    _albums(connected, "frequent", "al1", [_song("s1", "One")])
    client.post("/music/server/smart/frequent/import")

    kinds = {k["id"]: k for k in client.get("/music/server/smart").json()["kinds"]}

    assert kinds["frequent"]["imported"] is True
    assert kinds["frequent"]["load_to_device"] is True
    assert kinds["recent"]["imported"] is False
