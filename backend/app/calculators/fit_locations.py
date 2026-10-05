# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""FIT *locations* file encoder — Garmin's saved places.

Written by hand rather than through ``garmin_fit_sdk`` because the SDK cannot
express this file at all: its generated profile has no ``location`` message
(global 29) and its ``file`` enum skips 8 (``locations``) entirely, jumping
7 → 9. ``Encoder.write_mesg`` rejects both, so there is nothing to configure
around — the bytes have to be assembled here.

The layout below was taken from a known-good Locations.fit (Gadgetbridge's
``TestFitLocationEncoding.fit``) rather than from memory, which is what pinned
down the two fields that are easy to guess wrong: the timestamp is field 253,
not 6, and altitude is ``(metres + 500) * 5`` in a uint16 whose 65535 means
"no altitude" rather than "65 km up".

A watch keeps every saved place in ONE file, so this takes the whole list and
the sync layer rebuilds it whenever any waypoint changes. Encoding one point at
a time would replace the file and silently drop the rest.

Colour is deliberately absent: the FIT location record has no colour field —
a Garmin device colours a place by its symbol. The app's colour is therefore a
map concern only, and the symbol is what carries meaning onto the wrist.
"""

from __future__ import annotations

import math
import struct
from datetime import datetime, timezone

# ── FIT wire constants ───────────────────────────────────────────────────────

_HEADER_SIZE = 14
_PROTOCOL_VERSION = 0x20          # 2.0
_PROFILE_VERSION = 21208          # major * 1000 + minor, matching the bundled SDK
_DATA_TYPE = b".FIT"

_MESG_FILE_ID = 0
_MESG_LOCATION = 29

_FILE_TYPE_LOCATIONS = 8          # absent from garmin_fit_sdk's `file` enum
_MANUFACTURER_GARMIN = 1
_PRODUCT_CONNECT = 65534          # the same sentinel fit_course.py writes

# Base type ids, with the endian bit set on the multi-byte ones as FIT requires.
_T_ENUM = 0x00
_T_UINT16 = 0x84
_T_SINT32 = 0x85
_T_UINT32 = 0x86
_T_UINT32Z = 0x8C
_T_STRING = 0x07

# Local message types. file_id and location get their own so neither definition
# has to be re-sent.
_LOCAL_FILE_ID = 0
_LOCAL_LOCATION = 1

_SEMI = 2 ** 31 / 180.0           # degrees → semicircles
_FIT_EPOCH = 631065600            # 1989-12-31 UTC, in Unix seconds

# Altitude is stored as (metres + offset) * scale in a uint16.
_ALT_SCALE = 5
_ALT_OFFSET = 500
_ALT_INVALID = 0xFFFF

# What a watch will show. Longer names are trimmed rather than rejected — a
# place whose label is cut short is still findable; one that failed to encode
# is not.
_NAME_BYTES = 31

_CRC_TABLE = (
    0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
    0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
)


# ── Symbols ──────────────────────────────────────────────────────────────────
# App icon name → Garmin location symbol id (the device's fixed symbol table).
# The app's own picker offers the first ten; the rest are aliases so a waypoint
# imported from elsewhere, or named by a POI category, still lands on something
# recognisable instead of the default pin.
_SYMBOL = {
    # The app's picker (see WAYPOINT_ICONS in WatchLoadSheet.kt).
    "marker": 84,             # Pin, Blue
    "water": 30,              # Drinking Water
    "camp": 11,               # Campground
    "parking": 52,            # Parking Area
    "summit": 71,             # Summit
    "viewpoint": 63,          # Scenic Area
    "shelter": 39,            # Lodging
    "food": 60,               # Restaurant
    "danger": 25,             # Skull and Crossbones
    "car": 12,                # Car
    # The map's own sprite names, which are what the app's picker now offers:
    # a waypoint drawn on the map and the same waypoint on the wrist should not
    # show two different symbols, so the two vocabularies are one.
    "camp_site": 11,
    "alpine_hut": 39,
    "wilderness_hut": 39,
    "toilets": 61,            # Restroom
    "fast_food": 32,
    "cafe": 60,
    "ranger_station": 41,     # Information
    "marina": 7,              # Boat Ramp
    # Aliases.
    "drinking_water": 30,
    "water_source": 136,
    "spring": 136,
    "campground": 11,
    "campsite": 11,
    "peak": 71,
    "trailhead": 76,          # Trail Head
    "scenic": 63,
    "lodge": 128,
    "lodging": 39,
    "restaurant": 60,
    "picnic": 54,             # Picnic Area
    "restroom": 61,
    "shower": 68,
    "fuel": 36,               # Gas Station
    "gas_station": 36,
    "medical": 45,            # Medical Facility
    "first_aid": 45,
    "pharmacy": 53,
    "information": 41,
    "park": 51,
    "beach": 90,
    "fishing": 33,
    "swimming": 72,
    "skiing": 69,
    "bike": 85,               # Bike Trail
    "bridge": 9,
    "dam": 24,
    "building": 10,
    "school": 64,
    "bank": 4,
    "store": 22,              # Convenience Store
    "geocache": 81,
}

_DEFAULT_SYMBOL = 84          # Pin, Blue

# id → icon, for reading a watch's own places back. Built from the forward map
# rather than written out again, so the two cannot drift. Several names share an
# id (water/drinking_water, camp/campground); the first one wins, which is why
# the picker's ten are listed first above — a place read off the watch comes
# back named the way the picker would have named it.
_ICON_FOR_SYMBOL = {}
for _icon, _id in _SYMBOL.items():
    _ICON_FOR_SYMBOL.setdefault(_id, _icon)

_DEFAULT_ICON = "marker"


def symbol_for(icon: str | None) -> int:
    """The Garmin symbol id for an app icon name, defaulting to a plain pin."""
    return _SYMBOL.get((icon or "").strip().lower(), _DEFAULT_SYMBOL)


def icon_for(symbol: int | None) -> str:
    """The app icon name for a Garmin symbol id, defaulting to a plain marker.

    A watch offers 137 symbols and the app's picker offers ten, so most ids have
    no exact name here. Falling back to "marker" keeps an imported place visible
    and editable; refusing it would lose the place over its icon.
    """
    return _ICON_FOR_SYMBOL.get(symbol, _DEFAULT_ICON)


# ── Primitives ───────────────────────────────────────────────────────────────

def _crc16(data: bytes, crc: int = 0) -> int:
    for byte in data:
        for nibble in (byte & 0xF, (byte >> 4) & 0xF):
            table = _CRC_TABLE[crc & 0xF]
            crc = (crc >> 4) & 0x0FFF
            crc = crc ^ table ^ _CRC_TABLE[nibble]
    return crc


def semicircles(degrees: float) -> int:
    """Degrees → the sint32 semicircles a FIT position field holds."""
    return int(degrees * _SEMI)


def fit_time(moment: datetime) -> int:
    """A datetime → FIT's seconds-since-1989 stamp.

    Naive datetimes are read as UTC, matching how the rest of the FIT encoders
    here treat them.
    """
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return int(moment.timestamp()) - _FIT_EPOCH


def encode_altitude(metres: float | None) -> int:
    """Metres → the uint16 altitude field, or the invalid marker.

    Out-of-range values become "no altitude" rather than wrapping: a waypoint
    that shows up at the wrong height on the wrist is a worse answer than one
    that shows no height at all.
    """
    if metres is None:
        return _ALT_INVALID
    try:
        value = float(metres)
    except (TypeError, ValueError):
        return _ALT_INVALID
    if math.isnan(value) or math.isinf(value):
        return _ALT_INVALID
    raw = round((value + _ALT_OFFSET) * _ALT_SCALE)
    if raw < 0 or raw >= _ALT_INVALID:
        return _ALT_INVALID
    return raw


def trim_name(value: str | None, max_bytes: int = _NAME_BYTES) -> bytes:
    """Name → NUL-terminated UTF-8, trimmed without splitting a character."""
    encoded = (value or "Waypoint").encode("utf-8")[:max_bytes]
    # A cut through a multi-byte sequence leaves bytes no decoder will accept;
    # drop back to the last whole character rather than shipping them.
    while encoded and encoded.decode("utf-8", errors="ignore").encode("utf-8") != encoded:
        encoded = encoded[:-1]
    return (encoded or b"Waypoint") + b"\x00"


def _definition(local: int, global_num: int, fields: list[tuple[int, int, int]]) -> bytes:
    out = bytearray()
    out.append(0x40 | local)            # definition-message header
    out.append(0)                       # reserved
    out.append(0)                       # architecture: little-endian
    out += struct.pack("<H", global_num)
    out.append(len(fields))
    for number, size, base_type in fields:
        out += bytes((number, size, base_type))
    return bytes(out)


# ── The file ─────────────────────────────────────────────────────────────────

def generate_locations_fit(
    waypoints: list[dict],
    time_created: datetime | None = None,
    serial_number: int = 1,
) -> bytes:
    """Encode ``[{name, lat, lng, ele_m, icon}, ...]`` as one Locations FIT.

    An empty list is accepted and produces a valid file containing no places.
    That is the honest encoding of "nothing is loaded", and callers use it
    deliberately; it is not a no-op, so do not hand this an empty list by
    accident.

    Waypoints missing a position are skipped rather than raising — one bad row
    should not cost the user every other saved place in the same push.
    """
    points = [
        w for w in (waypoints or [])
        if w.get("lat") is not None and w.get("lng") is not None
    ]

    created = fit_time(time_created or datetime.now(timezone.utc))

    # Every location record shares one definition, so the name field is sized
    # once, to the longest name in this file.
    names = [trim_name(w.get("name")) for w in points]
    name_size = max((len(n) for n in names), default=1)

    body = bytearray()

    body += _definition(_LOCAL_FILE_ID, _MESG_FILE_ID, [
        (0, 1, _T_ENUM),            # type
        (1, 2, _T_UINT16),          # manufacturer
        (2, 2, _T_UINT16),          # product
        (3, 4, _T_UINT32Z),         # serial_number
        (4, 4, _T_UINT32),          # time_created
    ])
    body.append(_LOCAL_FILE_ID)
    body += struct.pack(
        "<BHHII",
        _FILE_TYPE_LOCATIONS,
        _MANUFACTURER_GARMIN,
        _PRODUCT_CONNECT,
        serial_number & 0xFFFFFFFF,
        created,
    )

    if points:
        body += _definition(_LOCAL_LOCATION, _MESG_LOCATION, [
            (254, 2, _T_UINT16),        # message_index
            (253, 4, _T_UINT32),        # timestamp
            (0, name_size, _T_STRING),  # name
            (1, 4, _T_SINT32),          # position_lat
            (2, 4, _T_SINT32),          # position_long
            (3, 2, _T_UINT16),          # symbol
            (4, 2, _T_UINT16),          # altitude
        ])
        for index, (point, name) in enumerate(zip(points, names)):
            body.append(_LOCAL_LOCATION)
            body += struct.pack("<HI", index, created)
            body += name.ljust(name_size, b"\x00")
            body += struct.pack(
                "<iiHH",
                semicircles(float(point["lat"])),
                semicircles(float(point["lng"])),
                symbol_for(point.get("icon")),
                encode_altitude(point.get("ele_m")),
            )

    header = bytearray(_HEADER_SIZE)
    header[0] = _HEADER_SIZE
    header[1] = _PROTOCOL_VERSION
    struct.pack_into("<H", header, 2, _PROFILE_VERSION)
    struct.pack_into("<I", header, 4, len(body))
    header[8:12] = _DATA_TYPE
    struct.pack_into("<H", header, 12, _crc16(bytes(header[:12])))

    out = bytes(header) + bytes(body)
    return out + struct.pack("<H", _crc16(out))


# ── Reading one back ─────────────────────────────────────────────────────────
#
# A watch's own saved places arrive as the same file this module writes, so the
# decoder lives beside the encoder — the field numbers and the altitude formula
# are the same facts, and splitting them across two files is how they drift.
#
# Deliberately tolerant. This parses a file written by a device, not by us: it
# may use big-endian records (Garmin Connect does), carry messages this code has
# never heard of, and end with padding. Anything unrecognised is skipped rather
# than raised on, because one odd record must not cost the user every other
# place on their watch.

_BASE_TYPE_SIZES = {
    0x00: 1, 0x01: 1, 0x02: 1, 0x07: 1, 0x0A: 1, 0x0D: 1,
    0x03: 2, 0x04: 2, 0x0B: 2,
    0x05: 4, 0x06: 4, 0x08: 4, 0x0C: 4,
    0x09: 8, 0x0E: 8, 0x0F: 8, 0x10: 8,
}


def _read_int(raw: bytes, base_type: int, endian: str) -> int | None:
    signed = base_type in (0x01, 0x03, 0x05, 0x0E)
    value = int.from_bytes(raw, "little" if endian == "<" else "big", signed=signed)
    # FIT's per-type invalid marker: all bits set (all bits but the sign for
    # signed types). Returning None keeps "the device did not say" distinct from
    # a real reading, which matters most for altitude.
    invalid = (1 << (len(raw) * 8 - (1 if signed else 0))) - 1
    return None if value == invalid else value


def parse_locations_fit(data: bytes) -> list[dict]:
    """Read a Locations.fit into ``[{name, lat, lng, ele_m, icon}, ...]``.

    Raises ValueError only when the file is not a FIT file at all — a caller
    handed the wrong bytes, which is worth refusing loudly. Everything past that
    point degrades quietly.
    """
    if len(data) < _HEADER_SIZE or data[8:12] != _DATA_TYPE:
        raise ValueError("not a FIT file")

    header_size = data[0]
    declared = struct.unpack("<I", data[4:8])[0]
    # A file truncated in transfer still has whatever records did arrive; read
    # to whichever end comes first rather than trusting the header's word.
    end = min(header_size + declared, len(data))

    offset = header_size
    definitions: dict[int, tuple[int, list[tuple[int, int, int]], str]] = {}
    places: list[dict] = []

    while offset < end:
        record_header = data[offset]
        offset += 1

        if record_header & 0x80:
            # Compressed-timestamp header: 1-byte header, and the local type is
            # in different bits. No known locations file uses these, and without
            # a definition for the local type there is nothing to advance by, so
            # stop rather than walk off into misaligned bytes.
            break

        if record_header & 0x40:
            local = record_header & 0x0F
            if offset + 5 > end:
                break
            endian = "<" if data[offset + 1] == 0 else ">"
            global_num = int.from_bytes(
                data[offset + 2:offset + 4], "little" if endian == "<" else "big")
            count = data[offset + 4]
            offset += 5

            fields = []
            for _ in range(count):
                if offset + 3 > end:
                    return places
                fields.append((data[offset], data[offset + 1], data[offset + 2]))
                offset += 3

            if record_header & 0x20:            # developer fields follow
                if offset >= end:
                    return places
                developer_count = data[offset]
                offset += 1
                for _ in range(developer_count):
                    if offset + 3 > end:
                        return places
                    # Their sizes still have to be stepped over in data records.
                    fields.append((-1, data[offset + 1], 0x0D))
                    offset += 3

            definitions[local] = (global_num, fields, endian)
            continue

        definition = definitions.get(record_header & 0x0F)
        if definition is None:
            # A data record for a local type never defined — the file is not
            # walkable from here, since its length is unknown.
            break
        global_num, fields, endian = definition

        values: dict[int, bytes] = {}
        for number, size, _base_type in fields:
            if offset + size > end:
                return places
            values[number] = data[offset:offset + size]
            offset += size

        if global_num != _MESG_LOCATION:
            continue

        place = _location_from(values, endian)
        if place is not None:
            places.append(place)

    return places


def _location_from(values: dict[int, bytes], endian: str) -> dict | None:
    """One decoded location, or None when it has no usable position."""
    latitude = values.get(1)
    longitude = values.get(2)
    if latitude is None or longitude is None:
        return None

    lat_raw = _read_int(latitude, 0x05, endian)
    lng_raw = _read_int(longitude, 0x05, endian)
    if lat_raw is None or lng_raw is None:
        return None

    name = b""
    if 0 in values:
        name = values[0].split(b"\x00")[0]

    symbol = None
    if 3 in values:
        symbol = _read_int(values[3], 0x04, endian)

    elevation = None
    if 4 in values:
        raw = _read_int(values[4], 0x04, endian)
        if raw is not None:
            elevation = raw / _ALT_SCALE - _ALT_OFFSET

    return {
        "name": name.decode("utf-8", errors="replace") or "Waypoint",
        "lat": lat_raw / _SEMI,
        "lng": lng_raw / _SEMI,
        "ele_m": elevation,
        "icon": icon_for(symbol),
    }
