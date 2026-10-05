// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Create/edit modal for a custom stretch. Lets the user name a stretch, pick its
// pattern (static/dynamic/yoga/… from PATTERNS below), tag muscle groups, and
// attach a demo animation via GarminAnimationPicker, then POSTs it to the
// stretch library. Opened from the Stretches tab.
import { useState } from "react";
import { api } from "../api/client";
import GarminAnimationPicker from "./GarminAnimationPicker";
import { muscleLabel } from "../utils/muscleGroups";

const PATTERNS = [
  { k: "static_stretch",     l: "Static Stretch" },
  { k: "dynamic_stretch",    l: "Dynamic Stretch" },
  { k: "yoga_pose",          l: "Yoga Pose" },
  { k: "balance_pose",       l: "Balance Pose" },
  { k: "pnf_stretch",        l: "PNF Stretch" },
  { k: "myofascial_release", l: "Myofascial Release" },
];

const EQUIPMENT = ["bodyweight", "strap", "foam_roller", "block", "mat", "wall", "band"];

const MUSCLES = [
  "chest", "front_delts", "side_delts", "rear_delts", "biceps", "triceps", "forearms",
  "traps", "lats", "mid_back", "upper_back", "lower_back", "abs", "obliques",
  "hip_flexors", "glutes", "quads", "hamstrings", "calves_front", "calves_back",
  "adductors", "neck", "feet", "hands",
];


export default function CustomStretchModal({ stretch, onClose, onSaved }) {
  const isEdit = !!stretch;
  const [name, setName] = useState(stretch?.name || "");
  const [primary, setPrimary] = useState(stretch?.primary_muscles || []);
  const [secondary, setSecondary] = useState(stretch?.secondary_muscles || []);
  const [equipment, setEquipment] = useState(stretch?.equipment || ["bodyweight"]);
  const [pattern, setPattern] = useState(stretch?.movement_pattern || "static_stretch");
  const [difficulty, setDifficulty] = useState(stretch?.difficulty || 1);
  const [description, setDescription] = useState(stretch?.description || "");
  const [instructions, setInstructions] = useState(stretch?.instructions || "");
  const [cautions, setCautions] = useState(stretch?.cautions || "");
  const [duration, setDuration] = useState(stretch?.duration_per_side_sec || 60);
  const [eachSide, setEachSide] = useState(stretch?.each_side || false);
  const [defaultSets, setDefaultSets] = useState(stretch?.sets || 1);
  const [garminPair, setGarminPair] = useState(
    stretch?.garmin_category && stretch?.garmin_subtype != null
      ? { category: stretch.garmin_category, subtype: stretch.garmin_subtype }
      : null
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const toggleMuscle = (muscle, isPrimary) => {
    if (isPrimary) {
      setPrimary(prev => prev.includes(muscle) ? prev.filter(m => m !== muscle) : [...prev, muscle]);
      setSecondary(prev => prev.filter(m => m !== muscle));
    } else {
      setSecondary(prev => prev.includes(muscle) ? prev.filter(m => m !== muscle) : [...prev, muscle]);
      setPrimary(prev => prev.filter(m => m !== muscle));
    }
  };

  const toggleEquipment = (eq) => {
    setEquipment(prev => prev.includes(eq) ? prev.filter(e => e !== eq) : [...prev, eq]);
  };

  const handleSave = async () => {
    if (!name.trim()) { setError("Name is required"); return; }
    setSaving(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        primary_muscles: primary,
        secondary_muscles: secondary,
        equipment,
        movement_pattern: pattern,
        difficulty,
        description: description.trim() || null,
        instructions: instructions.trim() || null,
        cautions: cautions.trim() || null,
        duration_per_side_sec: Number(duration),
        each_side: eachSide,
        sets: Number(defaultSets),
        garmin_category: garminPair?.category ?? null,
        garmin_subtype:  garminPair?.subtype  ?? null,
      };
      if (isEdit) await api.updateCustomStretch(stretch.custom_id, body);
      else await api.createCustomStretch(body);
      onSaved();
    } catch { setError("Failed to save"); }
    finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-lg max-w-xl w-full max-h-[85vh] overflow-y-auto m-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-3.5 border-b border-slate-200 dark:border-slate-700">
          <h3 className="text-sm font-semibold text-slate-900 dark:text-white">{isEdit ? "Edit Custom Stretch" : "New Custom Stretch"}</h3>
          <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400">&times;</button>
        </div>
        <div className="p-3.5 space-y-4">
          {error && <div className="text-xs text-red-500">{error}</div>}
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Name *</label>
            <input value={name} onChange={e => setName(e.target.value)} className="w-full px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Movement Pattern</label>
            <div className="flex gap-1 flex-wrap">
              {PATTERNS.map(({ k, l }) => (
                <button key={k} onClick={() => setPattern(k)}
                  className={`text-[10px] px-1.5 py-1 rounded-full font-medium transition-colors ${
                    pattern === k ? "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300" : "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                  }`}>{l}</button>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Hold duration (s)</label>
              <input type="number" min={5} max={600} value={duration} onChange={e => setDuration(parseInt(e.target.value) || 60)}
                className="w-full px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm" />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Sets</label>
              <input type="number" min={1} max={10} value={defaultSets} onChange={e => setDefaultSets(parseInt(e.target.value) || 1)}
                className="w-full px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm" />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Difficulty</label>
              <div className="flex gap-1">
                {[1, 2, 3].map(d => (
                  <button key={d} onClick={() => setDifficulty(d)}
                    className={`flex-1 py-1 rounded text-xs font-medium ${difficulty >= d ? "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300" : "bg-slate-100 dark:bg-slate-800 text-slate-400"}`}>
                    {d}
                  </button>
                ))}
              </div>
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
            <input type="checkbox" checked={eachSide} onChange={e => setEachSide(e.target.checked)} className="rounded" />
            Hold each side
          </label>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Primary Muscles</label>
            <div className="flex gap-1 flex-wrap">
              {MUSCLES.map(m => (
                <button key={m} onClick={() => toggleMuscle(m, true)}
                  className={`text-[10px] px-1 py-0.5 rounded font-medium ${
                    primary.includes(m) ? "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300" : "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                  }`}>{muscleLabel(m)}</button>
              ))}
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Secondary Muscles</label>
            <div className="flex gap-1 flex-wrap">
              {MUSCLES.map(m => (
                <button key={m} onClick={() => toggleMuscle(m, false)}
                  className={`text-[10px] px-1 py-0.5 rounded font-medium ${
                    secondary.includes(m) ? "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300" : "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                  }`}>{muscleLabel(m)}</button>
              ))}
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Equipment</label>
            <div className="flex gap-1 flex-wrap">
              {EQUIPMENT.map(eq => (
                <button key={eq} onClick={() => toggleEquipment(eq)}
                  className={`text-[10px] px-1 py-0.5 rounded font-medium ${
                    equipment.includes(eq) ? "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300" : "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                  }`}>{eq.replace(/_/g, " ")}</button>
              ))}
            </div>
          </div>
          <GarminAnimationPicker
            app="yoga"
            value={garminPair}
            onChange={setGarminPair}
          />
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Description</label>
            <textarea value={description} onChange={e => setDescription(e.target.value)} rows={2}
              className="w-full px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Instructions</label>
            <textarea value={instructions} onChange={e => setInstructions(e.target.value)} rows={4}
              placeholder="Step-by-step instructions (one step per line)" className="w-full px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Cautions</label>
            <textarea value={cautions} onChange={e => setCautions(e.target.value)} rows={2}
              className="w-full px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm" />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 p-3.5 border-t border-slate-200 dark:border-slate-700">
          <button onClick={onClose} className="btn btn-neutral btn-sm">Cancel</button>
          <button onClick={handleSave} disabled={saving} className="btn btn-primary btn-sm">
            {saving ? "Saving..." : isEdit ? "Save Changes" : "Create Stretch"}
          </button>
        </div>
      </div>
    </div>
  );
}
