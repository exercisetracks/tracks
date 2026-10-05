// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Stretches library tab (Flexibility page). Lists individual stretches with
// tag/muscle filtering and the create/edit form (name, tags, a MuscleMapPicker,
// and an optional demo animation). Opens entries in StretchDetailModal; the
// MUSCLE_TO_LIBRARY map below bridges muscle-picker group ids to library tags.
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import { MuscleMapPicker } from "./activity/charts/MuscleMap";
import { muscleLabel } from "../utils/muscleGroups";
import StretchDetailModal from "./StretchDetailModal";
import AnimationBadge from "./AnimationBadge";
import AnimationConfirmToggle from "./AnimationConfirmToggle";
import { PlusIcon } from "./ui/Button";

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

function stretchMatchesMuscle(stretch, muscle) {
  if (!muscle) return true;
  const libKey = MUSCLE_TO_LIBRARY[muscle] || muscle;
  return (
    (stretch.primary_muscles || []).includes(libKey) ||
    (stretch.secondary_muscles || []).includes(libKey)
  );
}

function PrefToggle({ pref, onChange, saving }) {
  return (
    <div className={`flex rounded-lg border overflow-hidden text-[11px] font-medium transition-opacity ${saving ? "opacity-50 pointer-events-none" : ""}`}>
      <button
        onClick={() => onChange(pref === "preferred" ? null : "preferred")}
        className={`px-1.5 py-1 transition-colors ${pref === "preferred"
          ? "bg-accent-500 text-white border-accent-500"
          : "bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-accent-50 dark:hover:bg-accent-900/20 border-slate-200 dark:border-slate-700"}`}
        title="Prefer — algorithm prioritises"
      >&#x2713;</button>
      <button
        onClick={() => onChange(pref === "excluded" ? null : "excluded")}
        className={`px-1.5 py-1 transition-colors ${pref === "excluded"
          ? "bg-red-500 text-white border-red-500"
          : "bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-red-50 dark:hover:bg-red-900/20 border-slate-200 dark:border-slate-700"}`}
        title="Exclude — never auto-scheduled"
      >&#x2715;</button>
    </div>
  );
}

const PAT_COLOR = {
  static_stretch:     "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  dynamic_stretch:    "bg-accent-100 text-accent-700 dark:bg-accent-900/30 dark:text-accent-300",
  yoga_pose:          "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300",
  balance_pose:       "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
  pnf_stretch:        "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
  myofascial_release: "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300",
};

function StretchRow({ stretch, onPrefChange, onEdit, onDelete, onViewDetail,
                       primaryDeviceProductId, onConfirmChange }) {
  const [saving, setSaving] = useState(false);

  const handlePref = async (newPref) => {
    setSaving(true);
    await onPrefChange(stretch.name, newPref);
    setSaving(false);
  };

  const patColor = PAT_COLOR[stretch.movement_pattern] || "bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400";
  const rowBg = stretch.preference === "preferred"
    ? "bg-accent-50/50 dark:bg-accent-900/10 border-accent-200 dark:border-accent-800"
    : stretch.preference === "excluded"
      ? "bg-red-50/40 dark:bg-red-900/10 border-red-200 dark:border-red-800 opacity-60"
      : "bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700";

  return (
    <div className={`rounded-xl border px-2.5 py-2 flex items-start gap-2 ${rowBg} cursor-pointer`}
      onClick={onViewDetail}>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-sm font-medium text-slate-800 dark:text-slate-200 leading-snug hover:text-accent-600 dark:hover:text-accent-400 transition-colors">
            {stretch.name}
          </span>
          {stretch._is_custom && (
            <span className="text-[10px] px-1 py-0.5 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300">
              custom
            </span>
          )}
          <AnimationBadge
            state={stretch.animation_state}
            hasAnimation={stretch.has_animation}
            show={stretch.animation_state !== undefined || stretch.has_animation !== undefined}
          />
          <AnimationConfirmToggle
            category={stretch.garmin_category}
            subtype={stretch.garmin_subtype}
            state={stretch.animation_state}
            disabled={!primaryDeviceProductId}
            onChange={(s) => onConfirmChange(stretch.name, s)}
          />
        </div>
        <div className="flex flex-wrap gap-1 mt-1">
          {stretch.movement_pattern && (
            <span className={`text-[10px] px-1 py-0.5 rounded-full ${patColor}`}>
              {stretch.movement_pattern.replace(/_/g, " ")}
            </span>
          )}
          {(stretch.primary_muscles || []).slice(0, 3).map(m => (
            <span key={m} className="text-[10px] px-1 py-0.5 rounded-full bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
              {muscleLabel(m)}
            </span>
          ))}
        </div>
        {stretch.description && (
          <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-1 line-clamp-1">
            {stretch.description}
          </p>
        )}
      </div>
      <div className="flex items-center gap-1.5 shrink-0 mt-0.5" onClick={e => e.stopPropagation()}>
        {stretch._is_custom && (
          <>
            <button onClick={() => onEdit(stretch)}
              className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-sm p-1"
              title="Edit">&#9998;</button>
            <button onClick={() => onDelete(stretch)}
              className="text-slate-300 hover:text-red-400 text-sm p-1"
              title="Delete">&#128465;</button>
          </>
        )}
        <PrefToggle pref={stretch.preference} onChange={handlePref} saving={saving} />
      </div>
    </div>
  );
}

export default function StretchesTab({ onOpenCustomModal }) {
  const [stretches, setStretches] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [selectedMuscle, setSelectedMuscle] = useState(null);
  const [filter, setFilter] = useState("all");
  const [detailStretch, setDetailStretch] = useState(null);

  const [primaryDevice, setPrimaryDevice] = useState(null);

  const load = useCallback(async () => {
    try {
      const [data, devices] = await Promise.all([
        api.getStretches(),
        api.getGarminDevices().catch(() => []),
      ]);
      setStretches(data || []);
      const pri = (devices || []).find(d => d.is_primary);
      setPrimaryDevice(pri || null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleConfirmChange = (name, newState) => {
    setStretches(prev => prev.map(s =>
      s.name === name ? { ...s, animation_state: newState } : s
    ));
  };

  const handleMuscleSelect = (muscle) => {
    setSelectedMuscle(prev => prev === muscle ? null : muscle);
  };

  const handlePrefChange = async (name, pref) => {
    if (pref) {
      await api.setStretchPreference(name, pref);
    } else {
      await api.deleteStretchPreference(name);
    }
    setStretches(prev => prev.map(s =>
      s.name === name ? { ...s, preference: pref ?? null } : s
    ));
  };

  const handleDelete = async (stretch) => {
    if (!confirm(`Delete custom stretch "${stretch.name}"?`)) return;
    await api.deleteCustomStretch(stretch.custom_id);
    setStretches(prev => prev.filter(s => s.name !== stretch.name));
  };

  const visible = useMemo(() => {
    let list = stretches;
    if (selectedMuscle) list = list.filter(s => stretchMatchesMuscle(s, selectedMuscle));
    if (search.trim())  list = list.filter(s => s.name.toLowerCase().includes(search.toLowerCase()));
    if (filter === "preferred") list = list.filter(s => s.preference === "preferred");
    if (filter === "excluded")  list = list.filter(s => s.preference === "excluded");
    if (filter === "custom")    list = list.filter(s => s._is_custom);
    return list;
  }, [stretches, selectedMuscle, search, filter]);

  const preferredCount = stretches.filter(s => s.preference === "preferred").length;
  const excludedCount  = stretches.filter(s => s.preference === "excluded").length;

  if (loading) return (
    <div className="flex justify-center py-11">
      <div className="w-5 h-5 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
    </div>
  );

  return (
    <>
    <div className="flex gap-4 min-h-0" style={{ minHeight: 0 }}>
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

      <div className="flex-1 min-w-0 flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <input
            type="search"
            placeholder="Search stretches…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="flex-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2.5 py-1.5 text-sm text-slate-800 dark:text-slate-200 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-accent-500/40"
          />
          <button
            onClick={() => onOpenCustomModal(null, load)}
            className="btn btn-primary shrink-0">
            <PlusIcon />Custom
          </button>
        </div>

        <div className="flex gap-1.5 flex-wrap">
          {[
            { key: "all",       label: `All (${stretches.length})` },
            { key: "preferred", label: `Preferred (${preferredCount})` },
            { key: "excluded",  label: `Excluded (${excludedCount})` },
            { key: "custom",    label: "Custom" },
          ].map(f => (
            <button key={f.key}
              onClick={() => setFilter(f.key)}
              className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                filter === f.key
                  ? "bg-accent-600 text-white"
                  : "bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600"
              }`}>
              {f.label}
            </button>
          ))}
        </div>

        <div className="flex gap-3 text-[10px] text-slate-400 dark:text-slate-500">
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-sm bg-accent-500" /> Preferred — algorithm prioritises
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-sm bg-red-400" /> Excluded — never scheduled
          </span>
        </div>

        <div className="flex-1 overflow-y-auto space-y-1.5 pr-1" style={{ maxHeight: "calc(100vh - 210px)" }}>
          {visible.length === 0 && (
            <div className="text-center py-7 text-slate-400 dark:text-slate-500 text-sm italic">
              {search || selectedMuscle ? "No stretches match the current filter." : "No stretches found."}
            </div>
          )}
          {visible.map(stretch => (
            <StretchRow
              key={stretch.name}
              stretch={stretch}
              onPrefChange={handlePrefChange}
              onEdit={(s) => onOpenCustomModal(s, load)}
              onDelete={handleDelete}
              onViewDetail={() => setDetailStretch(stretch)}
              primaryDeviceProductId={primaryDevice?.product_id}
              onConfirmChange={handleConfirmChange}
            />
          ))}
        </div>
      </div>
    </div>
    {detailStretch && (
      <StretchDetailModal
        stretch={detailStretch}
        onClose={() => setDetailStretch(null)}
      />
    )}
    </>
  );
}
