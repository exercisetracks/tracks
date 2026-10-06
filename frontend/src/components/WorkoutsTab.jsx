// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Workouts library tab (strength). Lists the user's saved strength workout
// templates with tag filtering, and the create/edit form: name, tag chips, a
// MuscleMapPicker, and a drag-and-drop (@dnd-kit) ordered list of exercises.
// Presentational pieces live in ./workouts/ (SortableExercise, ExercisePickRow,
// constants); this file owns the tab's data loading and edit state.
import { PlusIcon } from "./ui/Button";
import { useState, useEffect, useCallback } from "react";
import { api } from "../api/client";
import { MuscleMapPicker } from "./activity/charts/MuscleMap";
import { muscleLabel } from "../utils/muscleGroups";
import ExerciseDetailModal from "./ExerciseDetailModal";
import { VALID_TAGS, TAG_LABELS, MUSCLE_TO_LIBRARY } from "./workouts/constants";
import SortableExercise from "./workouts/SortableExercise";
import BlockStructure from "./workouts/BlockStructure";
import { InfoTooltip } from "./Charts/fitness/FitnessChartParts";
import { remove as removeRow, toEditor, fromEditor, isMarker } from "../lib/blocks";
import ExercisePickRow from "./workouts/ExercisePickRow";

// ── Main Workouts Tab ───────────────────────────────────────────────────────

// Client-only ids for editor rows (the `_id` is stripped before saving).
let _exerciseIdCounter = 0;
function nextExerciseId() { return `ex_${++_exerciseIdCounter}`; }

export default function WorkoutsTab() {
  const [exercises, setExercises] = useState([]);
  const [workouts, setWorkouts] = useState([]);
  const [selectedMuscle, setSelectedMuscle] = useState(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [editMode, setEditMode] = useState(false);
  const [workoutName, setWorkoutName] = useState("");
  const [workoutDesc, setWorkoutDesc] = useState("");
  const [workoutTags, setWorkoutTags] = useState([]);
  const [workoutExercises, setWorkoutExercises] = useState([]); // { _id, exercise_name, ... }
  const [includeInPlan, setIncludeInPlan] = useState(true);
  const [syncToWatch, setSyncToWatch] = useState(false);
  const [editingId, setEditingId] = useState(null);

  // Exercise detail popup
  const [detailExercise, setDetailExercise] = useState(undefined);


  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [exData, wos] = await Promise.all([
        api.getExercises().catch(() => []),
        api.getWorkouts().catch(() => []),
      ]);
      const all = (exData || []).map(e => ({ ...e, searchName: (e.name || "").toLowerCase() }));
      setExercises(all);
      setWorkouts(Array.isArray(wos) ? wos : []);
    } catch { /* ignore */ }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // ── Filter ────────────────────────────────────────────────────────────────

  const filtered = exercises.filter(e => {
    const q = search.toLowerCase().trim();
    if (q) {
      const nameMatch = (e.name || "").toLowerCase().includes(q);
      if (nameMatch) return true;
      // Search muscle tags — check if any primary/secondary muscle matches the query
      const muscles = [...(e.primary_muscles || []), ...(e.secondary_muscles || [])];
      const muscleMatch = muscles.some(m => {
        if (!m) return false;
        // Direct muscle key match
        if (m.toLowerCase().includes(q)) return true;
        // Look up the human-readable label
        const label = muscleLabel(m);
        if (label && label.toLowerCase().includes(q)) return true;
        return false;
      });
      return muscleMatch;
    }
    if (selectedMuscle) {
      const libKey = MUSCLE_TO_LIBRARY[selectedMuscle] || selectedMuscle;
      const muscles = [...(e.primary_muscles || []), ...(e.secondary_muscles || [])];
      return muscles.includes(libKey);
    }
    return true;
  });

  // ── Handlers ──────────────────────────────────────────────────────────────

  const handleMuscleSelect = (key) => {
    setSelectedMuscle(prev => prev === key ? null : key);
  };

  const addExerciseToWorkout = (ex) => {
    setWorkoutExercises(prev => [...prev, {
      _id: nextExerciseId(),
      exercise_name: ex.name,
      exercise_source: "library",
      target_reps: ex.default_reps || 8,
      target_sets: ex.default_sets || 3,
      rir_target: 2,
      rest_seconds: 90,
      weight_method: "percentage_e1rm",
      weight_value: 0.75,
      order_index: prev.length,
      notes: null,
    }]);
  };

  const updateExercise = (id, field, value) => {
    setWorkoutExercises(prev => prev.map(e => e._id === id ? { ...e, [field]: value } : e));
  };

  const removeExercise = (id) => {
    setWorkoutExercises(prev => prev.filter(e => e._id !== id));
  };

  const toggleTag = (tag) => {
    setWorkoutTags(prev => prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag]);
  };

  const startNew = () => {
    setEditMode(true);
    setWorkoutName(""); setWorkoutDesc(""); setWorkoutTags([]);
    setWorkoutExercises([]); setIncludeInPlan(true); setSyncToWatch(false);
    setEditingId(null);
  };

  const startEdit = (wo) => {
    setEditMode(true);
    setWorkoutName(wo.name);
    setWorkoutDesc(wo.description || "");
    setWorkoutTags(wo.tags || []);
    setIncludeInPlan(wo.include_in_plan !== false);
    setSyncToWatch(wo.sync_to_watch === true);
    setWorkoutExercises(toEditor((wo.exercises || []).map(e => ({
      _id: nextExerciseId(),
      exercise_name: e.exercise_name, exercise_source: e.exercise_source || "library",
      target_reps: e.target_reps,
      target_sets: e.target_sets, rir_target: e.rir_target,
      rest_seconds: e.rest_seconds, weight_method: e.weight_method,
      weight_value: e.weight_value, order_index: e.order_index, notes: e.notes,
      item_kind: e.item_kind, group_uid: e.group_uid, group_kind: e.group_kind,
      group_rounds: e.group_rounds, group_rest_seconds: e.group_rest_seconds,
    }))));
    setEditingId(wo.id);
  };

  const saveWorkout = async () => {
    if (!workoutName.trim() || fromEditor(workoutExercises).length === 0) return;
    setSaving(true);
    try {
      const body = {
        name: workoutName.trim(), description: workoutDesc || null,
        tags: workoutTags, include_in_plan: includeInPlan,
        sync_to_watch: syncToWatch,
        exercises: fromEditor(workoutExercises).map(({ _id, ...ex }) => ex),
      };
      if (editingId) await api.updateWorkout(editingId, body);
      else await api.createWorkout(body);
      await load();
      setEditMode(false); setEditingId(null);
    } catch (err) { alert(err.message || "Failed to save workout"); }
    setSaving(false);
  };

  const deleteWorkout = async (id) => {
    if (!confirm("Delete this workout?")) return;
    try { await api.deleteWorkout(id); await load(); } catch {}
  };

  // Open detail popup for a library exercise or a configured exercise
  const viewExerciseDetail = (ex) => {
    const libEx = exercises.find(e => e.name === ex.exercise_name);
    if (libEx) setDetailExercise(libEx);
  };

  if (loading) return (
    <div className="flex justify-center py-11">
      <div className="w-5 h-5 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
    </div>
  );

  return (
    <div className="flex gap-4 min-h-0" style={{ minHeight: 0 }}>
      {/* ── LEFT ──────────────────────────────────────────────────────────── */}
      <div className="w-2/5 shrink-0 flex flex-col gap-3 overflow-y-auto" style={{ maxHeight: "calc(100vh - 100px)" }}>
        <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-2.5 shrink-0">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-2">Filter by muscle</p>
          <MuscleMapPicker selected={selectedMuscle} onSelect={handleMuscleSelect} />
        </div>

        <div className="flex-1 overflow-y-auto rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-2.5">
          <div className="flex items-center gap-2 mb-2">
            <input type="search" placeholder="Search by name or muscle…" value={search}
              onChange={e => setSearch(e.target.value)}
              className="flex-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-1.5 py-1 text-xs text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40" />
          </div>
          <div className="space-y-1">
            {filtered.slice(0, 60).map(ex => (
              <ExercisePickRow key={ex.name} name={ex.name}
                primary={ex.primary_muscles} secondary={ex.secondary_muscles}
                equipment={ex.equipment}
                onAdd={() => addExerciseToWorkout(ex)}
                onViewDetail={() => setDetailExercise(ex)} />
            ))}
            {exercises.length > 0 && filtered.length === 0 && (
              <p className="text-xs text-slate-400 italic text-center py-3.5">No exercises match your search</p>
            )}
          </div>
        </div>
      </div>

      {/* ── RIGHT ─────────────────────────────────────────────────────────── */}
      <div className="flex-1 min-w-0 overflow-y-auto" style={{ maxHeight: "calc(100vh - 100px)" }}>
        {!editMode ? (
          <>
            <div className="flex items-center justify-between mb-3">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">Your Workouts ({workouts.length})</p>
              <button onClick={startNew}
                className="btn btn-tonal btn-sm"><PlusIcon />New Workout</button>
            </div>
            {workouts.length === 0 ? (
              <div className="text-center py-11 text-slate-400 dark:text-slate-500">
                <p className="text-sm mb-2">No saved workouts yet</p>
                <p className="text-xs">Create a workout to get started with automatic progressive overload</p>
              </div>
            ) : (
              <div className="space-y-2">
                {workouts.map(wo => (
                  <div key={wo.id} className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-3.5 hover:border-accent-300 dark:hover:border-accent-700 transition-colors">
                    <div className="flex items-start justify-between mb-2">
                      <div>
                        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                          {wo.name}
                          {wo.include_in_plan !== false && <span className="ml-2 text-[10px] text-accent-600 dark:text-accent-400 font-normal">(in plan)</span>}
                          {wo.sync_to_watch === true && <span className="ml-2 text-[10px] text-sky-600 dark:text-sky-400 font-normal">(watch)</span>}
                        </h3>
                        {wo.description && <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{wo.description}</p>}
                      </div>
                      <div className="flex gap-1">
                        <button onClick={() => startEdit(wo)} className="btn btn-neutral btn-sm">Edit</button>
                        <button onClick={() => deleteWorkout(wo.id)} className="btn btn-danger btn-sm">Delete</button>
                      </div>
                    </div>
                    {(wo.tags || []).length > 0 && (
                      <div className="flex gap-1 mb-2 flex-wrap">
                        {wo.tags.map(t => <span key={t} className="text-[10px] px-1.5 py-0.5 rounded-full bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-400 font-medium">{TAG_LABELS[t] || t}</span>)}
                      </div>
                    )}
                    <div className="text-[11px] text-slate-500 dark:text-slate-400">
                      {(wo.exercises || []).length} exercises
                      {(wo.exercises || []).slice(0, 4).map(e => (
                        <span key={e.exercise_name} className="ml-2 text-slate-400 dark:text-slate-500">{e.exercise_name} {e.target_sets}×{e.target_reps}</span>
                      ))}
                      {(wo.exercises || []).length > 4 && <span className="ml-2">…</span>}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">{editingId ? "Edit Workout" : "New Workout"}</p>
              <div className="flex gap-2">
                <button onClick={() => { setEditMode(false); setEditingId(null); }}
                  className="btn btn-neutral btn-sm">Cancel</button>
                <button onClick={saveWorkout} disabled={saving || !workoutName.trim() || fromEditor(workoutExercises).length === 0}
                  className="btn btn-primary btn-sm">
                  {saving ? "Saving…" : "Save Workout"}
                </button>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1">Workout Name *</label>
                <input type="text" value={workoutName} onChange={e => setWorkoutName(e.target.value)}
                  placeholder="e.g. Upper Body Push"
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40" />
              </div>
              <div>
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1">Description</label>
                <input type="text" value={workoutDesc} onChange={e => setWorkoutDesc(e.target.value)}
                  placeholder="Optional notes…"
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40" />
              </div>
            </div>

            {/* Named for what they do, as on the phone's builder: "include in
                training plans" / "sync to watch" said little to a new user.
                The "?" beside each is outside its label so it does not tick it. */}
            <div className="flex items-center gap-2">
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={includeInPlan} onChange={e => setIncludeInPlan(e.target.checked)}
                className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-accent-600 focus:ring-accent-500" />
              <span className="text-xs text-slate-600 dark:text-slate-400">Schedule in my plan</span>
            </label>
            <InfoTooltip><p>The plan generator may put this workout into your training plan.</p></InfoTooltip>
            </div>

            <div className="flex items-center gap-2">
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={syncToWatch} onChange={e => setSyncToWatch(e.target.checked)}
                className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-sky-600 focus:ring-sky-500" />
              <span className="text-xs text-slate-600 dark:text-slate-400">Save to my watch</span>
            </label>
            <InfoTooltip><p>Saved onto your watch as a workout, to start from there. The watch holds up to 25.</p></InfoTooltip>
            </div>

            <div>
              <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1">Tags</label>
              <div className="flex gap-1.5 flex-wrap">
                {VALID_TAGS.map(tag => (
                  <button key={tag} onClick={() => toggleTag(tag)}
                    className={`text-[11px] px-2.5 py-1 rounded-full font-medium transition-colors ${
                      workoutTags.includes(tag) ? "bg-accent-600 text-white" : "bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600"
                    }`}>{TAG_LABELS[tag]}</button>
                ))}
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <p className="text-xs font-medium text-slate-600 dark:text-slate-400">Structure ({workoutExercises.filter(e => !isMarker(e) && e.item_kind !== "rest").length})</p>
              </div>
              <BlockStructure rows={workoutExercises} setRows={setWorkoutExercises} restField="rest_seconds" noun="exercise"
                renderRow={(ex, inGroup) => (
                  <SortableExercise id={ex._id} ex={ex} inGroup={inGroup}
                    onChange={updateExercise} onRemove={(id) => setWorkoutExercises(prev => removeRow(prev, id))}
                    onViewDetail={viewExerciseDetail} />
                )} />
            </div>
          </div>
        )}
      </div>

      {/* Exercise detail popup */}
      {detailExercise !== undefined && (
        <ExerciseDetailModal
          exercise={detailExercise}
          onClose={() => setDetailExercise(undefined)}
        />
      )}
    </div>
  );
}
