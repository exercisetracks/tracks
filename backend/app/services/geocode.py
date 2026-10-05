# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Suggest a human-readable name for a region bbox via reverse geocoding."""

from __future__ import annotations

import logging
import re
import unicodedata

import httpx

logger = logging.getLogger(__name__)

NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
USER_AGENT = "Tracks/1.0 (map download tool; self-hosted)"


def suggest_region_name(bbox: list[float]) -> str:
    west, south, east, north = bbox
    lon = (west + east) / 2
    lat = (north + south) / 2

    try:
        name = _nominatim_reverse(lat, lon)
        if name:
            return name
    except Exception:
        logger.debug("Nominatim lookup failed for %s,%s", lat, lon, exc_info=True)

    # Magnitudes: the hemisphere letter already carries the sign, and printing
    # both gave "-105.15°W" — a double negative that reads as the wrong side of
    # the planet.
    return f"{abs(lat):.2f}°{_ns(lat)}, {abs(lon):.2f}°{_ew(lon)}"


def _nominatim_reverse(lat: float, lon: float) -> str | None:
    with httpx.Client(timeout=10) as client:
        resp = client.get(
            NOMINATIM_URL,
            params={
                "lat": lat, "lon": lon, "format": "jsonv2", "zoom": 10,
                # Without this, Nominatim answers in the place's own languages
                # and hands back every variant it holds joined together — a
                # download near Asela came out as
                # "Assela ኣሰላ عسلة, النعامة", which is four scripts and no
                # usable name. Tracks' UI is English throughout, so ask for the
                # English exonym and take the local name only when there is
                # nothing else.
                "accept-language": "en",
            },
            headers={"User-Agent": USER_AGENT},
        )
        resp.raise_for_status()
        data = resp.json()

    address = data.get("address") or {}
    parts: list[str] = []

    for key in ("city", "town", "village", "hamlet", "municipality"):
        if address.get(key):
            parts.append(address[key])
            break

    state = address.get("state", "")
    country = address.get("country", "")

    if state:
        parts.append(state)
    elif country:
        parts.append(country)

    if parts:
        return _usable(", ".join(parts))

    display = data.get("display_name", "")
    if display:
        return _usable(display.split(",")[0].strip())

    return None


# One name, not a list of them. Nominatim sometimes returns several variants in
# a single field separated by a slash or a semicolon, and occasionally repeats
# the same place in two scripts side by side.
_SEPARATORS = re.compile(r"\s*[/;|]\s*")


def _usable(name: str) -> str | None:
    """The name if it is one a person could read back, else None.

    None sends [suggest_region_name] to its coordinate fallback, which is
    unglamorous and always legible. That is the right trade: this string is
    prefilled into a text field the user can edit, so a wrong-but-typable
    starting point beats one they have to select and delete first.

    "Readable" here means Latin script, which is a deliberate narrowing rather
    than a claim about languages — `accept-language: en` has already asked for
    the English name, so anything still arriving in another script is a place
    with no English exonym, and the mixed-script concatenation is exactly the
    shape the bug took.
    """
    candidate = _SEPARATORS.split(name)[0].strip()
    candidate = " ".join(candidate.split())
    if not candidate:
        return None

    letters = [c for c in candidate if c.isalpha()]
    if not letters:
        return None
    if not all("LATIN" in unicodedata.name(c, "") for c in letters):
        return None
    return candidate


def _ns(lat: float) -> str:
    return "N" if lat >= 0 else "S"


def _ew(lon: float) -> str:
    return "E" if lon >= 0 else "W"
