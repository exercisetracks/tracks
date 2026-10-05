// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * CustomExerciseModal — create or edit a user-defined exercise.
 *
 * Props:
 *   exercise  — null for create, or an existing custom exercise object to edit
 *   onClose   — called when the modal is dismissed
 *   onSaved   — called after a successful save (triggers parent list reload)
 */
import { useEffect, useState } from "react";
import { api } from "../api/client";
import GarminAnimationPicker from "./GarminAnimationPicker";

const ALL_MUSCLES = [
  "chest", "front_delts", "side_delts", "rear_delts", "biceps", "triceps",
  "forearms", "traps", "lats", "mid_back", "upper_back", "lower_back",
  "core", "abs", "obliques", "hip_flexors", "glutes", "quads",
  "hamstrings", "calves", "hip_abductors", "hip_external_rotators",
];

const ALL_EQUIPMENT = [
  "bodyweight", "dumbbell", "barbell", "cable", "machine", "kettlebell", "band", "pullup_bar",
];

const ALL_PATTERNS = [
  "push", "pull", "hinge", "squat", "carry", "rotation", "isometric", "plyometric", "isolation",
];

function MuscleSelector({ label, selected, onChange }) {
  const toggle = (m) => {
    const next = selected.includes(m)
      ? selected.filter(x => x !== m)
      : [...selected, m];
    onChange(next);
  };
  return (
    <div>
      <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">{label}</label>
      <div className="flex flex-wrap gap-1">
        {ALL_MUSCLES.map(m => (
          <button key={m} type="button" onClick={() => toggle(m)}
            className={`text-[11px] px-1.5 py-0.5 rounded-full border transition-colors ${
              selected.includes(m)
                ? "bg-accent-500 text-white border-accent-500"
                : "bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-accent-400"
            }`}>
            {m.replace(/_/g, " ")}
          </button>
        ))}
      </div>
    </div>
  );
}

function EquipmentSelector({ selected, onChange }) {
  const toggle = (e) => {
    const next = selected.includes(e)
      ? selected.filter(x => x !== e)
      : [...selected, e];
    onChange(next.length ? next : ["bodyweight"]);
  };
  return (
    <div>
      <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">Equipment needed</label>
      <div className="flex flex-wrap gap-1">
        {ALL_EQUIPMENT.map(e => (
          <button key={e} type="button" onClick={() => toggle(e)}
            className={`text-[11px] px-1.5 py-0.5 rounded-full border transition-colors ${
              selected.includes(e)
                ? "bg-accent-500 text-white border-accent-500"
                : "bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-accent-400"
            }`}>
            {e}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function CustomExerciseModal({ exercise, onClose, onSaved }) {
  const isEdit = !!exercise?.custom_id;

  const [name, setName]           = useState("");
  const [primaryMuscles, setPrimary]   = useState([]);
  const [secondaryMuscles, setSecondary] = useState([]);
  const [equipment, setEquipment] = useState(["bodyweight"]);
  const [pattern, setPattern]     = useState("");
  const [isCompound, setIsCompound] = useState(true);
  const [difficulty, setDifficulty] = useState(1);
  const [description, setDescription] = useState("");
  const [defaultSets, setDefaultSets] = useState(3);
  const [defaultReps, setDefaultReps] = useState(10);
  const [instructions, setInstructions] = useState("");
  const [garminPair, setGarminPair] = useState(null);  // { category, subtype } | null
  const [saving, setSaving]       = useState(false);
  const [error, setError]         = useState(null);

  useEffect(() => {
    if (exercise) {
      setName(exercise.name || "");
      setPrimary(exercise.primary_muscles || []);
      setSecondary(exercise.secondary_muscles || []);
      setEquipment(exercise.equipment || ["bodyweight"]);
      setPattern(exercise.movement_pattern || "");
      setIsCompound(exercise.is_compound ?? true);
      setDifficulty(exercise.difficulty || 1);
      setDescription(exercise.description || "");
      setInstructions(exercise.instructions || "");
      setDefaultSets(exercise.default_sets || 3);
      setDefaultReps(exercise.default_reps || 10);
      setGarminPair(
        exercise.garmin_category && exercise.garmin_subtype != null
          ? { category: exercise.garmin_category, subtype: exercise.garmin_subtype }
          : null
      );
    }
  }, [exercise]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!name.trim()) { setError("Name is required"); return; }
    if (primaryMuscles.length === 0) { setError("Select at least one primary muscle"); return; }
    if (equipment.length === 0) { setError("Select at least one equipment option"); return; }

    setSaving(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        primary_muscles: primaryMuscles,
        secondary_muscles: secondaryMuscles,
        equipment,
        movement_pattern: pattern || null,
        is_compound: isCompound,
        difficulty,
        description: description.trim() || null,
        instructions: instructions.trim() || null,
        default_sets: Number(defaultSets),
        default_reps: Number(defaultReps),
        garmin_category: garminPair?.category ?? null,
        garmin_subtype:  garminPair?.subtype  ?? null,
      };
      if (isEdit) {
        await api.updateCustomExercise(exercise.custom_id, body);
      } else {
        await api.createCustomExercise(body);
      }
      onSaved();
      onClose();
    } catch (err) {
      setError(err?.message || "Failed to save exercise");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-3.5">
      <div className="bg-white dark:bg-slate-900 rounded-xl shadow-lg w-full max-w-xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-4 pt-4 pb-2.5 border-b border-slate-100 dark:border-slate-800">
          <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">
            {isEdit ? "Edit custom exercise" : "New custom exercise"}
          </h2>
          <button onClick={onClose}
            className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-xl leading-none">×</button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto px-4 py-3.5 space-y-4">
          {/* Name */}
          <div>
            <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1">
              Exercise name <span className="text-red-400">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. Banded Clamshell"
              className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
              disabled={saving}
            />
          </div>

          {/* Movement pattern + compound toggle */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">
                Movement pattern
              </label>
              <select
                value={pattern}
                onChange={e => setPattern(e.target.value)}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
                disabled={saving}>
                <option value="">— select —</option>
                {ALL_PATTERNS.map(p => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
            <div className="space-y-2">
              <div>
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">
                  Difficulty
                </label>
                <select
                  value={difficulty}
                  onChange={e => setDifficulty(Number(e.target.value))}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
                  disabled={saving}>
                  <option value={1}>1 — Beginner</option>
                  <option value={2}>2 — Intermediate</option>
                  <option value={3}>3 — Advanced</option>
                </select>
              </div>
            </div>
          </div>

          {/* Default sets/reps */}
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">
                Default sets
              </label>
              <input type="number" min={1} max={10} value={defaultSets}
                onChange={e => setDefaultSets(e.target.value)}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
                disabled={saving} />
            </div>
            <div>
              <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">
                Default reps
              </label>
              <input type="number" min={1} max={100} value={defaultReps}
                onChange={e => setDefaultReps(e.target.value)}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
                disabled={saving} />
            </div>
            <div className="flex items-end pb-1.5">
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="checkbox" checked={isCompound} onChange={e => setIsCompound(e.target.checked)}
                  className="rounded text-accent-500 focus:ring-accent-500" disabled={saving} />
                <span className="text-xs text-slate-600 dark:text-slate-400">Compound</span>
              </label>
            </div>
          </div>

          <MuscleSelector
            label="Primary muscles *"
            selected={primaryMuscles}
            onChange={setPrimary}
          />
          <MuscleSelector
            label="Secondary muscles"
            selected={secondaryMuscles}
            onChange={setSecondary}
          />
          <EquipmentSelector selected={equipment} onChange={setEquipment} />

          <GarminAnimationPicker
            app="strength"
            value={garminPair}
            onChange={setGarminPair}
          />

          {/* Notes */}
          <div>
            <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1.5">
              Notes / coaching cue
            </label>
            <textarea
              rows={2}
              value={description}
              onChange={e => setDescription(e.target.value)}
              placeholder="e.g. Best loaded with a dumbbell for stability…"
              className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40 resize-none"
              disabled={saving}
            />
          </div>

          {/* Instructions */}
          <div>
            <label className="text-xs font-medium text-slate-600 dark:text-slate-400 block mb-1">
              Step-by-step instructions
            </label>
            <p className="text-[11px] text-slate-400 dark:text-slate-500 mb-1.5">
              Each line is one step. Start with "1." "2." etc. End with "Tip: …" for a highlighted tip.
            </p>
            <textarea
              rows={5}
              value={instructions}
              onChange={e => setInstructions(e.target.value)}
              placeholder={"1. Starting position…\n2. Movement…\n3. Return…\nTip: Key coaching cue."}
              className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40 resize-none font-mono"
              disabled={saving}
            />
          </div>

          {error && (
            <p className="text-sm text-red-500 dark:text-red-400">{error}</p>
          )}
        </form>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-4 py-2.5 border-t border-slate-100 dark:border-slate-800">
          <button type="button" onClick={onClose} disabled={saving}
            className="btn btn-neutral">
            Cancel
          </button>
          <button type="submit" form="" onClick={handleSubmit} disabled={saving}
            className="btn btn-primary">
            {saving ? "Saving…" : isEdit ? "Save changes" : "Create exercise"}
          </button>
        </div>
      </div>
    </div>
  );
}
