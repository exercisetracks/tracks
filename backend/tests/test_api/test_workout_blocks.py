# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Blocks in saved workouts and flows: rest rows, repeat groups, supersets.

Blocks are fields on each row (spec/sync.yaml, workout_exercise), so the
REST API has to carry them through untouched — otherwise a circuit built on
the web would reach a phone as a flat list, or the reverse.
"""

from app.calculators.fit_workout import _emit_strength_steps, _emit_yoga_steps, workout_runs

GROUP = {"group_uid": "g1", "group_kind": "repeat", "group_rounds": 3, "group_rest_seconds": 60}


def _workout(exercises):
    return {"name": "Circuit", "exercises": exercises}


class TestWorkoutBlocks:

    def test_a_workout_keeps_its_rest_rows_and_groups(self, client, user):
        """A circuit saved on the web must come back as a circuit."""
        resp = client.post("/workouts", json=_workout([
            {"exercise_name": "Push Up", **GROUP},
            {"exercise_name": "Barbell Row", **GROUP},
            {"item_kind": "rest", "rest_seconds": 120},
            {"exercise_name": "Plank"},
        ]))
        assert resp.status_code == 201, resp.text
        rows = resp.json()["exercises"]
        assert [r["group_uid"] for r in rows] == ["g1", "g1", None, None]
        assert rows[0]["group_rounds"] == 3 and rows[1]["group_rest_seconds"] == 60
        assert rows[2]["item_kind"] == "rest" and rows[2]["exercise_name"] is None

    def test_an_exercise_row_without_a_name_is_refused(self, client, user):
        """Only a rest block may name no exercise; anything else is a broken row."""
        resp = client.post("/workouts", json=_workout([{"target_sets": 3}]))
        assert resp.status_code == 422

    def test_an_unknown_group_kind_is_refused(self, client, user):
        resp = client.post("/workouts", json=_workout([
            {"exercise_name": "Push Up", "group_uid": "g", "group_kind": "pyramid"},
        ]))
        assert resp.status_code == 422


class TestFlowBlocks:

    def test_a_flow_keeps_its_rest_rows_and_groups(self, client, user):
        resp = client.post("/flexibility/flows", json={
            "name": "Rounds",
            "stretches": [
                {"exercise_name": "Cat Cow", "duration_seconds": 30, **GROUP},
                {"exercise_name": "Childs Pose", "duration_seconds": 45, **GROUP},
                {"item_kind": "rest", "duration_seconds": 30},
            ],
        })
        assert resp.status_code == 200, resp.text
        rows = resp.json()["stretches"]
        assert [r["group_uid"] for r in rows] == ["g1", "g1", None]
        assert rows[2]["item_kind"] == "rest" and rows[2]["exercise_name"] is None

    def test_a_flow_stretch_without_a_name_is_refused(self, client, user):
        resp = client.post("/flexibility/flows", json={"name": "Bad", "stretches": [{"sets": 1}]})
        assert resp.status_code == 422


def _lift(name, group=None):
    step = {"type": "strength_exercise", "name": name, "reps": 5}
    if group:
        step["group"] = group
    return step


class TestBlockEncoding:

    def test_members_apart_after_a_concurrent_reorder_become_two_groups(self):
        """The one grouping rule every replica applies: consecutive uid, first member's settings."""
        g = {"uid": "g", "kind": "repeat", "rounds": 2, "rest_seconds": 0}
        runs = workout_runs([_lift("A", g), _lift("B"), _lift("C", g)])
        assert [len(m) for _, m in runs] == [1, 1, 1]
        assert runs[0][0] is g and runs[1][0] is None and runs[2][0] is g

    def test_a_circuit_is_one_loop_around_every_member(self):
        """Members' own sets are ignored inside a group; the rounds are the sets."""
        g = {"uid": "g", "kind": "repeat", "rounds": 3, "rest_seconds": 90}
        steps, _ = _emit_strength_steps([_lift("A", g), _lift("B", g)])
        kinds = [(s.get("intensity"), s.get("duration_type")) for s in steps]
        assert kinds == [("active", "reps"), ("active", "reps"), ("rest", "time"),
                         ("active", "repeat_until_steps_cmplt")]
        assert steps[-1]["duration_value"] == 0 and steps[-1]["target_value"] == 3

    def test_a_stretch_group_is_one_loop_around_its_holds(self):
        """A repeated stretch group is one pass of its members and one loop, as
        a circuit is — not written out round by round."""
        g = {"uid": "g", "kind": "repeat", "rounds": 2, "rest_seconds": 0}
        hold = {"type": "mobility_exercise", "name": "Cat Cow", "duration_seconds": 30, "group": g}
        steps, _ = _emit_yoga_steps([hold, dict(hold, name="Childs Pose")])
        assert [s["duration_type"] for s in steps] == ["time", "time", "repeat_until_steps_cmplt"]
        assert steps[-1]["duration_value"] == 0 and steps[-1]["target_value"] == 2


class TestRepsAreOneNumber:

    def test_a_saved_exercise_carries_one_reps_target(self, client, user):
        """The watch's step holds one reps count, so the API stores one — no range."""
        resp = client.post("/workouts", json=_workout([{"exercise_name": "Push Up", "target_reps": 6}]))
        assert resp.status_code == 201, resp.text
        row = resp.json()["exercises"][0]
        assert row["target_reps"] == 6
        assert "target_reps_min" not in row and "target_reps_max" not in row

    def test_a_saved_workouts_reps_are_what_the_watch_step_counts(self, client, user, db):
        """A custom workout in the plan must encode its own reps, not the encoder's default 8.

        The plan used to carry `reps_min`/`reps_max`, which the encoder does not
        read, so every exercise of a custom workout would have gone out as 8.
        """
        from app.api.training_plan.injectors import _build_custom_exercise_list
        resp = client.post("/workouts", json=_workout([
            {"exercise_name": "Push Up", "target_reps": 6, "target_sets": 1},
            {"item_kind": "rest", "rest_seconds": 60},
        ]))
        exercises = _build_custom_exercise_list(db, resp.json()["id"])
        steps, _ = _emit_strength_steps(exercises)
        work = [s for s in steps if s.get("duration_type") == "reps"]
        assert [s["duration_value"] for s in work] == [6]
