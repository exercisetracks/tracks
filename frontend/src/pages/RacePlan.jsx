// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Race Plan page. Loads a goal + its generated pacing plan, orchestrates all the
// plan-editing state (split-strategy slider, terrain type, GPX course upload,
// weather pin, generation), and lays out the result sections. The data-loading
// and mutation handlers stay here on purpose; the low-coupling presentational
// pieces (map, elevation chart, split slider, pace tables, strategy panel,
// weather bar, sport-specific sections) live in components/raceplan/.

import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import { useTheme } from "../context/ThemeContext";
import { getPerLapDrinks } from "../utils/fuelingUtils";
import { fmtDist, fmtDistShort, recomputeLaps } from "../components/raceplan/format";
import { COURSE_OPTIONS_METRIC, COURSE_OPTIONS_IMPERIAL, HR_OPTIONS } from "../components/raceplan/constants";
import { Section, RadioGroup, SyncStatus } from "../components/raceplan/ui";
import SplitSlider from "../components/raceplan/SplitSlider";
import SwimmingSection from "../components/raceplan/SwimmingSection";
import TriathlonSection from "../components/raceplan/TriathlonSection";
import WeatherBar from "../components/raceplan/WeatherBar";
import ElevationChart from "../components/raceplan/ElevationChart";
import RaceMap from "../components/raceplan/RaceMap";
import StrategyPanel from "../components/raceplan/StrategyPanel";
import FuelSection from "../components/raceplan/FuelSection";
import SavedCoursePicker from "../components/raceplan/SavedCoursePicker";
import PaceResults from "../components/raceplan/PaceResults";

// ── Main page ─────────────────────────────────────────────────────────────────

export default function RacePlan() {
  const { id: goalId } = useParams();
  const { colorScheme } = useTheme();
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const isDark = colorScheme === "dark" || (colorScheme === "system" && systemDark);

  const [goal,         setGoal]        = useState(null);
  const [plan,         setPlan]        = useState(null);
  const [loading,      setLoading]     = useState(true);
  const [generating,   setGenerating]  = useState(false);
  const [error,        setError]       = useState(null);
  const [gpxUploading, setGpxUploading]= useState(false);
  const [splitDraft,   setSplitDraft]  = useState(0);

  const fileRef    = useRef(null);
  const splitTimer = useRef(null);

  // Derived from plan
  const imperial = plan?.units === "imperial";
  const maxHr    = plan?.max_hr ?? null;

  useEffect(() => {
    Promise.all([api.getGoal(goalId), api.getRacePlan(goalId)])
      .then(([g, p]) => { setGoal(g); setPlan(p); setSplitDraft(p.split_spread ?? 0); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [goalId]);

  async function patchPlan(updates) {
    try {
      const updated = await api.updateRacePlan(goalId, updates);
      setPlan(updated);
      if (updates.split_spread !== undefined) setSplitDraft(updates.split_spread);
    } catch (e) {
      setError(e.message);
    }
  }

  // Slider: update local state immediately (realtime table), debounce the PATCH
  function handleSplitChange(val) {
    setSplitDraft(val);
    clearTimeout(splitTimer.current);
    splitTimer.current = setTimeout(() => patchPlan({ split_spread: val }), 500);
  }

  function handlePin(lat, lon) { patchPlan({ pin_lat: lat, pin_lon: lon }); }

  async function handleCourseTypeChange(v) {
    const hadPlan = !!plan?.generated_at;
    await patchPlan({ course_type: v });
    if (hadPlan && !hasCourse) generate();
  }

  async function handleGpxUpload(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    setGpxUploading(true);
    setError(null);
    try {
      const updated = await api.uploadCoursGpx(goalId, file);
      setPlan(updated);
      setSplitDraft(updated.split_spread ?? 0);
    } catch (ex) {
      setError(ex.message || "Failed to upload GPX");
    } finally {
      setGpxUploading(false);
      e.target.value = "";
    }
  }

  async function removeCourse() {
    await api.deleteCourseGpx(goalId);
    const updated = await api.getRacePlan(goalId);
    setPlan(updated);
  }

  async function generate() {
    setGenerating(true);
    setError(null);
    try {
      const updated = await api.generateRacePlan(goalId);
      setPlan(updated);
      setSplitDraft(updated.split_spread ?? 0);
    } catch (ex) {
      setError(ex.message || "Failed to generate plan");
    } finally {
      setGenerating(false);
    }
  }

  // Realtime lap recompute — no backend call needed when slider moves
  const displayLaps = useMemo(
    () => recomputeLaps(plan?.lap_paces, plan?.predicted_seconds, splitDraft, imperial),
    [plan?.lap_paces, plan?.predicted_seconds, splitDraft, imperial]
  );

  // Per-lap drink markers for the pace table (weather-aware sip schedule)
  const perLapDrinks = useMemo(() => {
    try {
      return getPerLapDrinks(
        displayLaps,
        plan?.predicted_seconds,
        plan?.fueling_plan?.carbs_g_per_h,
        plan?.weather_snapshot,
      );
    } catch {
      return [];
    }
  }, [
      displayLaps,
      plan?.predicted_seconds,
      plan?.fueling_plan?.carbs_g_per_h,
      plan?.weather_snapshot,
    ],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[40vh]">
        <div className="w-6 h-6 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (!goal) {
    return (
      <div className="max-w-3xl mx-auto px-3.5 py-7 text-center text-slate-500">
        Goal not found.{" "}
        <Link to="/race-plans" className="text-accent-600 underline">Back to Race Plans</Link>
      </div>
    );
  }

  const hasCourse   = plan?.has_course;
  const hasLaps     = displayLaps.length > 0;
  const coursePath  = plan?.course_path;
  const distKm      = goal.event_distance_meters ? (goal.event_distance_meters / 1000).toFixed(1) : null;
  const distLabel   = goal.event_distance_meters
    ? fmtDistShort(goal.event_distance_meters, plan?.units === "imperial")
    : null;
  const sport       = plan?.sport ?? "running";
  const isCycling   = sport === "cycling";
  const isSwimming  = sport === "swimming";
  const isTriathlon = sport === "triathlon";

  // Map center: prefer course path bounds (handled by FitBounds), then pin, then world
  const mapCenter = plan?.pin_lat != null ? [plan.pin_lat, plan.pin_lon] : [20, 0];
  const mapZoom   = plan?.pin_lat != null && !hasCourse ? 10 : 2;

  return (
    <div className="max-w-4xl mx-auto px-3.5 py-5 space-y-6">

      {/* Header */}
      <div className="flex items-start gap-3">
        <Link to="/race-plans" className="mt-1 shrink-0 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors">
          <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </Link>
        <div className="flex-1">
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">
            Race Plan — {goal.event_name || "Event"}
          </h1>
          {distLabel && (
            <p className="text-sm text-slate-500 dark:text-slate-400">
              {distLabel} · {goal.event_sport || "running"}{goal.event_date ? ` · ${goal.event_date}` : ""}
            </p>
          )}
        </div>
        {plan?.predicted_time && (
          <div className="text-right shrink-0">
            <div className="text-xs text-slate-400 dark:text-slate-500">Predicted finish</div>
            <div className="text-2xl font-bold text-accent-600 dark:text-accent-400 font-mono">
              {plan.predicted_time}
            </div>
          </div>
        )}
      </div>

      {error && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3.5 py-2.5 flex items-center gap-2">
          <svg className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {error}
          <button className="ml-auto text-red-400 hover:text-red-600" onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {/* Split strategy — hide for swimming (no laps) */}
      {!isSwimming && (
        <Section title="Split strategy">
          <SplitSlider
            value={splitDraft}
            onChange={handleSplitChange}
            displayLaps={displayLaps}
            imperial={imperial}
            isCycling={isCycling}
          />
        </Section>
      )}

      {/* Course */}
      <Section title="Course">
        <div className="space-y-4">
          {!hasCourse && (
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mb-2">
                Select terrain type (used when no GPX is uploaded):
              </p>
              <RadioGroup
                name="course_type" options={imperial ? COURSE_OPTIONS_IMPERIAL : COURSE_OPTIONS_METRIC}
                value={plan?.course_type ?? "flat"}
                onChange={handleCourseTypeChange}
              />
            </div>
          )}

          {/* GPX upload */}
          <div className="flex items-center gap-3 flex-wrap">
            <button
              onClick={() => fileRef.current?.click()} disabled={gpxUploading}
              className="btn btn-tonal"
            >
              {gpxUploading ? "Uploading…" : hasCourse ? "Replace GPX" : "Upload GPX course"}
            </button>
            <input ref={fileRef} type="file" accept=".gpx" className="hidden" onChange={handleGpxUpload} />
            <Link to={`/maps?raceGoal=${goalId}`}
              className="px-2.5 py-1.5 text-sm rounded-lg border border-slate-300 dark:border-slate-600 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors">
              Draw on map
            </Link>
            <SavedCoursePicker goalId={goalId}
              onPicked={(updated) => { setPlan(updated); setSplitDraft(updated.split_spread ?? 0); }} />
            {hasCourse && (
              <div className="flex items-center gap-3 flex-wrap text-sm text-accent-600 dark:text-accent-400">
                <svg className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                {fmtDistShort(plan.course_distance_m, imperial)} · +{imperial ? `${Math.round(plan.course_gain_m * 3.28084)} ft` : `${plan.course_gain_m} m`} gain
                {plan.technicality_label && (
                  <span className={`inline-flex items-center text-xs font-medium px-1.5 py-0.5 rounded-full ${
                    plan.technicality_label === "Smooth"         ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-400" :
                    plan.technicality_label === "Rolling"        ? "bg-sky-50 text-sky-700 dark:bg-sky-900/20 dark:text-sky-400" :
                    plan.technicality_label === "Technical"      ? "bg-amber-50 text-amber-700 dark:bg-amber-900/20 dark:text-amber-400" :
                                                                   "bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-400"
                  }`}>
                    {plan.technicality_label}
                  </span>
                )}
                <button onClick={removeCourse}
                  className="btn btn-danger btn-sm">
                  Remove
                </button>
              </div>
            )}
          </div>

          {/* Distance source toggle — only shown when GPX is loaded */}
          {hasCourse && goal.event_distance_meters && plan.course_distance_m && (
            <div className="flex flex-col gap-1.5">
              <p className="text-xs text-slate-500 dark:text-slate-400">Plan against which distance?</p>
              <div className="flex gap-2">
                {[
                  {
                    value: false,
                    label: `Goal distance`,
                    sub:   fmtDist(goal.event_distance_meters, imperial),
                  },
                  {
                    value: true,
                    label: `GPX distance`,
                    sub:   fmtDist(plan.course_distance_m, imperial),
                  },
                ].map(opt => (
                  <button
                    key={String(opt.value)}
                    onClick={() => patchPlan({ use_gpx_distance: opt.value })}
                    className={`flex flex-col items-start px-2.5 py-1.5 rounded-lg border text-sm transition-colors ${
                      (plan.use_gpx_distance ?? false) === opt.value
                        ? "border-accent-500 bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-300"
                        : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300"
                    }`}
                  >
                    <span className="font-medium">{opt.label}</span>
                    <span className="text-xs opacity-75">{opt.sub}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Map */}
          <div>
            <p className="text-xs text-slate-500 dark:text-slate-400 mb-2">
              {hasCourse
                ? "Course route shown. Weather pulled from course start automatically."
                : "Click to pin your race start location for weather data (fetched from Open-Meteo — coordinates are transmitted)."}
            </p>
            <div className="rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700" style={{ height: 300 }}>
              <RaceMap
                coursePath={coursePath}
                pinLat={plan?.pin_lat}
                pinLon={plan?.pin_lon}
                onPin={handlePin}
                disabled={hasCourse}
                theme={isDark ? "dark" : "light"}
                center={mapCenter}
                zoom={mapZoom}
              />
            </div>

            <ElevationChart coursePath={coursePath} isDark={isDark} imperial={imperial} />

            {/* Pin controls — hidden when GPX is loaded (auto-set from course) */}
            {!hasCourse && (
              plan?.pin_lat != null ? (
                <div className="flex items-center justify-between mt-1.5">
                  <span className="text-xs text-slate-400 dark:text-slate-500">
                    Weather pin: {plan.pin_lat.toFixed(4)}, {plan.pin_lon.toFixed(4)}
                  </span>
                  <button
                    onClick={() => patchPlan({ pin_lat: null, pin_lon: null })}
                    className="btn btn-neutral btn-sm gap-1"
                  >
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                    Clear pin
                  </button>
                </div>
              ) : (
                <p className="text-xs text-slate-400 dark:text-slate-500 mt-1.5">
                  No pin set — click the map to add a weather location.
                </p>
              )
            )}
            {hasCourse && plan?.pin_lat != null && (
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-1.5">
                Weather location: course start ({plan.pin_lat.toFixed(4)}, {plan.pin_lon.toFixed(4)})
              </p>
            )}
          </div>
        </div>
      </Section>

      {/* Garmin targets — hide for swimming (no watch file generated) */}
      {!isSwimming && (
        <Section title="Garmin workout targets">
          <RadioGroup
            name="hr_mode" options={HR_OPTIONS}
            value={plan?.pace_hr_mode ?? "pace"}
            onChange={v => patchPlan({ pace_hr_mode: v })}
          />
          <p className="text-xs text-slate-400 dark:text-slate-500">
            {isCycling
              ? <>Power targets generate per-km intervals. HR ceiling adds a per-lap cap scaled from conservative (start) to near-max (finish) — so you have legs left for a strong finish.</>
              : <>Pace targets give a ±15 sec/{imperial ? "mi" : "km"} alert zone per lap. HR ceiling adds a per-lap cap that starts conservative and builds toward max — so you don&apos;t blow up early.</>
            }
            {" "}
            {maxHr
              ? <span>Your max HR is <strong>{maxHr} bpm</strong>.</span>
              : <>Max HR not set. <Link to="/settings" className="text-accent-500 hover:underline">Set it in Settings →</Link></>
            }
          </p>
        </Section>
      )}

      {/* Generate */}
      <div className="flex items-center gap-4 flex-wrap">
        <button
          onClick={generate} disabled={generating}
          className="px-4 py-2 bg-accent-600 hover:bg-accent-700 disabled:opacity-50 text-white text-sm font-semibold rounded-xl transition-colors flex items-center gap-2"
        >
          {generating ? (
            <>
              <span className="w-4 h-4 border-2 border-white/40 border-t-white rounded-full animate-spin" />
              Generating…
            </>
          ) : (
            <>
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              Generate Race Plan
            </>
          )}
        </button>
        <SyncStatus watchUploadedAt={plan?.watch_uploaded_at} hasLaps={hasLaps} />
      </div>

      {/* Results */}
      {plan?.generated_at && (
        <>
          {/* Weather — not shown for swimming (conditions don't affect pool pace) */}
          {plan.weather_snapshot && !isSwimming && (
            <Section title="Race-day conditions">
              <WeatherBar weather={plan.weather_snapshot} />
            </Section>
          )}

          {/* Race-day strategy panel: HR ceiling + fueling guidance (MTB + cycling) */}
          {(plan.target_hr_ceiling || plan.fueling_plan) && (
            <Section title="Race-day strategy">
              <StrategyPanel
                targetHrCeiling={plan.target_hr_ceiling}
                fuelingPlan={plan.fueling_plan}
              />
            </Section>
          )}

          {!isSwimming && plan.predicted_seconds > 0 && (
            <Section title="Fuelling">
              <FuelSection goalId={goalId} plan={plan} onPatch={patchPlan} />
            </Section>
          )}

          {/* Swimming: simple target-pace view */}
          {isSwimming && (
            <Section title="Swim plan">
              <SwimmingSection plan={plan} />
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-2">
                Based on Critical Swim Speed (CSS) — the swimming equivalent of lactate threshold.
                Generated {new Date(plan.generated_at).toLocaleString()}.
              </p>
            </Section>
          )}

          {/* Triathlon: three-leg breakdown */}
          {isTriathlon && (
            <Section title="Race plan by segment">
              <TriathlonSection
                plan={plan}
                imperial={imperial}
                hrMode={plan?.pace_hr_mode}
                maxHr={maxHr}
                hasCourse={hasCourse}
              />
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-2">
                Swim uses CSS, bike uses Critical Power model, run uses VDOT.
                Includes 5 min T1 + 3 min T2 transitions.
                Generated {new Date(plan.generated_at).toLocaleString()}.
              </p>
            </Section>
          )}

          {/* Running / Cycling: km-by-km table */}
          {!isSwimming && !isTriathlon && hasLaps && (
            <Section title={
              isCycling
                ? `${imperial ? "Mile" : "Km"}-by-${imperial ? "mile" : "km"} power targets${hasCourse ? " (grade-adjusted)" : ""}`
                : `${imperial ? "Mile" : "Km"}-by-${imperial ? "mile" : "km"} targets${hasCourse ? " (grade-adjusted)" : ""}`
            }>
              <PaceResults
                plan={plan}
                laps={displayLaps}
                perLapDrinks={perLapDrinks}
                imperial={imperial}
                isCycling={isCycling}
                hasCourse={hasCourse}
                hrMode={plan?.pace_hr_mode}
                maxHr={maxHr}
                sport={sport}
              />
            </Section>
          )}
        </>
      )}
    </div>
  );
}
