# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Saved places: the CRUD surface, and the whole-set watch reconciliation.

The reconciliation half is where the interesting behaviour is. A watch keeps
every saved place in ONE Locations.fit, so the unit of work is the set rather
than the waypoint, and the tests below are mostly about that distinction: what
gets offered when one point of five changes, and what happens to the four that
did not.
"""
import base64

from app.models.waypoint import Waypoint


def _make(client, name="Camp", lat=40.0, lng=-150.0, **kwargs):
    body = {"name": name, "lat": lat, "lng": lng, **kwargs}
    resp = client.post("/maps/waypoints", json=body)
    assert resp.status_code == 201, resp.text
    return resp.json()


def _uploads(client, bluetooth=False):
    query = "?bluetooth=true" if bluetooth else ""
    return [i for i in client.get(f"/device-sync/upload-list{query}").json()["items"]
            if i["type"] == "waypoint"]


def _deletes(client):
    return [i for i in client.get("/device-sync/delete-list").json()["items"]
            if i["type"] == "waypoint"]


def _push(client):
    """Do what a synced device does: take the offer, then acknowledge it."""
    offered = _uploads(client)
    assert offered, "expected a Locations.fit to be offered"
    item = offered[0]
    client.post("/device-sync/mark-uploaded", json={"items": [{
        "type": "waypoint", "id": item["id"],
        "filename": item["filename"], "ids": item["ids"],
    }]})
    return item


class TestWaypointCrud:
    def test_create_read_update_delete(self, client, user):
        created = _make(client, name="Water", icon="water", ele_m=2500.0)
        assert created["name"] == "Water"
        assert created["on_watch"] is False

        listed = client.get("/maps/waypoints").json()
        assert [w["id"] for w in listed] == [created["id"]]

        patched = client.patch(f"/maps/waypoints/{created['id']}",
                               json={"color": "#ff0000"}).json()
        assert patched["color"] == "#ff0000"
        assert patched["name"] == "Water"        # omitted means leave alone

        assert client.delete(f"/maps/waypoints/{created['id']}").status_code == 204
        assert client.get("/maps/waypoints").json() == []

    def test_another_users_waypoint_is_not_found(self, client, db, user):
        # A real second user, not just an id nobody owns. The waypoints table
        # has a foreign key to users, so an invented id is a row Postgres
        # refuses outright — and a row that cannot exist proves nothing about
        # what happens when someone else's genuinely does.
        from app.models.activity import User as UserModel

        other_user = UserModel(name="Someone Else")
        db.add(other_user)
        db.flush()
        other = Waypoint(user_id=other_user.id, name="Theirs", lat=1.0, lng=1.0)
        db.add(other)
        db.commit()
        assert client.get(f"/maps/waypoints/{other.id}").status_code in (404, 405)
        assert client.patch(f"/maps/waypoints/{other.id}",
                            json={"name": "Mine now"}).status_code == 404
        assert client.delete(f"/maps/waypoints/{other.id}").status_code == 404


class TestWatchReconciliation:
    def test_nothing_is_offered_until_a_waypoint_is_flagged(self, client, user):
        _make(client)
        assert _uploads(client) == []

    def test_a_flagged_waypoint_is_offered_as_one_locations_file(self, client, user):
        first = _make(client, name="Camp", load_to_device=True)
        second = _make(client, name="Spring", lat=40.1, load_to_device=True)

        offered = _uploads(client)
        assert len(offered) == 1, "one file covers every saved place, not one per point"
        item = offered[0]
        assert item["filename"] == "Locations.fit"
        assert sorted(item["ids"]) == sorted([first["id"], second["id"]])
        assert base64.b64decode(item["fit_b64"])[8:12] == b".FIT"

    def test_the_file_goes_to_the_locations_folder(self, client, user):
        _make(client, load_to_device=True)
        assert _uploads(client)[0]["folder"] == "GARMIN/Locations"

    def test_nothing_is_re_offered_once_the_set_matches(self, client, user):
        _make(client, load_to_device=True)
        _push(client)
        assert _uploads(client) == []

    def test_flagging_another_waypoint_rebuilds_the_whole_file(self, client, user):
        first = _make(client, name="Camp", load_to_device=True)
        _push(client)

        second = _make(client, name="Spring", lat=40.1, load_to_device=True)
        offered = _uploads(client)
        assert len(offered) == 1
        # The point already on the watch has to be in the file again — the new
        # file replaces the old one wholesale, so omitting it would delete it.
        assert sorted(offered[0]["ids"]) == sorted([first["id"], second["id"]])

    def test_editing_a_waypoint_on_the_watch_makes_the_file_stale(self, client, user):
        created = _make(client, name="Camp", load_to_device=True)
        _push(client)
        assert client.get("/maps/waypoints").json()[0]["on_watch"] is True

        client.patch(f"/maps/waypoints/{created['id']}", json={"name": "Camp 2"})
        assert _uploads(client), "a renamed place must be pushed again"

    def test_recolouring_does_not_disturb_the_watch(self, client, user):
        # Colour is not in the FIT location record, so the file on the wrist is
        # unchanged and re-pushing it would be pure churn.
        created = _make(client, load_to_device=True)
        _push(client)
        client.patch(f"/maps/waypoints/{created['id']}", json={"color": "#00ff00"})
        assert _uploads(client) == []

    def test_unflagging_one_of_two_rewrites_without_it(self, client, user):
        first = _make(client, name="Camp", load_to_device=True)
        second = _make(client, name="Spring", lat=40.1, load_to_device=True)
        _push(client)

        client.patch(f"/maps/waypoints/{second['id']}", json={"load_to_device": False})
        offered = _uploads(client)
        assert offered[0]["ids"] == [first["id"]]

        _push(client)
        by_id = {w["id"]: w for w in client.get("/maps/waypoints").json()}
        assert by_id[first["id"]]["on_watch"] is True
        # Dropped from the file that replaced the old one, so it is off the
        # watch even though nothing deleted it individually.
        assert by_id[second["id"]]["on_watch"] is False

    def test_unflagging_everything_asks_for_the_file_to_be_removed(self, client, user):
        created = _make(client, load_to_device=True)
        _push(client)

        client.patch(f"/maps/waypoints/{created['id']}", json={"load_to_device": False})
        assert _uploads(client) == [], "nothing to write when nothing is loaded"
        pending = _deletes(client)
        assert len(pending) == 1
        assert pending[0]["filename"] == "Locations.fit"
        assert pending[0]["folder"] == "GARMIN/Locations"

        client.post("/device-sync/mark-deleted",
                    json={"items": [{"type": "waypoint", "id": 0}]})
        assert _deletes(client) == []
        assert client.get("/maps/waypoints").json()[0]["on_watch"] is False

    def test_bluetooth_clears_by_writing_an_empty_file(self, client, user):
        # BLE can put a file on a watch and has no operation for removing one,
        # so "delete every place" has to be expressed as a write. An empty
        # locations file is the same outcome by a route the link supports.
        created = _make(client, load_to_device=True)
        _push(client)

        client.patch(f"/maps/waypoints/{created['id']}", json={"load_to_device": False})

        offered = _uploads(client, bluetooth=True)
        assert len(offered) == 1
        assert offered[0]["filename"] == "Locations.fit"
        assert offered[0]["folder"] == "GARMIN/Locations"
        # Nothing is in it, and the empty id list is how the mark step knows
        # that everything previously on the watch has come off.
        assert offered[0]["ids"] == []
        assert base64.b64decode(offered[0]["fit_b64"])[:4]

        client.post("/device-sync/mark-uploaded", json={"items": [{
            "type": "waypoint", "id": 0,
            "filename": offered[0]["filename"], "ids": [],
        }]})
        assert client.get("/maps/waypoints").json()[0]["on_watch"] is False
        assert _uploads(client, bluetooth=True) == [], "nothing left to clear"

    def test_a_cable_caller_is_still_told_to_delete_the_file(self, client, user):
        # The flag is the whole difference. Over USB the file itself can be
        # removed, and writing an empty one instead would leave litter on the
        # watch that nothing ever cleans up.
        created = _make(client, load_to_device=True)
        _push(client)
        client.patch(f"/maps/waypoints/{created['id']}", json={"load_to_device": False})

        assert _uploads(client) == []
        assert len(_deletes(client)) == 1

    def test_clearing_is_not_offered_while_places_are_still_loaded(self, client, user):
        first = _make(client, name="Camp", load_to_device=True)
        _make(client, name="Spring", lat=40.1, load_to_device=True)
        _push(client)

        client.patch(f"/maps/waypoints/{first['id']}", json={"load_to_device": False})

        # One place remains, so this is an ordinary rewrite — and an empty file
        # offered alongside it could delete the survivor.
        offered = _uploads(client, bluetooth=True)
        assert len(offered) == 1
        assert offered[0]["ids"] != []

    def test_no_delete_is_queued_while_a_rewrite_is_pending(self, client, user):
        # Deleting a file we are about to replace loses every place if the sync
        # is interrupted between the two steps.
        _make(client, name="Camp", load_to_device=True)
        _push(client)
        _make(client, name="Spring", lat=40.1, load_to_device=True)
        assert _uploads(client)
        assert _deletes(client) == []

    def test_marking_records_only_what_the_file_held(self, client, user):
        first = _make(client, name="Camp", load_to_device=True)
        item = _uploads(client)[0]

        # Flagged after the offer was built, so it is in no file yet. Marking it
        # delivered here would mean it is never offered again.
        late = _make(client, name="Late", lat=40.2, load_to_device=True)
        client.post("/device-sync/mark-uploaded", json={"items": [{
            "type": "waypoint", "id": item["id"],
            "filename": item["filename"], "ids": item["ids"],
        }]})

        by_id = {w["id"]: w for w in client.get("/maps/waypoints").json()}
        assert by_id[first["id"]]["on_watch"] is True
        assert by_id[late["id"]]["on_watch"] is False
        assert _uploads(client), "the late waypoint still needs a push"


class TestIngestingWhatTheWatchHolds:
    """Reading a watch's own Locations.fit back.

    Places made on the watch itself are the case Tracks could not see at all —
    they exist, they take up the same file, and a sync that ignored them would
    overwrite them out of existence on its next push.
    """

    def _fit(self, places):
        from app.calculators.fit_locations import generate_locations_fit
        return generate_locations_fit(places)

    def _ingest(self, db, user, places):
        from app.routes.waypoints_sync import ingest_locations
        return ingest_locations(db, user.id, self._fit(places))

    def test_a_place_made_on_the_watch_becomes_a_waypoint(self, client, db, user):
        result = self._ingest(db, user, [
            {"name": "Camp by the lake", "lat": 40.5, "lng": -150.5,
             "ele_m": 2800.0, "icon": "camp"},
        ])
        assert result == {"found": 1, "imported": 1}

        listed = client.get("/maps/waypoints").json()
        assert len(listed) == 1
        assert listed[0]["name"] == "Camp by the lake"
        assert listed[0]["icon"] == "camp"
        # It is already there, so it must stay there: arriving unflagged would
        # make the very next sync rewrite the file without it.
        assert listed[0]["load_to_device"] is True
        assert listed[0]["on_watch"] is True

    def test_a_place_we_already_have_is_not_duplicated(self, client, db, user):
        _make(client, name="Camp", lat=40.5, lng=-150.5, load_to_device=True)
        _push(client)

        result = self._ingest(db, user, [
            {"name": "Camp", "lat": 40.5, "lng": -150.5, "icon": "marker"},
        ])
        assert result == {"found": 1, "imported": 0}
        assert len(client.get("/maps/waypoints").json()) == 1

    def test_a_place_deleted_on_the_watch_stops_being_on_the_watch(self, client, db, user):
        kept = _make(client, name="Camp", lat=40.5, lng=-150.5, load_to_device=True)
        gone = _make(client, name="Spring", lat=40.6, lng=-150.6, load_to_device=True)
        _push(client)
        assert all(w["on_watch"] for w in client.get("/maps/waypoints").json())

        # The watch comes back holding only one of them.
        self._ingest(db, user, [
            {"name": "Camp", "lat": 40.5, "lng": -150.5, "icon": "marker"},
        ])

        by_id = {w["id"]: w for w in client.get("/maps/waypoints").json()}
        assert by_id[kept["id"]]["on_watch"] is True
        assert by_id[gone["id"]]["on_watch"] is False

    def test_an_empty_file_means_the_watch_was_wiped(self, client, db, user):
        created = _make(client, load_to_device=True)
        _push(client)

        assert self._ingest(db, user, []) == {"found": 0, "imported": 0}
        assert client.get("/maps/waypoints").json()[0]["on_watch"] is False
        assert created["id"] is not None

    def test_a_place_moved_on_the_watch_is_a_different_place(self, client, db, user):
        # Same name, a kilometre away. Matching on name alone would silently
        # relocate the original rather than record a new mark.
        _make(client, name="Camp", lat=40.5, lng=-150.5, load_to_device=True)
        _push(client)

        self._ingest(db, user, [
            {"name": "Camp", "lat": 40.5, "lng": -150.5, "icon": "marker"},
            {"name": "Camp", "lat": 40.51, "lng": -150.5, "icon": "marker"},
        ])
        assert len(client.get("/maps/waypoints").json()) == 2
