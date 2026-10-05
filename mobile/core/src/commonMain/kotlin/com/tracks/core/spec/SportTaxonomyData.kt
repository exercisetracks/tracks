// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/sport_taxonomy.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

package com.tracks.core.spec

internal val SPORT_TYPE_LIST: List<String> = listOf(
    "bouldering",
    "climbing",
    "strength",
    "running",
    "hiking",
    "cycling",
    "mtb",
    "indoor_cycling",
    "swimming",
    "rowing",
    "triathlon",
    "skiing",
    "nordic_skiing",
    "paddling",
    "golf",
    "team_sports",
    "fitness_equipment",
    "mind_body",
    "other",
)

internal const val FALLBACK: String = "other"

internal val RULES: List<Rule> = listOf(
    Rule("bouldering", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "bouldering")))),
    Rule("climbing", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "rock_climbing|floor_climbing|indoor_climbing")))),
    Rule("mind_body", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "yoga|pilates|breath|meditat|mindful")))),
    Rule("strength", any = listOf(Entry.One(Condition(Field.SPORT, eq = "training")), Entry.One(Condition(Field.COMBINED, matches = "strength_training|weight_training|crossfit")))),
    Rule("hiking", any = listOf(Entry.One(Condition(Field.SPORT, matches = "hik|walk"))), none = listOf(Entry.One(Condition(Field.COMBINED, matches = "mountain_bik|rock|bike|cycl")))),
    Rule("running", any = listOf(Entry.One(Condition(Field.SPORT, matches = "run")), Entry.One(Condition(Field.SUB_SPORT, eq = "treadmill")))),
    Rule("indoor_cycling", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "indoor_cycling|virtual_cycling|spin")), Entry.One(Condition(Field.SUB_SPORT, eq = "indoor_cycling")), Entry.All(listOf(Condition(Field.SPORT, eq = "cycling"), Condition(Field.SUB_SPORT, matches = "virtual|indoor|trainer|spin"))))),
    Rule("mtb", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "mountain_bik|trail_bik|mtb|downhill")), Entry.One(Condition(Field.SUB_SPORT, eq = "mountain")))),
    Rule("cycling", any = listOf(Entry.One(Condition(Field.SPORT, matches = "cycl|bik|ride")), Entry.One(Condition(Field.SUB_SPORT, matches = "road|gravel|cyclocross|track_cycling|hand_cycling|recumbent")))),
    Rule("swimming", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "swim|open_water")))),
    Rule("rowing", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "row")))),
    Rule("triathlon", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "triathlon|multisport|duathlon|transition")))),
    Rule("skiing", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "alpine_ski|downhill_ski|snowboard")))),
    Rule("nordic_skiing", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "cross_country|nordic_ski|skate_ski|snowshoe")))),
    Rule("paddling", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "kayak|canoe|paddle|stand_up|surfing|sail|windsurf|kitesurf|wakeboard|water_ski|raft")))),
    Rule("fitness_equipment", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "elliptical|stair|fitness_equipment")), Entry.One(Condition(Field.SUB_SPORT, eq = "elliptical")), Entry.One(Condition(Field.SUB_SPORT, eq = "stair_climbing")))),
    Rule("team_sports", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "basketball|soccer|football|tennis|volleyball|baseball|softball|hockey|lacrosse|rugby|handball|squash|racquetball|pickleball|badminton")))),
    Rule("team_sports", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "box|wrestling|mma|martial|judo|karate")))),
    Rule("golf", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "golf")))),
    Rule("fitness_equipment", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "skating|skateboard|inline")))),
    Rule("other", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "horseback|hunting|fishing|equestrian")))),
    Rule("other", any = listOf(Entry.One(Condition(Field.COMBINED, matches = "motorcycl|snowmobil|driving|boating|flying")))),
)
