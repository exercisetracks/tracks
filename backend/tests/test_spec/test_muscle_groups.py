# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Python muscle-activation evaluator must agree with the shared golden
corpus.

spec/fixtures/muscle_groups.json is the same file the JavaScript
(spec/muscleActivation.js) and Kotlin (spec/MuscleGroups.kt) suites read. Its
expected values came from the original frontend/src/utils/muscleGroups.js —
the implementation that was already shipping — so these tests answer two
questions at once: does the spec/muscle_groups.yaml transcription match it,
and does this brand-new Python port agree with the other two.
"""
import json
from pathlib import Path

import pytest

from app.calculators.muscle_activation import (
    category_label,
    compute_muscle_activation,
    muscle_label,
)
from app.spec.muscle_groups import CATEGORY_LABELS, CATEGORY_MUSCLES, MUSCLE_LABELS

# Mounted read-only into the container — see docker-compose.yml.
FIXTURES = Path("/spec/fixtures/muscle_groups.json")


def _load():
    if not FIXTURES.exists():
        pytest.skip(f"shared fixtures not mounted at {FIXTURES}")
    return json.loads(FIXTURES.read_text())


class TestActivationGoldenCorpus:
    def test_fixtures_are_present_and_substantial(self):
        data = _load()
        assert len(data["activation_cases"]) >= 10
        assert len(data["category_label_cases"]) >= 8
        assert len(data["muscle_label_cases"]) >= 5

    def test_every_activation_case_matches(self):
        data = _load()
        mismatches = []
        for c in data["activation_cases"]:
            got = compute_muscle_activation(c["sets"])
            want = c["expected"]
            if (
                got["activation"] != pytest.approx(want["activation"], abs=1e-9)
                or got["totals"] != pytest.approx(want["totals"], abs=1e-9)
                or got["category_counts"] != want["category_counts"]
            ):
                mismatches.append((c["name"], want, got))
        assert not mismatches, "\n".join(
            f"{name}: expected {want}, got {got}" for name, want, got in mismatches
        )

    def test_every_category_label_case_matches(self):
        data = _load()
        mismatches = [
            (c["key"], c["expected"], category_label(c["key"]))
            for c in data["category_label_cases"]
            if category_label(c["key"]) != c["expected"]
        ]
        assert not mismatches

    def test_every_muscle_label_case_matches(self):
        data = _load()
        mismatches = [
            (c["key"], c["expected"], muscle_label(c["key"]))
            for c in data["muscle_label_cases"]
            if muscle_label(c["key"]) != c["expected"]
        ]
        assert not mismatches


class TestGeneratedTable:
    def test_every_primary_and_secondary_muscle_has_a_string_key(self):
        for cat, m in CATEGORY_MUSCLES.items():
            assert isinstance(m["primary"], list)
            assert isinstance(m["secondary"], list)
            for key in m["primary"] + m["secondary"]:
                assert isinstance(key, str) and key, f"{cat} has a blank muscle key"

    def test_category_muscles_covers_roughly_35_categories(self):
        # Not exact — just a floor so a spec edit that silently dropped most
        # of the table would fail loudly instead of quietly passing.
        assert len(CATEGORY_MUSCLES) >= 30

    def test_garmin_firmware_extensions_are_absent_from_category_labels(self):
        # hip_hinge/push/pull are deliberately not in category_labels — see
        # spec/muscle_groups.yaml. category_label() title-cases them instead.
        for key in ("hip_hinge", "push", "pull"):
            assert key in CATEGORY_MUSCLES
            assert key not in CATEGORY_LABELS

    def test_shoulders_is_labelled_now_that_the_library_uses_it(self):
        # "shoulders" was once deliberately unlabelled (it appeared only as a
        # secondary muscle). The bundled library uses it as a key, and every
        # key the library uses must read as words in the UI, not as a raw key.
        assert muscle_label("shoulders") == MUSCLE_LABELS["shoulders"] != "shoulders"


class TestUnitBehaviour:
    def test_compute_muscle_activation_handles_none(self):
        result = compute_muscle_activation(None)
        assert result == {"activation": {}, "totals": {}, "category_counts": {}}

    def test_category_label_handles_none_and_blank(self):
        assert category_label(None) == "Unknown"
        assert category_label("") == "Unknown"

    def test_muscle_label_falls_back_to_raw_key(self):
        assert muscle_label("not_a_real_muscle") == "not_a_real_muscle"
