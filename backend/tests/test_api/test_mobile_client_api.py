# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The API surface a native mobile client depends on.

These endpoints were added or tightened because an Android app can't make the
assumptions the bundled web frontend can: it isn't served by the same
deployment (so it can disagree with the server about what exists), it isn't on
the LAN (so unbounded payloads cost real time and data), and it retries in the
background (so a global rate limiter is a liability).
"""
from datetime import datetime, timedelta

from app.models.activity import Activity, DataPoint
from app.models.imports import PendingImport
from app.models.user_keys import UserKey
from app.services import crypto_context, user_crypto


def _auth(client, db, user, password="testpass123"):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    headers = {"Authorization": f"Bearer {resp.json()['access_token']}"}
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    material = user_crypto.unwrap_with_password(password, uk)
    return headers, material


def _make_activity_with_track(db, user, material, *, lat0=40.0, lng0=-150.0, npts=6, step=0.001,
                              started_at=datetime(2024, 6, 1, 8), sport="running", heart_rate=140):
    a = Activity(user_id=user.id, device_id=1, sport=sport,
                 started_at=started_at, duration_seconds=3600,
                 distance_meters=1000.0)
    db.add(a)
    db.flush()
    t0 = datetime(2024, 6, 1, 8, 0, 0)
    for i in range(npts):
        db.add(DataPoint(id=a.id * 100000 + i, activity_id=a.id,
                         recorded_at=t0 + timedelta(seconds=i * 10),
                         lat=lat0 + i * step, lng=lng0 + i * step,
                         altitude=1500.0 + i * 5, heart_rate=heart_rate, speed=3.0))
    token = crypto_context.set_current_key(material)
    try:
        db.commit()
    finally:
        crypto_context.reset_current_key(token)
    db.refresh(a)
    return a


class TestCapabilities:
    """A client must be able to ask what it's talking to before it has a token."""

    def test_reachable_without_auth(self, client, user, db):
        _auth(client, db, user)  # takes the app OUT of open-access mode
        resp = client.get("/capabilities")
        assert resp.status_code == 200

    def test_declares_versions_and_features(self, client, user):
        body = client.get("/capabilities").json()
        assert body["app"] == "tracks"
        assert isinstance(body["api_version"], int)
        # The floor must never exceed what this server actually speaks, or every
        # client would correctly conclude it is too old for a server that is fine.
        assert body["min_client_api_version"] <= body["api_version"]
        assert "device_sync" in body["features"]
        assert "vault_lock" in body["features"]

    def test_limits_match_what_endpoints_enforce(self, client):
        from app.api.activities.detail import _MAX_TRACK_POINTS

        limits = client.get("/capabilities").json()["limits"]
        # A client that clamps to an advertised limit the server doesn't honour
        # gets a 422 it was specifically trying to avoid.
        assert limits["track_max_points"] == _MAX_TRACK_POINTS


class TestTrackMaxPoints:
    """`chart_resolution` is a server-side preference; a phone needs a per-request say."""

    def test_caps_returned_points(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, npts=40)
        resp = client.get(f"/activities/{a.id}/track?max_points=100", headers=headers)
        assert resp.status_code == 200
        assert len(resp.json()) <= 100

    def test_downsamples_below_the_user_setting(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, npts=400)

        # Default resolution is "high" (3000), so the full track comes back whole.
        full = client.get(f"/activities/{a.id}/track", headers=headers).json()
        assert len(full) == 400

        capped = client.get(f"/activities/{a.id}/track?max_points=100", headers=headers)
        assert len(capped.json()) == 100

    def test_rejects_out_of_range(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material)
        assert client.get(f"/activities/{a.id}/track?max_points=1", headers=headers).status_code == 422
        assert client.get(f"/activities/{a.id}/track?max_points=999999", headers=headers).status_code == 422


class TestHeatmapViewport:
    """The single biggest payload in the API; bbox/zoom are how a phone bounds it."""

    def test_bbox_excludes_tracks_outside_it(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, lat0=40.0, lng0=-150.0)
        _make_activity_with_track(db, user, material, lat0=10.0, lng0=10.0)

        everything = client.get("/activities/heatmap", headers=headers).json()
        assert len(everything) == 2

        inside = client.get(
            "/activities/heatmap?bbox=-151,39,-149,41", headers=headers
        ).json()
        assert len(inside) == 1

    def test_bbox_narrows_the_cached_payload_too(self, client, user, db):
        """The unfiltered response is cached as pre-serialised bytes. A bbox
        query must narrow that cache rather than bypass it — recomputing would
        mean re-decrypting every GPS point in the user's history."""
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, lat0=40.0, lng0=-150.0)
        _make_activity_with_track(db, user, material, lat0=10.0, lng0=10.0)

        assert len(client.get("/activities/heatmap", headers=headers).json()) == 2  # populates cache
        cached_and_filtered = client.get(
            "/activities/heatmap?bbox=-151,39,-149,41", headers=headers
        ).json()
        assert len(cached_and_filtered) == 1

    def test_bbox_does_not_poison_the_cache(self, client, user, db):
        """A subsequent unfiltered request must still see everything."""
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, lat0=40.0, lng0=-150.0)
        _make_activity_with_track(db, user, material, lat0=10.0, lng0=10.0)

        client.get("/activities/heatmap?bbox=-151,39,-149,41", headers=headers)
        assert len(client.get("/activities/heatmap", headers=headers).json()) == 2

    def test_low_zoom_drops_sub_pixel_detail(self, client, user, db):
        headers, material = _auth(client, db, user)
        # ~11 m apart: visible at z14, indistinguishable at z4.
        a = _make_activity_with_track(db, user, material, npts=30, step=0.0001)

        detailed = client.get("/activities/heatmap?zoom=16", headers=headers).json()
        coarse = client.get("/activities/heatmap?zoom=4", headers=headers).json()
        detailed_pts = sum(len(t) for t in detailed)
        coarse_pts = sum(len(t) for t in coarse)
        assert coarse_pts < detailed_pts
        assert a.id  # track was actually created

    def test_malformed_bbox_is_422_not_500(self, client, user, db):
        headers, _ = _auth(client, db, user)
        for bad in ("not-a-bbox", "1,2,3", "-200,0,10,10", "10,10,-10,-10"):
            resp = client.get(f"/activities/heatmap?bbox={bad}", headers=headers)
            assert resp.status_code == 422, f"{bad!r} returned {resp.status_code}"


class TestHeatmapCache:
    """Every filter the dashboard sends is served from one cached pass.

    Decrypting lat/lng is the whole cost of this endpoint. Date windows used to
    bypass the cache entirely — and the dashboard heatmap now sends one on
    every load, because it follows the period pills — and each mode was built
    separately, so a cold dashboard decrypted every point four times.
    """

    def _counting_builds(self, monkeypatch):
        from app.api.activities import heatmap_cache
        calls = []
        real = heatmap_cache.build_heatmap_bases

        def counted(*a, **kw):
            calls.append(1)
            return real(*a, **kw)

        monkeypatch.setattr(heatmap_cache, "build_heatmap_bases", counted)
        return calls

    def test_a_window_filters_by_start_date_inclusively(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, started_at=datetime(2024, 5, 31, 23, 0))
        _make_activity_with_track(db, user, material, lat0=10.0, started_at=datetime(2024, 6, 1, 0, 30))
        _make_activity_with_track(db, user, material, lat0=20.0, started_at=datetime(2024, 6, 3, 12, 0))

        def n(qs):
            return len(client.get(f"/activities/heatmap?{qs}", headers=headers).json())

        assert n("after=2024-06-01") == 2
        assert n("before=2024-06-01") == 2      # the whole of the 1st is in
        assert n("after=2024-06-02&before=2024-06-03") == 1
        assert n("") == 3

    def test_every_window_sport_and_mode_comes_from_one_build(self, client, user, db, monkeypatch):
        calls = self._counting_builds(monkeypatch)
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        _make_activity_with_track(db, user, material, lat0=10.0, sport="cycling")

        for qs in ("", "after=2024-01-01", "after=2024-05-01&sport=cycling",
                   "mode=pace", "mode=heartrate&after=2024-01-01", "mode=gradient&sport=running"):
            assert client.get(f"/activities/heatmap?{qs}", headers=headers).status_code == 200
        assert len(calls) == 1

    def test_sport_filters_the_cached_tracks(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        _make_activity_with_track(db, user, material, lat0=10.0, sport="cycling")
        client.get("/activities/heatmap", headers=headers)  # build
        assert len(client.get("/activities/heatmap?sport=cycling", headers=headers).json()) == 1
        assert len(client.get("/activities/heatmap?sport=Cycling ", headers=headers).json()) == 1

    def test_a_mode_drops_points_without_its_value(self, client, user, db):
        """No heart rate is a gap, not a guess — as the per-mode SQL filtered it."""
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, heart_rate=None)
        assert len(client.get("/activities/heatmap", headers=headers).json()) == 1
        assert client.get("/activities/heatmap?mode=heartrate", headers=headers).json() == []
        pace = client.get("/activities/heatmap?mode=pace", headers=headers).json()
        assert pace and all(len(p) == 3 for p in pace[0])

    def test_an_import_clears_every_cached_filter(self, client, user, db):
        from app.api.activities import invalidate_heatmap_cache
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        assert len(client.get("/activities/heatmap?after=2024-01-01", headers=headers).json()) == 1
        _make_activity_with_track(db, user, material, lat0=10.0)
        invalidate_heatmap_cache()  # what fit_import does after every import
        assert len(client.get("/activities/heatmap?after=2024-01-01", headers=headers).json()) == 2


    def test_a_deleted_activity_leaves_every_cached_filter(self, client, user, db):
        """Edits never invalidated these caches, only imports did — so a
        deleted ride stayed on the heatmap until the next import."""
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material)
        _make_activity_with_track(db, user, material, lat0=10.0)
        assert len(client.get("/activities/heatmap?after=2024-01-01", headers=headers).json()) == 2
        assert client.delete(f"/activities/{a.id}", headers=headers).status_code == 204
        assert len(client.get("/activities/heatmap?after=2024-01-01", headers=headers).json()) == 1

    def test_a_re_sported_activity_moves_between_cached_sports(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material)
        assert len(client.get("/activities/heatmap?sport=running", headers=headers).json()) == 1
        client.patch(f"/activities/{a.id}", json={"sport": "hiking"}, headers=headers)
        assert client.get("/activities/heatmap?sport=running", headers=headers).json() == []
        assert len(client.get("/activities/heatmap?sport=hiking", headers=headers).json()) == 1

    def test_track_geojson_is_cached_and_follows_a_rename(self, client, user, db, monkeypatch):
        from app.api.activities import heatmap as heatmap_route
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material)
        calls = []
        real = heatmap_route._tracks_geojson
        monkeypatch.setattr(heatmap_route, "_tracks_geojson", lambda *x: calls.append(1) or real(*x))

        first = client.get("/activities/tracks-geojson", headers=headers).json()
        assert client.get("/activities/tracks-geojson", headers=headers).json() == first
        assert len(calls) == 1  # the second open decrypted nothing

        client.patch(f"/activities/{a.id}", json={"name": "Renamed"}, headers=headers)
        after = client.get("/activities/tracks-geojson", headers=headers).json()
        assert after["features"][0]["properties"]["name"] == "Renamed"


class TestImportStatusIsScoped:
    def test_requires_auth(self, client, user, db, monkeypatch):
        """This route lives on sync_router, which is mounted WITHOUT a blanket
        auth dependency, so the guard has to be declared per-route. It had none.

        `_setup_complete` is forced here because the fixture harness never
        leaves open-access mode: `/auth/setup` commits an admin the request-scoped
        session doesn't go on to see, so require_auth keeps taking its open-mode
        branch and hands back the first user for *any* guarded route. Setting the
        flag reproduces the post-setup state a real deployment reaches, which is
        the only state in which this assertion means anything.
        """
        from app import auth as auth_core

        _auth(client, db, user)
        monkeypatch.setattr(auth_core, "_setup_complete", True)
        assert client.get("/training-plan/sync/import-status").status_code == 401

    def test_reports_only_the_callers_queue(self, client, user, db):
        """Another user's pending imports must not spin this user's UI."""
        from app.models.activity import User

        headers, _ = _auth(client, db, user)
        other = User(name="Somebody Else")
        db.add(other)
        db.flush()
        db.add(PendingImport(user_id=other.id, blob_id="blob-1",
                             content_hash="h" * 64, filename="x.fit"))
        db.commit()

        assert client.get("/training-plan/sync/import-status",
                          headers=headers).json()["importing"] is False

        db.add(PendingImport(user_id=user.id, blob_id="blob-2",
                             content_hash="g" * 64, filename="y.fit"))
        db.commit()
        assert client.get("/training-plan/sync/import-status",
                          headers=headers).json()["importing"] is True


class TestSyncSignallingIsShared:
    """These flags moved out of module globals because they only existed in
    whichever of UVICORN_WORKERS processes served the write. Redis is the only
    state every worker shares — so these tests assert on Redis directly, which
    is the part a second worker would actually be able to see."""

    def test_start_and_clear_round_trip(self):
        """Per user now (app.services.activity_status), not one flag for the
        instance — a watch docked for one account spun every account's sidebar."""
        from app.services import activity_status

        activity_status.mark(7, "watch")
        assert activity_status.snapshot(7)["watch"] is not None
        assert activity_status.snapshot(8)["watch"] is None
        activity_status.clear(7, "watch")
        assert activity_status.snapshot(7)["watch"] is None

    def test_active_flag_expires_rather_than_latching(self):
        """A worker that dies mid-sync used to take its in-memory flag with it,
        leaving the sidebar stuck on "Syncing watch…" forever. The TTL is what
        replaces the old timestamp-staleness comparison."""
        from app.services import activity_status
        from app.services.redis_client import get_redis

        activity_status.mark(7, "watch")
        assert get_redis().ttl(activity_status._key(7, "watch")) > 0

    def test_trigger_is_consumed_exactly_once(self, client, user, db):
        """Two agents polling at the same instant must not both believe they
        own the trigger — DELETE returning a count is what makes the
        read-and-consume atomic."""
        from app.api.training_plan import sync as sync_mod
        from app.services.redis_client import get_redis

        get_redis().set(sync_mod._TRIGGER_KEY, "1", ex=sync_mod._TRIGGER_WINDOW_SECONDS)
        assert sync_mod.should_trigger_sync() == {"trigger": True}
        assert sync_mod.should_trigger_sync() == {"trigger": False}

    def test_no_trigger_pending_reads_false(self):
        from app.api.training_plan import sync as sync_mod

        assert sync_mod.should_trigger_sync() == {"trigger": False}

    def test_redis_failure_reports_idle_not_stuck(self, monkeypatch):
        """Fail-open: a Redis blip should mean "nothing happening", never a
        latched spinner or a phantom trigger."""
        import redis as redis_lib
        from app.api.training_plan import sync as sync_mod

        def boom():
            raise redis_lib.RedisError("down")

        from app.services import activity_status

        monkeypatch.setattr(sync_mod, "get_redis", boom)
        monkeypatch.setattr(activity_status, "get_redis", boom)
        assert activity_status.snapshot(7)["watch"] is None
        assert sync_mod.should_trigger_sync() == {"trigger": False}
        activity_status.mark(7, "watch")   # must not raise
        activity_status.clear(7, "watch")  # must not raise


class TestClientIpBehindProxy:
    """The login limiter keys on this. Get it wrong in one direction and every
    client shares one counter; wrong in the other and any client can forge its
    way past the limiter entirely."""

    def _request(self, headers):
        from unittest.mock import Mock

        req = Mock()
        req.headers = headers
        req.client = Mock(host="172.18.0.5")  # the proxy, as the socket sees it
        return req

    def test_uses_peer_address_when_no_proxy_configured(self, monkeypatch):
        from app.api import auth as auth_module

        monkeypatch.setattr(auth_module.settings, "trusted_proxy_hops", 0)
        req = self._request({"x-forwarded-for": "9.9.9.9"})
        assert auth_module._client_ip(req) == "172.18.0.5"

    def test_reads_the_hop_the_proxy_appended(self, monkeypatch):
        from app.api import auth as auth_module

        monkeypatch.setattr(auth_module.settings, "trusted_proxy_hops", 1)
        req = self._request({"x-forwarded-for": "203.0.113.7"})
        assert auth_module._client_ip(req) == "203.0.113.7"

    def test_client_supplied_prefix_cannot_forge_the_address(self, monkeypatch):
        """A client sending its own X-Forwarded-For only prepends values; the
        proxy appends the address it actually saw. Counting from the right is
        what makes the header safe to read at all."""
        from app.api import auth as auth_module

        monkeypatch.setattr(auth_module.settings, "trusted_proxy_hops", 1)
        req = self._request({"x-forwarded-for": "1.2.3.4, 5.6.7.8, 203.0.113.7"})
        assert auth_module._client_ip(req) == "203.0.113.7"

    def test_falls_back_when_header_is_shorter_than_configured_hops(self, monkeypatch):
        from app.api import auth as auth_module

        monkeypatch.setattr(auth_module.settings, "trusted_proxy_hops", 2)
        req = self._request({"x-forwarded-for": "203.0.113.7"})
        assert auth_module._client_ip(req) == "172.18.0.5"

    def test_missing_header_falls_back_to_peer(self, monkeypatch):
        from app.api import auth as auth_module

        monkeypatch.setattr(auth_module.settings, "trusted_proxy_hops", 1)
        assert auth_module._client_ip(self._request({})) == "172.18.0.5"
