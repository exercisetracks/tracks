# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Taking an access token back.

Access tokens are stateless JWTs that live 30 days. Signing out used to drop
only the session's cached decryption key, which shut the encrypted half of the
API and left the rest — medications, meals, settings — open to the old token
until it expired. These tests pin the other half: a signed-out or
password-changed-away token is refused everywhere.
"""
from app.services import crypto_context
from app.services.redis_client import get_redis

PASSWORD = "testpass123"
NEW_PASSWORD = "brandnewpass456"


def _setup(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": PASSWORD,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return resp.json()["access_token"]


def _login(client, **extra):
    resp = client.post("/auth/login", json={
        "username": "admin", "password": PASSWORD, **extra})
    assert resp.status_code == 200, resp.text
    return resp.json()


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _medications(client, token):
    """An endpoint that decrypts nothing — exactly the kind a stale token
    could still reach before revocation existed."""
    return client.get("/medications", headers=_bearer(token))


class TestLogout:
    def test_a_logged_out_token_is_refused_where_nothing_is_encrypted(self, client, user):
        """The case that motivated the change: after logout, a stolen copy of
        the token still listed the user's medications for up to 30 days."""
        token = _setup(client)
        assert _medications(client, token).status_code == 200

        client.post("/auth/logout", headers=_bearer(token))

        assert _medications(client, token).status_code == 401

    def test_logging_out_one_session_leaves_the_others_alone(self, client, user):
        _setup(client)
        laptop = _login(client)["access_token"]
        phone = _login(client)["access_token"]

        client.post("/auth/logout", headers=_bearer(laptop))

        assert _medications(client, laptop).status_code == 401
        assert _medications(client, phone).status_code == 200

    def test_logout_ends_a_refresh_chain_on_the_same_session(self, client, user):
        """Otherwise the chain could mint fresh tokens for the sid once its
        revocation row has aged out."""
        _setup(client)
        login = _login(client, issue_refresh_token=True)

        client.post("/auth/logout", headers=_bearer(login["access_token"]))

        assert client.post("/auth/refresh", json={
            "refresh_token": login["refresh_token"]}).status_code == 401

    def test_the_revocation_outlives_a_redis_restart(self, client, user):
        """Redis runs without persistence. A revocation kept there would be
        forgotten by a container restart and re-admit the token."""
        token = _setup(client)
        client.post("/auth/logout", headers=_bearer(token))

        get_redis().flushdb()

        assert _medications(client, token).status_code == 401


class TestSignOutADevice:
    def test_a_signed_out_devices_token_is_refused(self, client, user):
        owner = _setup(client)
        lost_phone = _login(client, issue_refresh_token=True, device_label="Lost")
        session_id = client.get("/auth/sessions", headers=_bearer(owner)).json()[0]["id"]

        client.delete(f"/auth/sessions/{session_id}", headers=_bearer(owner))

        assert _medications(client, lost_phone["access_token"]).status_code == 401
        assert _medications(client, owner).status_code == 200


class TestPasswordChange:
    def _change(self, client, token, **extra):
        return client.post("/users/me/password", json={
            "current_password": PASSWORD, "new_password": NEW_PASSWORD, **extra,
        }, headers=_bearer(token))

    def test_every_older_token_is_refused(self, client, user):
        """Including sessions whose vault had gone idle, which no index of
        live sessions would have named."""
        mine = _setup(client)
        thief = _login(client)["access_token"]
        crypto_context.drop_session_key(_sid(thief))  # the thief's vault has lapsed

        resp = self._change(client, mine)
        assert resp.status_code == 200, resp.text

        assert _medications(client, thief).status_code == 401
        assert _medications(client, mine).status_code == 401

    def test_the_caller_gets_a_working_token_for_the_same_session(self, client, user):
        """The replacement keeps the sid, so the vault the caller just proved
        the password for stays open rather than asking for it again."""
        mine = _setup(client)

        replacement = self._change(client, mine).json()["access_token"]

        assert _sid(replacement) == _sid(mine)
        assert _medications(client, replacement).status_code == 200
        assert crypto_context.load_session_key(_sid(replacement)) is not None

    def test_a_phone_can_ask_for_a_refresh_token_to_replace_its_revoked_one(self, client, user):
        """The change revokes every refresh token, the caller's included. A
        phone changing its own password must not be signed out by it."""
        _setup(client)
        phone = _login(client, issue_refresh_token=True)

        body = self._change(client, phone["access_token"],
                            issue_refresh_token=True, device_label="Pixel").json()

        assert body["refresh_token"]
        refreshed = client.post("/auth/refresh", json={"refresh_token": body["refresh_token"]})
        assert refreshed.status_code == 200, refreshed.text
        assert _medications(client, refreshed.json()["access_token"]).status_code == 200

    def test_the_web_gets_no_refresh_token_unless_it_asks(self, client, user):
        assert self._change(client, _setup(client)).json()["refresh_token"] is None

    def test_a_wrong_current_password_is_not_reported_as_an_expired_login(self, client, user):
        """A 401 here made the phone's client refresh, retry, and then show the
        user as signed out over a typo."""
        resp = client.post("/users/me/password", json={
            "current_password": "not-it-at-all", "new_password": NEW_PASSWORD,
        }, headers=_bearer(_setup(client)))
        assert resp.status_code == 403


def _sid(token):
    from app.auth import decode_token
    return decode_token(token)["sid"]
