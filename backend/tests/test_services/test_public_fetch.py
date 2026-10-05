# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The guard on server-side fetches of user-supplied URLs.

Literal IP addresses throughout, so nothing here depends on DNS or the network.
"""
import httpx
import pytest

from app.services import public_fetch
from app.services.public_fetch import UnsafeURL, check_public_url, fetch_public

PUBLIC = "http://93.184.216.34"


@pytest.mark.parametrize("url", [
    "file:///etc/passwd",
    "ftp://93.184.216.34/cpe.bin",
    "http:///no-host",
    "http://127.0.0.1:8000/",
    "http://localhost/",
    "http://10.0.0.5:4080/",
    "http://172.18.0.5/",
    "http://192.168.1.1/",
    "http://169.254.169.254/latest/meta-data/",
    "http://[::1]/",
    "http://[::ffff:127.0.0.1]/",
])
def test_a_url_into_the_server_or_its_network_is_refused(url):
    """Each of these is the server's own filesystem, itself, its Docker
    network, the LAN or a cloud metadata service — never an ephemeris mirror."""
    with pytest.raises(UnsafeURL):
        check_public_url(url)


def test_a_public_address_is_allowed():
    check_public_url(PUBLIC + "/cpe.bin")


@pytest.fixture
def transport(monkeypatch):
    def install(handler):
        monkeypatch.setattr(public_fetch, "_transport", httpx.MockTransport(handler))
    return install


def test_a_redirect_into_the_server_is_refused(transport):
    """Checking only the first URL is the classic way round a guard like this:
    a public address that answers 302 to a private one."""
    seen = []

    def handler(request):
        seen.append(str(request.url))
        return httpx.Response(302, headers={"location": "http://127.0.0.1:8000/openapi.json"})

    transport(handler)
    with pytest.raises(UnsafeURL):
        fetch_public(PUBLIC + "/cpe.bin", max_bytes=100, timeout=5)
    assert seen == [PUBLIC + "/cpe.bin"]   # the private hop was never requested


def test_a_public_redirect_is_followed_and_the_body_capped(transport):
    def handler(request):
        if request.url.path == "/old":
            return httpx.Response(301, headers={"location": "/new"})
        return httpx.Response(200, content=b"x" * 50)

    transport(handler)
    assert fetch_public(PUBLIC + "/old", max_bytes=10, timeout=5) == b"x" * 10


def test_an_error_status_raises(transport):
    transport(lambda request: httpx.Response(404))
    with pytest.raises(httpx.HTTPStatusError):
        fetch_public(PUBLIC + "/cpe.bin", max_bytes=10, timeout=5)
