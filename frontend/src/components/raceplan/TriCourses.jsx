// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A triathlon's course, leg by leg: where each leg's course came from — its
// own file, its stretch of the whole course, or nothing yet — with its own
// add/replace and, for an own file, remove (the leg then falls back to the
// whole course). The legs are coloured as on the map above, and named, so
// the colour is never the only thing telling them apart. The phone shows the
// same rows (TriCourseRows in RacePlansScreen.kt).
import CourseMenu from "./CourseMenu";
import { fmtDistShort } from "./format";

export const LEG_COLORS = { swim: "#2a78d6", bike: "#eda100", run: "#e87ba4" };
const NAMES = { swim: "Swim", bike: "Bike", run: "Run" };

export function sourceLabel(c) {
  if (c?.source === "own") return "own file";
  if (c?.source === "whole") return "from the whole course";
  return "no course";
}

export default function TriCourses({ goalId, plan, imperial, uploading, onUpload, onPicked, onRemove }) {
  const courses = plan.triathlon_courses ?? {};
  return (
    <ul className="divide-y divide-slate-100 dark:divide-slate-800 text-sm" data-testid="tri-courses">
      {["swim", "bike", "run"].map((leg) => {
        const c = courses[leg];
        return (
          <li key={leg} className="flex items-center gap-3 py-2">
            <span className="w-3 h-1 rounded inline-block" style={{ background: LEG_COLORS[leg] }} />
            <span className="w-12 font-medium">{NAMES[leg]}</span>
            <span className="flex-1 text-slate-500 dark:text-slate-400 tabular-nums">
              {c?.distance_m ? `${fmtDistShort(c.distance_m, imperial)} · ` : ""}{sourceLabel(c)}
            </span>
            <CourseMenu goalId={goalId} leg={leg} small hasCourse={c?.source === "own"}
              label={c?.source === "own" ? "Replace" : "Add"} uploading={uploading}
              onUpload={onUpload} onPicked={onPicked} />
            {c?.source === "own" && (
              <button className="btn btn-danger btn-sm" onClick={() => onRemove(leg)}>Remove</button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
