# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fetching a URL a user typed, from inside the server.

A server-side fetch of a user-supplied URL is a request made from the server's
position on the network: it reaches the loopback services (in the all-in-one
image that is PostgreSQL, Redis and the API itself), the Docker network, a
cloud metadata address, and — through urllib, which is what this replaced —
the local filesystem via ``file://``. The custom AGPS URL is exactly that, and
its download is handed straight back to the caller, so before this guard a
``file:///proc/self/environ`` URL returned JWT_SECRET and the encryption keys
to any logged-in household member.

So a fetch through here is http(s) only, every address the host resolves to
must be a public one, and redirects are followed by hand so each hop passes
the same check (a public URL answering 302 to http://127.0.0.1/ is the
standard way around a check made only on the first URL).

This is for URLs that should point at the internet — an ephemeris mirror, not
a music server on the LAN. Integrations whose whole point is a private address
(Navidrome, a local Ollama) do not use it, and do not hand raw bodies back.

Residual, stated so it is not mistaken for closed: DNS can answer differently
between the check and the connection (rebinding). Closing that means pinning
the connection to the address that was checked; for a public file of a few
megabytes fetched on demand by an authenticated user, the window is accepted.
"""
from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlsplit

import httpx

#: Where requests go. None is the network; tests put an httpx.MockTransport here.
_transport: httpx.BaseTransport | None = None

_MAX_REDIRECTS = 5


class UnsafeURL(ValueError):
    """The URL is not one the server will fetch on a user's behalf."""


def _is_public(address: str) -> bool:
    ip = ipaddress.ip_address(address.split("%", 1)[0])
    # An IPv4 address written as IPv6 (::ffff:127.0.0.1) is still loopback.
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_global


def check_public_url(url: str) -> None:
    """Raise UnsafeURL unless `url` is http(s) and resolves only to public
    addresses. Every resolved address must pass, not just one: a name with a
    public and a private record would otherwise be a coin toss."""
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise UnsafeURL("only http:// and https:// URLs can be fetched")
    port = parts.port or (443 if parts.scheme == "https" else 80)
    try:
        infos = socket.getaddrinfo(parts.hostname, port, type=socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError) as exc:
        raise UnsafeURL(f"cannot resolve {parts.hostname}") from exc
    if not infos or not all(_is_public(info[4][0]) for info in infos):
        raise UnsafeURL(f"{parts.hostname} is not a public address")


def fetch_public(url: str, *, max_bytes: int, timeout: float,
                 headers: dict[str, str] | None = None) -> bytes:
    """GET `url` and return at most `max_bytes` of its body.

    Raises UnsafeURL for a URL (or a redirect) the guard refuses, and
    httpx.HTTPError for anything that goes wrong on the wire.
    """
    with httpx.Client(timeout=timeout, follow_redirects=False,
                      transport=_transport) as client:
        for _ in range(_MAX_REDIRECTS + 1):
            check_public_url(url)
            with client.stream("GET", url, headers=headers) as resp:
                if resp.is_redirect:
                    url = str(resp.url.join(resp.headers["location"]))
                    continue
                resp.raise_for_status()
                body = bytearray()
                for chunk in resp.iter_bytes():
                    body += chunk
                    if len(body) >= max_bytes:
                        break
                return bytes(body[:max_bytes])
    raise UnsafeURL("too many redirects")
