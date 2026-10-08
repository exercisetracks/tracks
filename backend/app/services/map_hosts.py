# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The internet hosts map downloads come from, and whether DNS is blocking them.

Network-wide DNS filters — AdGuard Home, Pi-hole, NextDNS — are common on
exactly the home servers Tracks runs on, and several map sources sit on their
blocklists. A blocked host does not fail politely: the name resolves to
0.0.0.0 (or does not resolve at all), go-pmtiles reports "dial tcp
0.0.0.0:443: connection refused", and what reached the user was a 502 and a
download that never started. Working out that the cause was a DNS filter, and
which names to allow, was left to them.

So a download first asks the resolver about every host it will need, and says
in one message which are blocked and the whole list to allow — all at once,
because allowing them one failed download at a time is the other way this
goes wrong.
"""

from __future__ import annotations

import ipaddress
import socket
from concurrent.futures import ThreadPoolExecutor, wait
from urllib.parse import urlparse

from app.config import settings

# What else a region download fetches, besides the basemap and terrain archives
# named in settings. Overpass is raced across mirrors, so one allowed mirror is
# enough — they are all listed because a filter that blocks one often blocks
# the rest.
GLYPH_HOST = "protomaps.github.io"          # map label fonts (routes/fonts.py)
OSM_HOSTS = ("download.geofabrik.de",)      # trail, water and POI extracts
OVERPASS_HOSTS = (
    "overpass-api.de", "overpass.private.coffee",
    "overpass.kumi.systems", "maps.mail.ru",
)
# Routing's own downloads (brouter_downloader), not needed to download an area.
ROUTING_HOSTS = ("brouter.de", "raw.githubusercontent.com")

# The resolver's whole answer has this long; a filter answers in milliseconds,
# so this only matters when DNS itself is down.
_RESOLVE_SECONDS = 5.0


def download_hosts() -> list[str]:
    """Every host a region download needs, in the order they are used."""
    hosts = []
    for url in (settings.pmtiles_source_url, settings.dem_source_url):
        host = urlparse(url).hostname if url else None
        if host:
            hosts.append(host)
    hosts += [*OSM_HOSTS, *OVERPASS_HOSTS, GLYPH_HOST]
    return list(dict.fromkeys(hosts))


# The resolver, through one name so tests can answer for it (the conftest
# answers for every test, so none depends on this machine's DNS).
_resolve = socket.getaddrinfo


def _null(address: str) -> bool:
    """An address no real server is at: what a DNS filter answers with."""
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    return ip.is_unspecified or ip.is_loopback


def _verdict(host: str) -> str | None:
    """None when the host resolves to a real address, else why it does not."""
    try:
        infos = _resolve(host, 443, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        return "not found"
    addresses = {info[4][0] for info in infos}
    if addresses and all(_null(a) for a in addresses):
        return "blocked"
    return None


def blocked_hosts(hosts: list[str]) -> dict[str, str]:
    """{host: "blocked" | "not found"} for each host DNS will not give a real
    address for. Empty when all is well — or when the resolver did not answer
    in time, which is not evidence of anything and is left to the download."""
    # Not a `with` block: leaving one waits for every lookup, timeout or not.
    ex = ThreadPoolExecutor(max_workers=len(hosts) or 1)
    futures = {ex.submit(_verdict, h): h for h in hosts}
    done, _ = wait(futures, timeout=_RESOLVE_SECONDS)
    ex.shutdown(wait=False)
    out = {futures[f]: f.result() for f in done if f.result() is not None}
    # Every host "not found" is a machine with no DNS at all, not a filter.
    if out and len(out) == len(hosts) and set(out.values()) == {"not found"}:
        return {}
    return {h: out[h] for h in hosts if h in out}


class MapHostsBlocked(RuntimeError):
    """DNS will not resolve hosts a map download needs. The message is for the
    person who runs the filter: what is blocked, and everything to allow."""

    def __init__(self, blocked: dict[str, str], needed: list[str]):
        self.blocked, self.needed = blocked, needed
        names = list(blocked)
        null = any(v == "blocked" for v in blocked.values())
        how = ("answers with a null address for" if null else "cannot find")
        super().__init__(
            f"This network's DNS {how} {_join(names)}, so maps cannot download. "
            f"That is usually a DNS filter or ad blocker (AdGuard Home, Pi-hole, "
            f"NextDNS). Allow these there, then try again: {', '.join(needed)}."
        )


def check_download_hosts() -> None:
    """Raise MapHostsBlocked when DNS is blocking any host a download needs."""
    needed = download_hosts()
    blocked = blocked_hosts(needed)
    if blocked:
        raise MapHostsBlocked(blocked, needed)


def allow_list_hint() -> str:
    """The sentence a connection failure ends with: everything to allow at once."""
    return f"Map downloads use: {', '.join(download_hosts())}."


def _join(names: list[str]) -> str:
    if len(names) <= 2:
        return " and ".join(names)
    return ", ".join(names[:-1]) + " and " + names[-1]
