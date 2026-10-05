# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The hand-rolled Locations.fit encoder.

Worth testing at the byte level rather than the API level: nothing in the
project can decode this file (the bundled SDK has no `location` message, which
is why the encoder exists), so a mistake here is invisible until a watch shows
a saved place in the wrong hemisphere. The expected values below come from a
known-good Locations.fit, not from re-deriving the encoder's own arithmetic.
"""
import struct
from datetime import datetime, timezone

import pytest

from app.calculators.fit_locations import (
    encode_altitude, generate_locations_fit, icon_for, parse_locations_fit,
    semicircles, symbol_for, trim_name,
)

CREATED = datetime(2026, 3, 28, 8, 59, 13, tzinfo=timezone.utc)


# ── a minimal reader, so the assertions are about the file and not the code ──

def _parse(data: bytes) -> tuple[dict, list[dict]]:
    """Return (file_id fields, [location fields]) keyed by field number."""
    header_size = data[0]
    data_size = struct.unpack("<I", data[4:8])[0]
    assert data[8:12] == b".FIT"

    i, end = header_size, header_size + data_size
    definitions: dict[int, tuple[int, list[tuple[int, int, int]]]] = {}
    file_id: dict[int, object] = {}
    locations: list[dict[int, object]] = []

    while i < end:
        record_header = data[i]
        i += 1
        if record_header & 0x40:                      # definition
            local = record_header & 0xF
            i += 2                                    # reserved + architecture
            global_num = struct.unpack("<H", data[i:i + 2])[0]
            i += 2
            count = data[i]
            i += 1
            fields = []
            for _ in range(count):
                fields.append((data[i], data[i + 1], data[i + 2]))
                i += 3
            definitions[local] = (global_num, fields)
            continue

        global_num, fields = definitions[record_header & 0xF]
        parsed: dict[int, object] = {}
        for number, size, base_type in fields:
            raw = data[i:i + size]
            i += size
            kind = base_type & 0x1F
            if kind == 0x07:
                parsed[number] = raw.split(b"\x00")[0].decode("utf-8")
            elif kind in (0x00, 0x02):
                parsed[number] = raw[0]
            elif kind == 0x04:
                parsed[number] = struct.unpack("<H", raw)[0]
            elif kind == 0x05:
                parsed[number] = struct.unpack("<i", raw)[0]
            else:
                parsed[number] = struct.unpack("<I", raw)[0]
        if global_num == 0:
            file_id.update(parsed)
        else:
            locations.append(parsed)

    return file_id, locations


def _crc(data: bytes) -> int:
    table = (0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
             0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)
    crc = 0
    for byte in data:
        for nibble in (byte & 0xF, (byte >> 4) & 0xF):
            temp = table[crc & 0xF]
            crc = ((crc >> 4) & 0x0FFF) ^ temp ^ table[nibble]
    return crc


# ── field encodings, against a known-good file's values ─────────────────────

@pytest.mark.parametrize("metres, expected", [
    (6967.0, 37335),        # Aconcagua
    (-439.8, 301),          # the Dead Sea, i.e. below the datum
    (8848.8, 46744),        # Everest
    (0.0, 2500),            # sea level is not "no altitude"
    (None, 0xFFFF),
])
def test_altitude_matches_the_reference_encoding(metres, expected):
    assert encode_altitude(metres) == expected


@pytest.mark.parametrize("metres", [float("nan"), float("inf"), 1e9, -1e9])
def test_unrepresentable_altitudes_become_absent_not_wrapped(metres):
    # A place shown at the wrong height is worse than one shown at no height.
    assert encode_altitude(metres) == 0xFFFF


def test_position_is_semicircles():
    assert semicircles(-32.65305555425584) == -389566127


def test_names_are_trimmed_without_splitting_a_character():
    # Three-byte characters cut at 31 bytes land mid-sequence.
    trimmed = trim_name("नमस्ते" * 10)
    assert trimmed.endswith(b"\x00")
    trimmed[:-1].decode("utf-8")            # raises if a character was split
    assert len(trimmed) <= 32


def test_a_blank_name_still_produces_something_findable():
    assert trim_name("") == b"Waypoint\x00"
    assert trim_name(None) == b"Waypoint\x00"


def test_unknown_icons_fall_back_to_a_pin_rather_than_nothing():
    assert symbol_for("summit") == 71
    assert symbol_for("SUMMIT") == 71
    assert symbol_for("no-such-icon") == symbol_for(None) == 84


# ── the file ────────────────────────────────────────────────────────────────

def test_file_is_a_valid_fit_container():
    data = generate_locations_fit(
        [{"name": "Camp", "lat": 40.0, "lng": -150.0, "ele_m": 2500.0, "icon": "camp"}],
        time_created=CREATED,
    )
    assert data[0] == 14
    assert data[8:12] == b".FIT"
    assert struct.unpack("<H", data[12:14])[0] == _crc(data[:12])

    declared = struct.unpack("<I", data[4:8])[0]
    assert len(data) == 14 + declared + 2
    assert struct.unpack("<H", data[-2:])[0] == _crc(data[:-2])


def test_file_id_declares_the_locations_type_the_sdk_cannot():
    file_id, _ = _parse(generate_locations_fit(
        [{"name": "Camp", "lat": 40.0, "lng": -150.0}], time_created=CREATED))
    assert file_id[0] == 8            # `locations`, absent from garmin_fit_sdk
    assert file_id[1] == 1            # garmin


def test_every_waypoint_becomes_a_location_record():
    points = [
        {"name": "Aconcagua", "lat": -32.65305555425584,
         "lng": -70.01166670583189, "ele_m": 6967.0, "icon": "summit"},
        {"name": "Spring", "lat": 40.1, "lng": -150.2, "icon": "water"},
    ]
    _, locations = _parse(generate_locations_fit(points, time_created=CREATED))

    assert [loc[254] for loc in locations] == [0, 1]      # message_index
    assert locations[0][0] == "Aconcagua"
    assert locations[0][1] == -389566127
    assert locations[0][3] == 71                          # Summit
    assert locations[0][4] == 37335
    assert locations[1][4] == 0xFFFF                      # no elevation given


def test_the_shared_definition_is_sized_to_the_longest_name():
    # One definition covers every record, so a short name must not truncate a
    # long one that follows it.
    _, locations = _parse(generate_locations_fit([
        {"name": "A", "lat": 40.0, "lng": -150.0},
        {"name": "Trailhead by the old bridge", "lat": 40.1, "lng": -150.1},
    ], time_created=CREATED))
    assert locations[0][0] == "A"
    assert locations[1][0] == "Trailhead by the old bridge"


def test_waypoints_without_a_position_are_skipped_not_fatal():
    _, locations = _parse(generate_locations_fit([
        {"name": "Nowhere", "lat": None, "lng": None},
        {"name": "Somewhere", "lat": 40.0, "lng": -150.0},
    ], time_created=CREATED))
    assert [loc[0] for loc in locations] == ["Somewhere"]


def test_an_empty_list_is_a_valid_file_with_no_places():
    data = generate_locations_fit([], time_created=CREATED)
    file_id, locations = _parse(data)
    assert file_id[0] == 8
    assert locations == []
    assert struct.unpack("<H", data[-2:])[0] == _crc(data[:-2])


# ── reading one back ────────────────────────────────────────────────────────

def test_a_file_we_wrote_reads_back_the_same():
    points = [
        {"name": "Aconcagua", "lat": -32.653055, "lng": -70.011666,
         "ele_m": 6967.0, "icon": "summit"},
        {"name": "Spring", "lat": 40.1, "lng": -150.2, "icon": "water"},
        {"name": "No height", "lat": 1.0, "lng": 2.0, "icon": "camp"},
    ]
    read = parse_locations_fit(generate_locations_fit(points, time_created=CREATED))

    assert [p["name"] for p in read] == ["Aconcagua", "Spring", "No height"]
    assert [p["icon"] for p in read] == ["summit", "water", "camp"]
    for original, decoded in zip(points, read):
        assert abs(decoded["lat"] - original["lat"]) < 1e-6
        assert abs(decoded["lng"] - original["lng"]) < 1e-6
    assert read[0]["ele_m"] == pytest.approx(6967.0, abs=0.2)
    # Absent, not zero — a place at sea level and a place with no reading are
    # different answers and the watch distinguishes them.
    assert read[1]["ele_m"] is None
    assert read[2]["ele_m"] is None


def test_a_big_endian_file_from_another_writer_reads():
    # Our encoder only ever emits little-endian, so a round-trip cannot cover
    # this. Garmin Connect and Gadgetbridge both write big-endian records.
    data = _big_endian_locations_file()
    read = parse_locations_fit(data)
    assert len(read) == 1
    assert read[0]["name"] == "Aconcagua"
    assert read[0]["lat"] == pytest.approx(-32.65305555, abs=1e-6)
    assert read[0]["lng"] == pytest.approx(-70.01166670, abs=1e-6)
    assert read[0]["ele_m"] == pytest.approx(6967.0, abs=0.2)


def _big_endian_locations_file() -> bytes:
    """One location record, architecture byte 1, as another writer would emit."""
    body = bytearray()
    body += bytes([0x40, 0, 1]) + struct.pack(">H", 0) + bytes([1]) + bytes([0, 1, 0x00])
    body += bytes([0x00]) + bytes([8])                      # file_id: type=locations

    fields = [(253, 4, 0x86), (0, 16, 0x07), (1, 4, 0x85), (2, 4, 0x85), (4, 2, 0x84)]
    body += bytes([0x41, 0, 1]) + struct.pack(">H", 29) + bytes([len(fields)])
    for number, size, base in fields:
        body += bytes((number, size, base))

    body += bytes([0x01])
    body += struct.pack(">I", 1143622753)
    body += b"Aconcagua".ljust(16, b"\x00")
    body += struct.pack(">i", -389566127)
    body += struct.pack(">i", -835271719)
    body += struct.pack(">H", 37335)

    header = bytearray(14)
    header[0] = 14
    header[1] = 0x20
    struct.pack_into("<H", header, 2, 21208)
    struct.pack_into("<I", header, 4, len(body))
    header[8:12] = b".FIT"
    struct.pack_into("<H", header, 12, _crc(bytes(header[:12])))
    out = bytes(header) + bytes(body)
    return out + struct.pack("<H", _crc(out))


def test_bytes_that_are_not_a_fit_file_are_refused():
    with pytest.raises(ValueError):
        parse_locations_fit(b"this is not a FIT file at all")


def test_a_truncated_file_yields_the_places_that_arrived():
    full = generate_locations_fit([
        {"name": "First", "lat": 40.0, "lng": -150.0},
        {"name": "Second", "lat": 41.0, "lng": -151.0},
    ], time_created=CREATED)
    # Chop the last record and the CRC, as an interrupted transfer would.
    read = parse_locations_fit(full[:-24])
    assert [p["name"] for p in read] == ["First"]


def test_an_empty_file_reads_as_no_places():
    assert parse_locations_fit(generate_locations_fit([], time_created=CREATED)) == []


def test_unknown_symbols_come_back_as_a_plain_marker():
    # A watch offers 137 symbols; losing the place over an unmapped one would
    # be the wrong trade.
    assert icon_for(999) == "marker"
    assert icon_for(None) == "marker"
    assert icon_for(71) == "summit"
