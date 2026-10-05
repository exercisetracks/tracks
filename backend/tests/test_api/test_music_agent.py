# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The cable path's half of music — what the garmin-sync container talks to."""

import shutil
import subprocess

import pytest

from app.models.sync_agents import SyncAgent
from app.services.sync_agent_auth import generate_pairing_token

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not available")


def _mp3(tmp_path, name, *, title, freq=440):
    path = tmp_path / name
    subprocess.run([
        "ffmpeg", "-nostdin", "-y", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration=1",
        "-ar", "44100", "-ac", "2", "-b:a", "128k",
        "-metadata", f"title={title}", "-metadata", "artist=A",
        "-id3v2_version", "3", str(path),
    ], capture_output=True, check=True)
    return path


def _add_track(client, tmp_path, title, freq=440, load=True):
    path = _mp3(tmp_path, f"{freq}.mp3", title=title, freq=freq)
    with path.open("rb") as fh:
        resp = client.post("/music/tracks", files={"files": (path.name, fh, "audio/mpeg")},
                           data={"load_to_device": str(load).lower()})
    return resp.json()["added"][0]


@pytest.fixture
def dock(db, user):
    """A paired cable-sync agent, scoped to this user."""
    raw, token_hash = generate_pairing_token()
    db.add(SyncAgent(user_id=user.id, kind="garmin-usb", label="dock", token_hash=token_hash))
    db.commit()
    return {"Authorization": f"Bearer {raw}"}


def test_upload_list_names_the_root_music_folder(client, user, dock, tmp_path):
    _add_track(client, tmp_path, "Alpha")

    body = client.get("/music/sync/upload-list", headers=dock).json()

    # Storage root, a sibling of GARMIN/ — files written inside GARMIN/Music
    # exist on disk and are never seen by the watch's scanner.
    assert body["folder"] == "Music"
    assert [i["title"] for i in body["items"]] == ["Alpha"]


def test_audio_is_fetched_per_track_not_carried_inline(client, user, dock, tmp_path):
    track = _add_track(client, tmp_path, "Alpha")

    item = client.get("/music/sync/upload-list", headers=dock).json()["items"][0]

    # A library-sized JSON body of base64 would be a third larger than the
    # files and buffered whole at both ends.
    assert "fit_b64" not in item and "data_b64" not in item
    resp = client.get(f"/music/sync/audio/{track['id']}", headers=dock)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "audio/mpeg"


def test_marking_uploaded_clears_the_list(client, user, dock, tmp_path):
    _add_track(client, tmp_path, "Alpha")
    item = client.get("/music/sync/upload-list", headers=dock).json()["items"][0]

    client.post("/music/sync/mark-uploaded",
                json={"items": [{"id": item["id"], "filename": item["filename"]}]}, headers=dock)

    assert client.get("/music/sync/upload-list", headers=dock).json()["items"] == []


def test_unloading_queues_a_removal_for_the_dock(client, user, dock, tmp_path):
    track = _add_track(client, tmp_path, "Alpha")
    item = client.get("/music/sync/upload-list", headers=dock).json()["items"][0]
    client.post("/music/sync/mark-uploaded",
                json={"items": [{"id": item["id"], "filename": item["filename"]}]}, headers=dock)

    client.post("/music/tracks/load", json={"ids": [track["id"]], "load": False})

    body = client.get("/music/sync/delete-list", headers=dock).json()
    assert [i["id"] for i in body["items"]] == [track["id"]]


def test_these_routes_need_a_token_at_all(client, user):
    assert client.get("/music/sync/upload-list").status_code == 401


def test_playlists_ride_along_with_the_upload_list(client, user, dock, tmp_path):
    a = _add_track(client, tmp_path, "Alpha", freq=440)
    pid = client.post("/music/playlists", json={"name": "Run"}).json()["id"]
    client.put(f"/music/playlists/{pid}/tracks", json={"track_ids": [a["id"]]})
    client.patch(f"/music/playlists/{pid}", json={"load_to_device": True})

    body = client.get("/music/sync/upload-list", headers=dock).json()

    assert len(body["playlists"]) == 1
    assert body["playlists"][0]["filename"].endswith(".m3u")
