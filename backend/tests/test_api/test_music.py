# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Music library API and the watch reconciliation behind the push.

The reconciliation assertions are the load-bearing ones: a music push moves
megabytes per track, so a plan that offers the same file twice, or forgets a
file it already sent, costs real minutes on a cable.
"""

import shutil
import subprocess

import pytest

from app.models.music import MusicTrack

pytestmark = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg not available",
)


def _mp3(tmp_path, name, *, title, artist="Artist", seconds=1, freq=440):
    path = tmp_path / name
    subprocess.run([
        "ffmpeg", "-nostdin", "-y",
        "-f", "lavfi", "-i", f"sine=frequency={freq}:duration={seconds}",
        "-ar", "44100", "-ac", "2", "-b:a", "128k",
        "-metadata", f"title={title}", "-metadata", f"artist={artist}",
        "-id3v2_version", "3", str(path),
    ], capture_output=True, check=True)
    return path


def _upload(client, path, load=False):
    with path.open("rb") as fh:
        return client.post(
            "/music/tracks",
            files={"files": (path.name, fh, "audio/mpeg")},
            data={"load_to_device": str(load).lower()},
        )


# ── library ──────────────────────────────────────────────────────────────────

def test_upload_creates_a_track_with_metadata_from_the_file(client, user, tmp_path):
    resp = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", artist="Band"))

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["failed"] == [] and body["duplicates"] == []
    track = body["added"][0]
    assert track["title"] == "Alpha"
    assert track["artist"] == "Band"
    assert track["codec"] == "mp3"
    assert track["size_bytes"] > 0


def test_reuploading_the_same_audio_is_a_duplicate_not_a_second_row(client, user, tmp_path):
    path = _mp3(tmp_path, "a.mp3", title="Alpha")
    first = _upload(client, path)
    second = _upload(client, path)

    assert second.json()["added"] == []
    assert second.json()["duplicates"][0]["id"] == first.json()["added"][0]["id"]
    assert len(client.get("/music/tracks").json()["tracks"]) == 1


def test_a_file_that_is_not_audio_is_reported_not_raised(client, user, tmp_path):
    junk = tmp_path / "notes.txt"
    junk.write_bytes(b"definitely not audio" * 50)

    resp = _upload(client, junk)

    assert resp.status_code == 200
    assert resp.json()["added"] == []
    assert resp.json()["failed"][0]["filename"] == "notes.txt"


# ── audio delivery ───────────────────────────────────────────────────────────

def test_audio_download_returns_the_stored_bytes(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]

    resp = client.get(f"/music/tracks/{tid}/audio")

    assert resp.status_code == 200
    assert resp.headers["content-type"] == "audio/mpeg"
    assert resp.headers["accept-ranges"] == "bytes"
    assert len(resp.content) > 0


def test_range_request_returns_206_and_the_requested_slice(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]
    whole = client.get(f"/music/tracks/{tid}/audio").content

    resp = client.get(f"/music/tracks/{tid}/audio", headers={"Range": "bytes=10-19"})

    assert resp.status_code == 206
    assert resp.content == whole[10:20]
    assert resp.headers["content-range"] == f"bytes 10-19/{len(whole)}"


def test_open_ended_range_runs_to_the_end_of_the_file(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]
    whole = client.get(f"/music/tracks/{tid}/audio").content

    resp = client.get(f"/music/tracks/{tid}/audio", headers={"Range": "bytes=100-"})

    assert resp.status_code == 206
    assert resp.content == whole[100:]


def test_a_suffix_range_returns_the_tail(client, user, tmp_path):
    """`bytes=-N` means the last N bytes, not "from N" — the one Range form
    that is easy to read backwards."""
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]
    whole = client.get(f"/music/tracks/{tid}/audio").content

    resp = client.get(f"/music/tracks/{tid}/audio", headers={"Range": "bytes=-50"})

    assert resp.status_code == 206
    assert resp.content == whole[-50:]


def test_an_absurdly_long_range_does_not_crash_the_handler(tmp_path):
    """A Range header is attacker-controlled. Feeding thousands of digits to
    int() costs real CPU, and past CPython's 4300-digit cap it raises outright
    — which, without a bound on the pattern, surfaces as a 500.

    Called directly rather than over the test client, which rejects a header
    this size before the handler ever sees it. Real servers differ on where
    that limit sits, so the handler should not rely on being shielded.
    """
    from app.api.music.library import ranged_audio_response

    audio = tmp_path / "a.mp3"
    audio.write_bytes(b"x" * 500)

    resp = ranged_audio_response(audio, "bytes=" + "9" * 4400 + "-")

    # Unparseable, so it falls through to serving the whole file — the same as
    # any other malformed Range.
    assert resp.status_code == 200


def test_range_past_the_end_is_refused_with_416(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]
    size = len(client.get(f"/music/tracks/{tid}/audio").content)

    resp = client.get(f"/music/tracks/{tid}/audio", headers={"Range": f"bytes={size + 10}-"})

    assert resp.status_code == 416


# ── watch reconciliation ─────────────────────────────────────────────────────

def test_only_flagged_tracks_are_offered_to_the_watch(client, user, tmp_path):
    _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", freq=440), load=True)
    _upload(client, _mp3(tmp_path, "b.mp3", title="Beta", freq=550), load=False)

    plan = client.get("/music/device-plan").json()

    assert [i["title"] for i in plan["add"]] == ["Alpha"]
    assert plan["remove"] == []
    assert plan["folder"] == "Music"


def test_plan_carries_a_url_rather_than_inline_audio(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True).json()["added"][0]["id"]

    item = client.get("/music/device-plan").json()["add"][0]

    # Audio is fetched, never embedded — base64 in the plan would inflate a
    # whole library by a third and buffer it all in memory.
    assert item["url"] == f"/music/tracks/{tid}/audio"
    assert "data_b64" not in item and "fit_b64" not in item
    assert item["filename"].endswith(".mp3")


def test_a_pushed_track_stops_being_offered(client, user, tmp_path):
    _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True)
    item = client.get("/music/device-plan").json()["add"][0]

    marked = client.post("/music/mark-uploaded", json={"items": [item]})

    assert marked.json()["marked"] == 1
    plan = client.get("/music/device-plan").json()
    assert plan["add"] == []
    assert plan["on_device_after"] == 1


def test_unloading_a_pushed_track_queues_it_for_removal(client, user, tmp_path):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True).json()["added"][0]["id"]
    item = client.get("/music/device-plan").json()["add"][0]
    client.post("/music/mark-uploaded", json={"items": [item]})

    client.post("/music/tracks/load", json={"ids": [tid], "load": False})

    plan = client.get("/music/device-plan").json()
    assert [i["id"] for i in plan["remove"]] == [tid]
    assert plan["remove"][0]["filename"] == item["filename"]
    assert plan["add"] == []


def test_marking_deleted_clears_the_removal(client, user, tmp_path, db):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True).json()["added"][0]["id"]
    item = client.get("/music/device-plan").json()["add"][0]
    client.post("/music/mark-uploaded", json={"items": [item]})
    client.post("/music/tracks/load", json={"ids": [tid], "load": False})

    client.post("/music/mark-deleted", json={"ids": [tid]})

    assert client.get("/music/device-plan").json()["remove"] == []
    # The row survives — the track is still in the library, just not carried.
    assert db.get(MusicTrack, tid) is not None


def test_deleting_a_track_on_the_watch_defers_until_the_file_is_gone(client, user, tmp_path, db):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True).json()["added"][0]["id"]
    item = client.get("/music/device-plan").json()["add"][0]
    client.post("/music/mark-uploaded", json={"items": [item]})

    resp = client.delete(f"/music/tracks/{tid}")

    # Deleting the row now would strand the file on the watch with nothing left
    # to describe it, so removal is queued first.
    assert resp.json() == {"deleted": False, "pending_device_removal": True}
    assert [i["id"] for i in client.get("/music/device-plan").json()["remove"]] == [tid]

    client.post("/music/mark-deleted", json={"ids": [tid]})
    assert db.get(MusicTrack, tid) is None


def test_deleting_a_track_never_pushed_removes_it_immediately(client, user, tmp_path, db):
    tid = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha")).json()["added"][0]["id"]

    resp = client.delete(f"/music/tracks/{tid}")

    assert resp.json()["deleted"] is True
    assert db.get(MusicTrack, tid) is None


def test_filenames_are_unique_and_ascii_safe(client, user, tmp_path):
    _upload(client, _mp3(tmp_path, "a.mp3", title="Song / Name?", artist="Ärtist", freq=440), load=True)
    _upload(client, _mp3(tmp_path, "b.mp3", title="Song / Name?", artist="Ärtist", freq=550), load=True)

    names = [i["filename"] for i in client.get("/music/device-plan").json()["add"]]

    assert len(set(names)) == 2
    for name in names:
        assert name.isascii()
        assert not set(name) & set('/\\:*?"<>|')


# ── playlists ────────────────────────────────────────────────────────────────

def test_playlist_is_rendered_as_m3u_naming_only_carried_tracks(client, user, tmp_path):
    a = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", freq=440), load=True).json()["added"][0]
    b = _upload(client, _mp3(tmp_path, "b.mp3", title="Beta", freq=550), load=False).json()["added"][0]
    pid = client.post("/music/playlists", json={"name": "Run"}).json()["id"]
    client.put(f"/music/playlists/{pid}/tracks", json={"track_ids": [a["id"], b["id"]]})
    client.patch(f"/music/playlists/{pid}", json={"load_to_device": True})

    plan = client.get("/music/device-plan").json()
    playlist = plan["playlists"][0]

    assert playlist["filename"].endswith(".m3u")
    # Flagging the playlist pulled Beta along with it, so both are carried and
    # both belong in the file. An .m3u naming a file that was never pushed is
    # what breaks playlists on some firmware.
    lines = playlist["content"].strip().split("\n")
    assert len(lines) == 2
    pushed = {i["filename"] for i in plan["add"]}
    assert set(lines) == pushed


def test_flagging_a_playlist_carries_its_tracks(client, user, tmp_path):
    a = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", freq=440), load=False).json()["added"][0]
    pid = client.post("/music/playlists", json={"name": "Run"}).json()["id"]
    client.put(f"/music/playlists/{pid}/tracks", json={"track_ids": [a["id"]]})

    client.patch(f"/music/playlists/{pid}", json={"load_to_device": True})

    assert [i["title"] for i in client.get("/music/device-plan").json()["add"]] == ["Alpha"]


def test_an_empty_playlist_is_not_written_to_the_watch(client, user, tmp_path):
    pid = client.post("/music/playlists", json={"name": "Empty"}).json()["id"]
    client.patch(f"/music/playlists/{pid}", json={"load_to_device": True})

    assert client.get("/music/device-plan").json()["playlists"] == []


def test_listing_playlists_returns_each_with_its_tracks_in_order(client, user, tmp_path):
    """GET /music/playlists — the library screen's own read.

    Pins the shape before the query behind it changes: a playlist carries its
    track ids in playlist order, which is the `position` column and not the
    order the tracks were uploaded in.
    """
    a = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", freq=440)).json()["added"][0]
    b = _upload(client, _mp3(tmp_path, "b.mp3", title="Beta", freq=550)).json()["added"][0]
    c = _upload(client, _mp3(tmp_path, "c.mp3", title="Gamma", freq=660)).json()["added"][0]

    run = client.post("/music/playlists", json={"name": "Run"}).json()["id"]
    rest = client.post("/music/playlists", json={"name": "Rest"}).json()["id"]
    # Deliberately not ascending id order — position is what should decide.
    client.put(f"/music/playlists/{run}/tracks", json={"track_ids": [c["id"], a["id"]]})
    client.put(f"/music/playlists/{rest}/tracks", json={"track_ids": [b["id"]]})
    client.patch(f"/music/playlists/{run}", json={"load_to_device": True})

    body = client.get("/music/playlists").json()["playlists"]

    by_name = {p["name"]: p for p in body}
    assert set(by_name) == {"Run", "Rest"}
    assert by_name["Run"]["track_ids"] == [c["id"], a["id"]]
    assert by_name["Rest"]["track_ids"] == [b["id"]]
    assert by_name["Run"]["load_to_device"] is True
    assert by_name["Rest"]["load_to_device"] is False
    assert by_name["Run"]["id"] == run


def test_listing_playlists_includes_an_empty_one(client, user):
    """An empty playlist still exists and still has to be listed — it is how a
    user gets back to the one they just made and have not filled yet."""
    pid = client.post("/music/playlists", json={"name": "Empty"}).json()["id"]

    body = client.get("/music/playlists").json()["playlists"]

    assert [p["id"] for p in body] == [pid]
    assert body[0]["track_ids"] == []


def test_listing_playlists_does_not_leak_another_users(client, user, db, tmp_path):
    """Scoped to the caller. The N+1 this replaced looked up tracks per
    playlist, so the per-playlist filter was the only thing keeping one
    account's playlists out of another's list."""
    from app.models.activity import User as UserModel
    from app.models.music import MusicPlaylist

    mine = client.post("/music/playlists", json={"name": "Mine"}).json()["id"]
    other = UserModel(name="Other")
    db.add(other)
    db.flush()
    db.add(MusicPlaylist(user_id=other.id, name="Theirs", source="local"))
    db.commit()

    body = client.get("/music/playlists").json()["playlists"]

    assert [p["id"] for p in body] == [mine]


def test_listing_playlists_costs_a_fixed_number_of_queries(client, user, tmp_path):
    """The reason this endpoint was rewritten: it ran one query per playlist,
    so opening the library screen cost N+1 round trips and got slower with
    every playlist added. The count must not scale with the number of them.
    """
    from sqlalchemy import event
    from app.database import engine

    a = _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha", freq=440)).json()["added"][0]
    for n in range(6):
        pid = client.post("/music/playlists", json={"name": f"P{n}"}).json()["id"]
        client.put(f"/music/playlists/{pid}/tracks", json={"track_ids": [a["id"]]})

    seen = []

    def count(conn, cursor, statement, params, context, executemany):
        if statement.lstrip().upper().startswith("SELECT"):
            seen.append(statement)

    event.listen(engine, "before_cursor_execute", count)
    try:
        body = client.get("/music/playlists").json()["playlists"]
    finally:
        event.remove(engine, "before_cursor_execute", count)

    assert len(body) == 6
    # Two for the payload (playlists, then their tracks in one go); the rest of
    # the allowance is the auth/user lookups the dependency does on any request.
    assert len(seen) <= 5, f"{len(seen)} selects for 6 playlists:\n" + "\n".join(seen)


# ── device limits ────────────────────────────────────────────────────────────

def test_plan_reports_the_device_file_ceiling_and_projected_count(client, user, tmp_path):
    _upload(client, _mp3(tmp_path, "a.mp3", title="Alpha"), load=True)

    plan = client.get("/music/device-plan").json()

    # The client checks this before starting rather than discovering the cap
    # part-way through a multi-megabyte transfer.
    assert plan["device_file_limit"] == 500
    assert plan["on_device_after"] == 1
    assert plan["bytes_to_add"] > 0
