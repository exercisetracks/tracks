# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""FIT course encoder + parser round-trip, and geometric turn detection."""
import pytest

from garmin_fit_sdk import Decoder, Stream

from app.calculators.fit_course import generate_course_fit
from app.services.course_fit import parse_course_fit, parse_gpx
from app.services.course_turns import detect_turns, is_turn_compatible

# A short up-then-down ridge line (lng, lat, ele_m).
COORDS = [
    [-150.270, 40.010, 1600.0],
    [-150.260, 40.020, 1650.0],
    [-150.250, 40.030, 1620.0],
]


class TestEncoderDecode:
    def test_round_trips_through_the_sdk_decoder(self):
        data = generate_course_fit("Ridge", COORDS, sport="running", course_id=42)
        msgs, errors = Decoder(Stream.from_byte_array(bytearray(data))).read()
        assert errors == []
        assert msgs["course_mesgs"][0]["name"] == "Ridge"
        assert len(msgs["record_mesgs"]) == 3

    def test_file_type_is_course(self):
        data = generate_course_fit("Ridge", COORDS, sport="hiking", course_id=1)
        msgs, _ = Decoder(Stream.from_byte_array(bytearray(data))).read()
        assert str(msgs["file_id_mesgs"][0]["type"]) == "course"

    def test_position_encodes_to_correct_degrees(self):
        data = generate_course_fit("Ridge", COORDS, course_id=1)
        msgs, _ = Decoder(Stream.from_byte_array(bytearray(data))).read()
        rec = msgs["record_mesgs"][0]
        lat = rec["position_lat"] * (180.0 / 2 ** 31)
        lng = rec["position_long"] * (180.0 / 2 ** 31)
        assert lat == pytest.approx(40.010, abs=1e-4)
        assert lng == pytest.approx(-150.270, abs=1e-4)

    def test_lap_summary_has_distance_and_climb(self):
        data = generate_course_fit("Ridge", COORDS, course_id=1)
        msgs, _ = Decoder(Stream.from_byte_array(bytearray(data))).read()
        lap = msgs["lap_mesgs"][0]
        assert lap["total_distance"] > 1000          # ~2.8 km
        assert lap["total_ascent"] == 50             # 1600→1650
        assert lap["total_descent"] == 30            # 1650→1620

    def test_turn_by_turn_emits_course_points(self):
        cps = [{"d_m": 1200.0, "lat": 40.020, "lng": -150.260, "type": "right", "name": "Turn R"}]
        data = generate_course_fit("Ridge", COORDS, course_id=1, course_points=cps)
        msgs, _ = Decoder(Stream.from_byte_array(bytearray(data))).read()
        pts = msgs["course_point_mesgs"]
        assert len(pts) == 1
        assert str(pts[0]["type"]) == "right"

    def test_rejects_degenerate_geometry(self):
        with pytest.raises(ValueError):
            generate_course_fit("X", [[-150.0, 40.0, 100.0]])


class TestParserRoundTrip:
    def test_parse_matches_encoder_stats(self):
        data = generate_course_fit("Ridge", COORDS, sport="running", course_id=1)
        out = parse_course_fit(data)
        assert out["name"] == "Ridge"
        assert out["sport"] == "running"
        assert len(out["coords"]) == 3
        assert out["distance_m"] == pytest.approx(2791.9, abs=5)
        assert out["ascent_m"] == pytest.approx(50, abs=1)
        assert out["descent_m"] == pytest.approx(30, abs=1)

    def test_gpx_parses_track_points(self):
        gpx = (
            '<?xml version="1.0"?><gpx version="1.1" '
            'xmlns="http://www.topografix.com/GPX/1/1"><trk><name>My GPX</name><trkseg>'
            '<trkpt lat="40.010" lon="-150.270"><ele>1600</ele></trkpt>'
            '<trkpt lat="40.020" lon="-150.260"><ele>1650</ele></trkpt>'
            '<trkpt lat="40.030" lon="-150.250"><ele>1620</ele></trkpt>'
            '</trkseg></trk></gpx>'
        )
        out = parse_gpx(gpx)
        assert out["name"] == "My GPX"
        assert len(out["coords"]) == 3
        assert out["ascent_m"] == pytest.approx(50, abs=1)


class TestTurnDetection:
    def test_straight_line_has_no_turns(self):
        line = [[-150.0 + i * 0.001, 40.0, 1500] for i in range(20)]
        assert detect_turns(line) == []
        ok, n, _ = is_turn_compatible(line)
        assert not ok and n == 0

    def test_right_angle_corner_is_detected(self):
        # East for ~300 m, then north for ~300 m → one ~90° turn.
        corner = ([[-150.0 + i * 0.0009, 40.0, 1500] for i in range(6)]
                  + [[-150.0 + 5 * 0.0009, 40.0 + j * 0.0009, 1500] for j in range(1, 6)])
        turns = detect_turns(corner)
        assert len(turns) >= 1
        assert turns[0]["type"] in ("left", "right", "sharp_left", "sharp_right")
