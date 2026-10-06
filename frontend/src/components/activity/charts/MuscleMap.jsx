// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Anatomical muscle-map components built on react-muscle-highlighter. Two
// exports share the front/back Body renderer and the PAIRED left/right→group
// map: MuscleMapPicker (interactive — click muscles to select target groups,
// used by the workout/stretch builders) and MuscleMap (read-only — shades each
// muscle by its activation level for an activity, the default export).
import React, { useState, useMemo, useEffect } from "react";
import Body from "react-muscle-highlighter";
import { muscleLabel } from "../../../utils/muscleGroups";
import { BODY_VIEWER_REGIONS } from "../../../spec/muscleGroups";
import { api } from "../../../api/client";

// Both tables below were object literals here until the mobile app needed the
// same diagram. They now come from spec/muscle_groups.yaml via codegen, which
// is what stops the phone and the browser shading the same session
// differently — see the `body_viewer` comment in that spec for why the mapping
// cannot be derived from the muscle keys.
//
// Reshaped rather than used directly, so everything downstream reads exactly
// as it did. One behaviour did change: the back-view neck regions had no
// pairing entry, so they looked up an activation key nothing produces and the
// neck never shaded on the back. The spec corrects that.
const PAIRED = Object.fromEntries(
  Object.entries(BODY_VIEWER_REGIONS).map(([region, m]) => [region, m.muscle]),
);

const DEFAULT_MUSCLE = "#64748b";

function muscleColor(t) {
  if (!t || t <= 0) return DEFAULT_MUSCLE;
  const v = Math.min(1, t);
  const hue = v < 0.5 ? 220 - 182 * (v / 0.5) : 38 - 34 * ((v - 0.5) / 0.5);
  const sat = 72 + 22 * v;
  const lit = 60 - 16 * v;
  const alpha = 0.42 + 0.53 * v;
  return `hsla(${hue.toFixed(0)}, ${sat.toFixed(0)}%, ${lit.toFixed(0)}%, ${alpha.toFixed(2)})`;
}

const MUSCLE_LIB_MAP = Object.fromEntries(
  Object.entries(BODY_VIEWER_REGIONS).map(([region, m]) => [
    region,
    { slug: m.slug, view: m.view },
  ]),
);

const MUSCLE_TO_LIBRARY = {
  chest: "chest", front_delts: "shoulders", side_delts: "shoulders",
  rear_delts: "shoulders", biceps: "biceps", triceps: "triceps",
  forearms: "forearms", traps: "upper_back", lats: "lats", mid_back: "upper_back",
  lower_back: "lower_back", abs: "core", obliques: "obliques",
  hip_flexors: "hip_flexors", glutes: "glutes", quads: "quads",
  hamstrings: "hamstrings", calves_front: "calves", calves_back: "calves",
  adductors: "hip_adductors", neck: "neck",
};

const ALL_FRONT_PARTS = [
  "abs","adductors","ankles","biceps","calves","chest","deltoids",
  "feet","forearm","hair","hands","head","knees","neck","obliques",
  "quadriceps","tibialis","trapezius","triceps",
];
const ALL_BACK_PARTS = [
  "adductors","ankles","calves","deltoids","feet","forearm","gluteal",
  "hair","hamstring","hands","head","lower-back","neck","trapezius",
  "triceps","upper-back",
];

function buildBodyData(activation, view) {
  const slugVal = {};
  for (const [key, m] of Object.entries(MUSCLE_LIB_MAP)) {
    if (m.view !== view) continue;
    const canonical = PAIRED[key] || key;
    const val = activation[canonical] || 0;
    if (!slugVal[m.slug] || val > slugVal[m.slug]) slugVal[m.slug] = val;
  }
  const allParts = view === "front" ? ALL_FRONT_PARTS : ALL_BACK_PARTS;
  return allParts.map(slug => ({
    slug,
    styles: {
      fill: slugVal[slug] !== undefined ? muscleColor(slugVal[slug]) : DEFAULT_MUSCLE,
    },
  }));
}

// Given a selected key (canonical like "chest" or paired like "chest_l"),
// return the set of library slugs to highlight on the given view.
function getGroupSlugs(selectedKey, view) {
  if (!selectedKey) return new Set();
  let m = MUSCLE_LIB_MAP[selectedKey];
  if (!m) {
    // selectedKey is a canonical key — find any paired key that maps to it
    for (const [key, info] of Object.entries(MUSCLE_LIB_MAP)) {
      if ((PAIRED[key] || key) === selectedKey && info.view === view) {
        m = info;
        break;
      }
    }
    // Also check across views
    if (!m) {
      for (const [key, info] of Object.entries(MUSCLE_LIB_MAP)) {
        if ((PAIRED[key] || key) === selectedKey) {
          m = info;
          break;
        }
      }
    }
    if (!m) return new Set();
  }
  const libSlug = m.slug;
  const slugs = new Set();
  for (const [, info] of Object.entries(MUSCLE_LIB_MAP)) {
    if (info.slug === libSlug && info.view === view) {
      slugs.add(info.slug);
    }
  }
  return slugs;
}

function buildSelectedData(selected, view) {
  const allParts = view === "front" ? ALL_FRONT_PARTS : ALL_BACK_PARTS;
  const highlightSlugs = getGroupSlugs(selected, view);
  return allParts.map(slug => ({
    slug,
    styles: {
      fill: highlightSlugs.has(slug) ? "rgba(16,185,129,0.55)" : DEFAULT_MUSCLE,
    },
  }));
}

function libToMuscleKey(libSlug, libSide, view) {
  let best = null;
  for (const [key, m] of Object.entries(MUSCLE_LIB_MAP)) {
    if (m.slug === libSlug && m.view === view) {
      if (libSide && key.endsWith(`_${libSide.slice(0, 1)}`)) {
        return key; // exact side match — best possible
      }
      if (!best) best = key; // fallback without side match
    }
  }
  return best;
}

function useUserSex() {
  const [sex, setSex] = useState("male");
  useEffect(() => {
    api.getSettings().then(s => { if (s?.sex) setSex(s.sex); }).catch(() => {});
  }, []);
  return sex;
}

const STYLES = `
.muscle-body-wrap svg {
  width: 100% !important;
  height: 100% !important;
}
.rmh-clickable {
  cursor: pointer;
}
.rmh-disabled {
  cursor: not-allowed;
}
.muscle-body-wrap svg > path {
  transition: filter 0.15s ease, stroke 0.10s ease, stroke-width 0.10s ease;
}
.muscle-body-wrap svg > path:hover {
  stroke: #10b981;
  stroke-width: 2px;
  filter: drop-shadow(0 0 6px rgba(16, 185, 129, 0.5));
}
.muscle-body-wrap svg > path.selected-muscle {
  stroke: #059669;
  stroke-width: 2px;
  filter: drop-shadow(0 0 8px rgba(5, 150, 105, 0.6));
}
`;

// ═══════════════════════════════════════════════════════════════
//  PICKER VARIANT
// ═══════════════════════════════════════════════════════════════

export function MuscleMapPicker({ selected, onSelect }) {
  const gender = useUserSex();

  const handlePartPress = (part, side) => {
    const key = libToMuscleKey(part.slug, side, "front") || libToMuscleKey(part.slug, side, "back");
    if (key) onSelect(PAIRED[key] || key);
  };

  return (
    <div className="flex flex-col gap-0">
      <style>{STYLES}</style>
      <div className="flex gap-1.5 muscle-body-wrap relative" style={{ height: 420 }}>
        <div className="flex flex-col items-center flex-1 min-h-0">
          <div className="flex-1 w-full min-h-0 overflow-hidden">
            <Body
              data={buildSelectedData(selected, "front")}
              side="front" gender={gender} scale={1}
              defaultFill="#e2e8f0" defaultStroke="rgba(100,116,139,0.25)"
              border="rgba(100,116,139,0.40)"
              onBodyPartPress={(p, s) => handlePartPress(p, s)}
            />
          </div>
          <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400 mt-1 text-center shrink-0">Front</p>
        </div>
        <div className="flex flex-col items-center flex-1 min-h-0">
          <div className="flex-1 w-full min-h-0 overflow-hidden">
            <Body
              data={buildSelectedData(selected, "back")}
              side="back" gender={gender} scale={1}
              defaultFill="#e2e8f0" defaultStroke="rgba(100,116,139,0.25)"
              border="rgba(100,116,139,0.40)"
              onBodyPartPress={(p, s) => handlePartPress(p, s)}
            />
          </div>
          <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400 mt-1 text-center shrink-0">Back</p>
        </div>
        {selected && (
          <span className="badge absolute bottom-1 left-1/2 -translate-x-1/2 text-white bg-accent-600 shadow">
            {muscleLabel(selected)}
          </span>
        )}
      </div>
    </div>
  );
}

export function MuscleMap({ activation = {}, totals = {}, hasData = true }) {
  const gender = useUserSex();
  const frontData = useMemo(() => buildBodyData(activation, "front"), [activation]);
  const backData  = useMemo(() => buildBodyData(activation, "back"),  [activation]);
  const totalLoad = Object.values(totals).reduce((a, b) => a + b, 0);
  const ranked = Object.entries(totals)
    .filter(([, v]) => v > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([key, v]) => [key, totalLoad > 0 ? v / totalLoad : 0]);

  return (
    <div className="card flex-1 min-h-0 flex flex-col">
      <style>{STYLES}</style>
      <p className="section-title mb-3">Muscles Worked</p>
      {!hasData && (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-sm text-slate-400 dark:text-slate-500 italic">No data recorded</p>
        </div>
      )}
      {hasData && <>
      <div className="flex gap-2 flex-1 min-h-0 muscle-body-wrap">
        <div className="flex flex-col items-center flex-1 min-h-0">
          <div className="flex-1 w-full min-h-0">
            <Body data={frontData} side="front" gender={gender} scale={1}
              defaultFill="#e2e8f0" defaultStroke="rgba(100,116,139,0.25)" border="rgba(100,116,139,0.40)" />
          </div>
          <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400 mt-1 text-center">Anterior <span className="text-slate-300 dark:text-slate-600">(Front)</span></p>
        </div>
        <div className="flex flex-col items-center flex-1 min-h-0">
          <div className="flex-1 w-full min-h-0">
            <Body data={backData} side="back" gender={gender} scale={1}
              defaultFill="#e2e8f0" defaultStroke="rgba(100,116,139,0.25)" border="rgba(100,116,139,0.40)" />
          </div>
          <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400 mt-1 text-center">Posterior <span className="text-slate-300 dark:text-slate-600">(Back)</span></p>
        </div>
      </div>
      {ranked.length > 0 && (
        <div className="mt-3 pt-2.5 border-t border-slate-100 dark:border-slate-800">
          <p className="text-[10px] uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-2">Top muscle groups</p>
          <div className="space-y-1.5">
            {ranked.map(([key, val]) => (
              <div key={key} className="flex items-center gap-2 text-xs">
                <span className="w-28 truncate text-slate-600 dark:text-slate-300">{muscleLabel(key)}</span>
                <div className="flex-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
                  <div className="h-full rounded-full" style={{ width: `${Math.round(val * 100)}%`, background: muscleColor(activation[key] || 0) }} />
                </div>
                <span className="text-[10px] text-slate-400 w-8 text-right">{Math.round(val * 100)}%</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="mt-3 pt-2.5 border-t border-slate-100 dark:border-slate-800 flex items-center justify-center gap-3 text-[10px] text-slate-400 dark:text-slate-500">
        {[["Low", 0.15], ["Moderate", 0.4], ["Heavy", 0.7], ["Max", 1.0]].map(([label, t]) => (
          <span key={label} className="flex items-center gap-1">
            <span className="inline-block w-2.5 h-2.5 rounded-sm" style={{ background: muscleColor(t) }} />{label}
          </span>
        ))}
      </div>
      </>}
    </div>
  );
}

export default MuscleMap;
