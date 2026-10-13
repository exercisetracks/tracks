# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Collections: track folders that hold saved places as well as tracks.

Each collection is a collection of its own on the watch, so a place has to be
able to belong to one, follow its collection's "on the watch" switch, and land
back in the default collection — visibly, to phones — when its collection is
deleted.
"""
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
