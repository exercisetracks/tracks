// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * climbLogColumns — pure column-definition builder for the Climbing "Climb Log"
 * table (ActivityTable).
 *
 * Extracted from ClimbingLayout to keep the layout body readable: this is a
 * ~65-line conditional lookup table with no state and no coupling to the
 * component beyond a few availability flags. It builds the ActivityTable
 * `columns` array, conditionally including Grade / Result / Ascent / Vert Speed
 * / Calories columns depending on what data the activity actually contains.
 *
 * Climbing-specific (route grades, climb results, per-route ascent), so it
 * lives under climbing/ rather than shared/.
 *
 * @param {object}  opts
 * @param {boolean} opts.hasGrades   include the Grade column
 * @param {boolean} opts.hasResults  include the Result column
 * @param {boolean} opts.hasAscent   include the Ascent + Vert Speed columns
 * @param {boolean} opts.hasCalories include the kcal column
 * @param {boolean} opts.imperial    unit selection for the Ascent header/values
 * @returns {Array} ActivityTable column descriptors
 */
import React from "react";
import { DurationDisplay } from "../../pills/DurationDisplay";
import { GradeDisplay } from "../../pills/GradeDisplay";
import { ResultBadge } from "../../cards/ResultBadge";
import { formatElevation } from "../../../../utils/formatUtils";

export function buildClimbLogColumns({ hasGrades, hasResults, hasAscent, hasCalories, imperial }) {
  return [
    { key: "number", label: "#", align: "left" },
    ...(hasGrades
      ? [{
          key: "grade_level",
          label: "Grade",
          align: "left",
          className: "font-semibold text-accent-600 dark:text-accent-400",
          formatter: (v) => <GradeDisplay grade={v} />,
        }]
      : []),
    ...(hasResults
      ? [{
          key: "climb_result",
          label: "Result",
          align: "center",
          formatter: (v) => <ResultBadge result={v} />,
        }]
      : []),
    {
      key: "duration_seconds",
      label: "Duration",
      align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="compact" />,
    },
    ...(hasAscent
      ? [
          {
            key: "total_ascent",
            label: `Ascent (${imperial ? "ft" : "m"})`,
            align: "right",
            formatter: (v) => (v > 0 ? formatElevation(v, imperial) : "—"),
          },
          {
            key: "avg_vert_speed",
            label: "Vert Speed",
            align: "right",
            formatter: (v) =>
              v
                ? `${(imperial ? v * 3.28084 : v).toFixed(2)} ${imperial ? "ft/s" : "m/s"}`
                : "—",
          },
        ]
      : []),
    {
      key: "max_heart_rate",
      label: "Peak HR",
      align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    ...(hasCalories
      ? [{
          key: "total_calories",
          label: "kcal",
          align: "right",
          formatter: (v) => (v ? v : "—"),
        }]
      : []),
    {
      key: "rest_after",
      label: "Rest After",
      align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="compact" />,
    },
  ];
}
