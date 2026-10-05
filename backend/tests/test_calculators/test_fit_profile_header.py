# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Files built with garmin_fit_sdk declare a pinned profile version.

The SDK stamps its own profile version into every header, and the phone builds
these files byte for byte to the same golden bytes (WorkoutFitTest). Upgrading
the SDK from 21.208 to 21.217 silently changed the server's headers and CRCs
while the phone's stayed put — nothing failed, the two just stopped agreeing.
"""
import struct

import pytest
from garmin_fit_sdk import Decoder, Stream

from app.calculators.fit_course import generate_course_fit
from app.calculators.fit_workout import SDK_FILE_PROFILE_VERSION, generate_workout_fit

COORDS = [[-150.0 + i * 0.001, 40.0, 1500.0 + i] for i in range(10)]


@pytest.mark.parametrize("build", [
    lambda: generate_workout_fit("Easy", "running", [], workout_id=7, time_created=0),
    lambda: generate_course_fit("Ridge", COORDS, course_id=1),
], ids=["workout", "course"])
def test_the_header_declares_the_pinned_profile_and_the_file_is_intact(build):
    data = build()
    assert struct.unpack_from("<H", data, 2)[0] == SDK_FILE_PROFILE_VERSION == 21208
    # Rewriting the header must leave a file the SDK itself accepts: both the
    # header CRC and the file CRC recomputed.
    decoder = Decoder(Stream.from_byte_array(bytearray(data)))
    assert decoder.check_integrity()
    _, errors = decoder.read()
    assert errors == []
