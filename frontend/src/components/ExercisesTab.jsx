// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * ExercisesTab — exercise library browser for the Training page.
 *
 * Left panel:  Interactive body diagram (click a muscle to filter).
 * Right panel: Scrollable exercise list with preference toggles.
 * Bottom left: Movement-pattern balance panel (push/pull/hinge/squat/core).
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import { MuscleMapPicker } from "./activity/charts/MuscleMap";
import { muscleLabel } from "../utils/muscleGroups";
import ExerciseDetailModal from "./ExerciseDetailModal";
import AnimationBadge from "./AnimationBadge";
import AnimationConfirmToggle from "./AnimationConfirmToggle";
import { PlusIcon } from "./ui/Button";

// ── Movement pattern balance ─────────────────────────────────────────────────
// These are the six fundamental patterns sport scientists use to assess
// workout completeness. An endurance athlete's supplementary programme needs
// at least push + pull + hinge to maintain joint health and posture; squat and
// core are secondary priorities.

// Muscle key → canonical muscle group name used by exercise_library
const MUSCLE_TO_LIBRARY = {
  chest: "chest", front_delts: "shoulders", side_delts: "shoulders",
  rear_delts: "shoulders", biceps: "biceps", triceps: "triceps",
  forearms: "forearms", traps: "upper_back", lats: "lats", mid_back: "upper_back",
  lower_back: "lower_back", abs: "core", obliques: "obliques",
  hip_flexors: "hip_flexors", glutes: "glutes", quads: "quads",
  hamstrings: "hamstrings", calves_front: "calves", calves_back: "calves",
  adductors: "hip_adductors", neck: "upper_back",
  ankles: "calves", hands: "hands", feet: "feet",
};

function exerciseMatchesMuscle(ex, muscle) {
  if (!muscle) return true;
  const libKey = MUSCLE_TO_LIBRARY[muscle] || muscle;
  return (
    (ex.primary_muscles || []).includes(libKey) ||
    (ex.secondary_muscles || []).includes(libKey)
  );
}

// ── Preference toggle ──────────────────────────────────────────────────────────
function PrefToggle({ pref, onChange, saving }) {
  return (
    <div className={`flex rounded-lg border overflow-hidden text-[11px] font-medium transition-opacity ${saving ? "opacity-50 pointer-events-none" : ""}`}>
      <button
        onClick={() => onChange(pref === "preferred" ? null : "preferred")}
        className={`px-1.5 py-1 transition-colors ${pref === "preferred"
          ? "bg-accent-500 text-white border-accent-500"
          : "bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-accent-50 dark:hover:bg-accent-900/20 border-slate-200 dark:border-slate-700"}`}
        title="Prefer this exercise — algorithm prioritises it"
      >✓</button>
      <button
        onClick={() => onChange(pref === "excluded" ? null : "excluded")}
        className={`px-1.5 py-1 transition-colors ${pref === "excluded"
          ? "bg-red-500 text-white border-red-500"
          : "bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-red-50 dark:hover:bg-red-900/20 border-slate-200 dark:border-slate-700"}`}
        title="Exclude — algorithm never schedules this"
      >✕</button>
    </div>
  );
}

// ── Single exercise row ────────────────────────────────────────────────────────
function ExerciseRow({ ex, onPrefChange, onEdit, onDelete, onViewDetail,
                       primaryDeviceProductId, onConfirmChange }) {
  const [saving, setSaving] = useState(false);

  const handlePref = async (newPref) => {
    setSaving(true);
    await onPrefChange(ex.name, newPref);
    setSaving(false);
  };

  const patColor = {
    push: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
    pull: "bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-300",
    hinge: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
    squat: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
    isometric: "bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300",
    plyometric: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
    isolation: "bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300",
    rotation: "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300",
  }[ex.movement_pattern] || "bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400";

  const rowBg = ex.preference === "preferred"
    ? "bg-accent-50/50 dark:bg-accent-900/10 border-accent-200 dark:border-accent-800"
    : ex.preference === "excluded"
      ? "bg-red-50/40 dark:bg-red-900/10 border-red-200 dark:border-red-800 opacity-60"
      : "bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700";

  return (
    <div className={`rounded-xl border px-2.5 py-2 flex items-start gap-2 ${rowBg} cursor-pointer`}
      onClick={onViewDetail}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-sm font-medium text-slate-800 dark:text-slate-200 leading-snug hover:text-accent-600 dark:hover:text-accent-400 transition-colors">
            {ex.name}
          </span>
          {ex.is_custom && (
            <span className="text-[10px] px-1 py-0.5 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300">
              custom
            </span>
          )}
          <AnimationBadge
            state={ex.animation_state}
            hasAnimation={ex.has_animation}
            show={ex.animation_state !== undefined || ex.has_animation !== undefined}
          />
          <AnimationConfirmToggle
            category={ex.garmin_category}
            subtype={ex.garmin_subtype}
            state={ex.animation_state}
            disabled={!primaryDeviceProductId}
            onChange={(s) => onConfirmChange(ex.name, s)}
          />
        </div>
        <div className="flex flex-wrap gap-1 mt-1">
          {ex.movement_pattern && (
            <span className={`text-[10px] px-1 py-0.5 rounded-full ${patColor}`}>
              {ex.movement_pattern}
            </span>
          )}
          {(ex.primary_muscles || []).slice(0, 3).map(m => (
            <span key={m} className="badge bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
              {muscleLabel(m)}
            </span>
          ))}
        </div>
        {ex.description && (
          <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-1 line-clamp-1">
            {ex.description}
          </p>
        )}
      </div>
      <div className="flex items-center gap-1.5 shrink-0 mt-0.5" onClick={e => e.stopPropagation()}>
        {ex.is_custom && (
          <>
            <button onClick={() => onEdit(ex)}
              className="btn btn-neutral btn-sm"
              >Edit</button>
            <button onClick={() => onDelete(ex)}
              className="btn btn-danger btn-sm"
              >Delete</button>
          </>
        )}
        <PrefToggle pref={ex.preference} onChange={handlePref} saving={saving} />
      </div>
    </div>
  );
}

// ── Main tab ───────────────────────────────────────────────────────────────────
export default function ExercisesTab({ onOpenCustomModal }) {
  const [exercises, setExercises] = useState([]);
  const [loading, setLoading]     = useState(true);
  const [search, setSearch]       = useState("");
  const [selectedMuscle, setSelectedMuscle] = useState(null);
  const [filter, setFilter]       = useState("all"); // all | preferred | excluded | custom
  const [detailExercise, setDetailExercise] = useState(null);

  // Primary-device product_id powers the AnimationConfirmToggle. We pull it
  // from /garmin/devices (cheap; cached by client.js) on first load.
  const [primaryDevice, setPrimaryDevice] = useState(null);

  const load = useCallback(async () => {
    try {
      const [exData, devices] = await Promise.all([
        api.getExercises(),
        api.getGarminDevices().catch(() => []),
      ]);
      setExercises(exData || []);
      const pri = (devices || []).find(d => d.is_primary);
      setPrimaryDevice(pri || null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // When the user clicks thumbs-up/down, the backend returns the new
  // animation_state for that exercise. We splice it into the local list so
  // the badge re-renders without a full refetch.
  const handleConfirmChange = (name, newState) => {
    setExercises(prev => prev.map(e =>
      e.name === name ? { ...e, animation_state: newState } : e
    ));
  };

  const handleMuscleSelect = (muscle) => {
    setSelectedMuscle(prev => prev === muscle ? null : muscle);
  };

  const handlePrefChange = async (name, pref) => {
    if (pref) {
      await api.setExercisePreference(name, pref);
    } else {
      await api.deleteExercisePreference(name);
    }
    setExercises(prev => prev.map(e =>
      e.name === name ? { ...e, preference: pref ?? null } : e
    ));
  };

  const handleDelete = async (ex) => {
    if (!confirm(`Delete custom exercise "${ex.name}"?`)) return;
    await api.deleteCustomExercise(ex.custom_id);
    setExercises(prev => prev.filter(e => e.name !== ex.name));
  };

  const visible = useMemo(() => {
    let list = exercises;
    if (selectedMuscle) list = list.filter(e => exerciseMatchesMuscle(e, selectedMuscle));
    if (search.trim())  list = list.filter(e => e.name.toLowerCase().includes(search.toLowerCase()));
    if (filter === "preferred") list = list.filter(e => e.preference === "preferred");
    if (filter === "excluded")  list = list.filter(e => e.preference === "excluded");
    if (filter === "custom")    list = list.filter(e => e.is_custom);
    return list;
  }, [exercises, selectedMuscle, search, filter]);

  const preferredCount = exercises.filter(e => e.preference === "preferred").length;
  const excludedCount  = exercises.filter(e => e.preference === "excluded").length;

  if (loading) return (
    <div className="flex justify-center py-11">
      <div className="spinner" />
    </div>
  );

  return (
    <>
    <div className="flex gap-4 min-h-0" style={{ minHeight: 0 }}>
      {/* ── Left column: diagram + balance ───────────────────────────────── */}
      <div className="w-2/5 shrink-0 flex flex-col gap-0">
        <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-2.5">
          <div className="flex items-center justify-between mb-2">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
              Filter by muscle
            </p>
          </div>
          <MuscleMapPicker selected={selectedMuscle} onSelect={handleMuscleSelect} />
        </div>
      </div>

      {/* ── Right column: search + list ───────────────────────────────────── */}
      <div className="flex-1 min-w-0 flex flex-col gap-3">
        {/* Toolbar */}
        <div className="flex items-center gap-2">
          <input
            type="search"
            placeholder="Search exercises…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="field flex-1"
          />
          <button
            onClick={() => onOpenCustomModal(null, load)}
            className="btn btn-primary shrink-0">
            <PlusIcon />Custom
          </button>
        </div>

        {/* Filter pills */}
        <div className="flex gap-1.5 flex-wrap">
          {[
            { key: "all",       label: `All (${exercises.length})` },
            { key: "preferred", label: `Preferred (${preferredCount})` },
            { key: "excluded",  label: `Excluded (${excludedCount})` },
            { key: "custom",    label: "Custom" },
          ].map(f => (
            <button key={f.key} type="button" aria-pressed={filter === f.key}
              onClick={() => setFilter(f.key)} className="bar-pill">
              {f.label}
            </button>
          ))}
        </div>

        {/* Legend */}
        <div className="flex gap-3 text-[10px] text-slate-400 dark:text-slate-500">
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-sm bg-accent-500" /> Preferred — algorithm prioritises
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-sm bg-red-400" /> Excluded — never scheduled
          </span>
        </div>

        {/* Exercise list */}
        <div className="flex-1 overflow-y-auto space-y-1.5 pr-1" style={{ maxHeight: "calc(100vh - 210px)" }}>
          {visible.length === 0 && (
            <div className="text-center py-7 text-slate-400 dark:text-slate-500 text-sm italic">
              {search || selectedMuscle ? "No exercises match the current filter." : "No exercises found."}
            </div>
          )}
          {visible.map(ex => (
            <ExerciseRow
              key={ex.name}
              ex={ex}
              onPrefChange={handlePrefChange}
              onEdit={(e) => onOpenCustomModal(e, load)}
              onDelete={handleDelete}
              onViewDetail={() => setDetailExercise(ex)}
              primaryDeviceProductId={primaryDevice?.product_id}
              onConfirmChange={handleConfirmChange}
            />
          ))}
        </div>
      </div>
    </div>

    {detailExercise && (
      <ExerciseDetailModal
        exercise={detailExercise}
        onClose={() => setDetailExercise(null)}
      />
    )}
    </>
  );
}
