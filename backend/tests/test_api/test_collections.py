# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Collections: track folders that hold saved places as well as tracks.

Each collection is a collection of its own on the watch, so a place has to be
able to belong to one, follow its collection's "on the watch" switch, and land
back in the default collection — visibly, to phones — when its collection is
deleted.
"""
from app.models.activity import User
from app.models.custom_track import CustomTrackFolder
from app.models.waypoint import Waypoint

from ..test_sync.helpers import Phone, latest, pull_all, push, setup_admin

A = Phone("000000000000000c")


def test_a_place_synced_into_a_collection_is_filed_there(client, user, db):
    h = setup_admin(client)
    folder = A.change("track_folder", name="Alps", color="#16a34a", load_to_device=False)
    push(client, h, folder)
    place = A.change("waypoint", name="Hut", lat=46.5, lng=7.9, folder_uid=folder["uid"])
    assert push(client, h, place)["results"][0]["status"] == "applied"
    row = db.query(Waypoint).filter_by(uid=place["uid"]).one()
    assert row.folder_id == db.query(CustomTrackFolder).filter_by(uid=folder["uid"]).one().id


def test_a_collections_watch_switch_reaches_its_places(client, user, db):
    created = client.post("/maps/folders", json={"name": "Alps"}).json()
    place = client.post("/maps/waypoints", json={"name": "Hut", "lat": 46.5, "lng": 7.9}).json()
    row = db.get(Waypoint, place["id"])
    row.folder_id = created["id"]
    db.commit()

    resp = client.patch(f"/maps/folders/{created['id']}", json={"load_to_device": True})
    assert resp.json()["place_count"] == 1
    db.refresh(row)
    assert row.load_to_device is True


def test_deleting_a_collection_moves_its_places_to_the_default_and_phones_hear_of_it(client, user, db):
    """Left to the foreign key's SET NULL, the move is never stamped, and a
    phone goes on filing the place under a collection that no longer exists."""
    h = setup_admin(client)
    folder = A.change("track_folder", name="Alps", color="#16a34a", load_to_device=False)
    push(client, h, folder)
    place = A.change("waypoint", name="Hut", lat=46.5, lng=7.9, folder_uid=folder["uid"])
    push(client, h, place)
    folder_id = db.query(CustomTrackFolder).filter_by(uid=folder["uid"]).one().id

    assert client.delete(f"/maps/folders/{folder_id}", headers=h).status_code == 204
    assert db.query(Waypoint).filter_by(uid=place["uid"]).one().folder_id is None
    changes, _ = pull_all(client, h)
    assert latest(changes, "waypoint", place["uid"])["fields"]["folder_uid"][0] is None


def test_a_place_can_be_created_in_a_collection_and_moved_out_again(client, user, db):
    folder = client.post("/maps/folders", json={"name": "Alps"}).json()
    place = client.post("/maps/waypoints", json={
        "name": "Hut", "lat": 46.5, "lng": 7.9, "folder_id": folder["id"]}).json()
    assert place["folder_id"] == folder["id"]

    moved = client.patch(f"/maps/waypoints/{place['id']}", json={"clear_folder": True}).json()
    assert moved["folder_id"] is None


def test_a_place_moved_into_a_collection_on_the_watch_goes_to_the_watch(client, user, db):
    """The rule a track follows: a collection whose switch is on keeps
    everything in it on the watch, including what arrives later."""
    folder = client.post("/maps/folders", json={"name": "Alps", "load_to_device": True}).json()
    place = client.post("/maps/waypoints", json={"name": "Hut", "lat": 46.5, "lng": 7.9}).json()
    assert place["load_to_device"] is False

    moved = client.patch(f"/maps/waypoints/{place['id']}", json={"folder_id": folder["id"]}).json()
    assert moved["folder_id"] == folder["id"]
    assert moved["load_to_device"] is True


def test_a_place_cannot_be_filed_in_someone_elses_collection(client, user, db):
    stranger = User(name="Someone else")
    db.add(stranger)
    db.flush()
    other = CustomTrackFolder(user_id=stranger.id, name="Not yours")
    db.add(other)
    db.flush()
    resp = client.post("/maps/waypoints", json={
        "name": "Hut", "lat": 46.5, "lng": 7.9, "folder_id": other.id})
    assert resp.status_code == 404


def test_a_track_can_be_created_straight_into_a_collection(client, user, db):
    folder = client.post("/maps/folders", json={"name": "Alps", "load_to_device": True}).json()
    track = client.post("/maps/courses", json={
        "name": "Ridge", "folder_id": folder["id"],
        "coords": [[7.9, 46.5], [7.91, 46.51]]}).json()
    assert track["folder_id"] == folder["id"]
    # Drawn into a collection on the watch, it is wanted on the watch.
    assert track["load_to_device"] is True
