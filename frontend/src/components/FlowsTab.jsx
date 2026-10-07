// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Flows library tab (Flexibility page). Lists the user's saved mobility/stretch
// "flows" (ordered sequences of stretches) with tag filtering, and provides the
// create/edit form — name, tag chips, and a MuscleMapPicker to record which
// muscle groups a flow targets. Opens individual entries in StretchDetailModal.
//
// Any flow can be played here, saved or still in the editor, in the same
// guided player as a planned one. Finishing logs a workout session naming the
// flow — the phone's record of a flow done (FlexibilityViewModel), so both
// clients see the same history.
import { useState, useEffect, useCallback } from "react";
import { api } from "../api/client";
import { MuscleMapPicker } from "./activity/charts/MuscleMap";
import { muscleLabel } from "../utils/muscleGroups";
import StretchDetailModal from "./StretchDetailModal";
import BlockStructure from "./workouts/BlockStructure";
import InfoTooltip from "./ui/InfoTooltip";
import Checkbox from "./ui/Checkbox";
import { remove as removeRow, toEditor, fromEditor, isMarker } from "../lib/blocks";
import { PlusIcon } from "./ui/Button";
import FlowPlayer from "./player/FlowPlayer";
import { planFlow } from "../lib/sessionPlan";

const VALID_TAGS = [
  "upper_body", "lower_body", "full_body", "mobility", "recovery",
  "pre_run", "post_run", "yoga", "balance", "breathing",
];
const TAG_LABELS = {
  upper_body: "Upper Body", lower_body: "Lower Body", full_body: "Full Body",
  mobility: "Mobility", recovery: "Recovery", pre_run: "Pre-Run",
  post_run: "Post-Run", yoga: "Yoga", balance: "Balance", breathing: "Breathing",
};

const MUSCLE_TO_LIBRARY = {
  chest: "chest", front_delts: "shoulders", side_delts: "shoulders",
  rear_delts: "shoulders", biceps: "biceps", triceps: "triceps",
  forearms: "forearms", traps: "upper_back", lats: "lats", mid_back: "upper_back",
  lower_back: "lower_back", abs: "core", obliques: "obliques",
  hip_flexors: "hip_flexors", glutes: "glutes", quads: "quads",
  hamstrings: "hamstrings", calves_front: "calves", calves_back: "calves",
  adductors: "hip_adductors", neck: "neck",
  ankles: "calves", hands: "hands", feet: "feet",
};

function StretchPickRow({ name, primary, secondary, duration, eachSide, equipment, onAdd, onViewDetail }) {
  return (
    <div className="flex items-center w-full text-left rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-accent-300 dark:hover:border-accent-700 transition-colors overflow-hidden">
      <button onClick={onAdd}
        className="shrink-0 px-2.5 py-1.5 text-accent-600 hover:bg-accent-50 dark:hover:bg-accent-900/20 text-sm font-bold transition-colors border-r border-slate-200 dark:border-slate-700">
        +
      </button>
      <button onClick={onViewDetail} className="flex-1 px-2.5 py-1.5 min-w-0">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-800 dark:text-slate-200 truncate hover:text-accent-600 dark:hover:text-accent-400">{name}</span>
          <span className="text-[10px] text-slate-400 shrink-0 ml-2">{duration}s{eachSide ? " each" : ""}</span>
        </div>
        {(primary?.length > 0 || secondary?.length > 0) && (
          <div className="flex gap-1 mt-1 flex-wrap">
            {primary?.map(m => <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">{muscleLabel(m)}</span>)}
            {secondary?.map(m => <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-slate-50 dark:bg-slate-800 text-slate-400">{muscleLabel(m)}</span>)}
          </div>
        )}
      </button>
    </div>
  );
}

let _flowIdCounter = 0;
function nextFlowId() { return `fl_${++_flowIdCounter}`; }

export default function FlowsTab() {
  const [stretches, setStretches] = useState([]);
  const [flows, setFlows] = useState([]);
  const [selectedMuscle, setSelectedMuscle] = useState(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [editMode, setEditMode] = useState(false);
  const [flowName, setFlowName] = useState("");
  const [flowDesc, setFlowDesc] = useState("");
  const [flowTags, setFlowTags] = useState([]);
  const [flowStretches, setFlowStretches] = useState([]);
  const [includeInPlan, setIncludeInPlan] = useState(true);
  const [syncToWatch, setSyncToWatch] = useState(true);
  const [editingId, setEditingId] = useState(null);

  const [detailStretch, setDetailStretch] = useState(undefined);

  // The guided player, when a flow is being done: { name, steps }.
  const [playing, setPlaying] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [stData, flData] = await Promise.all([
        api.getStretches().catch(() => []),
        api.getFlows().catch(() => []),
      ]);
      setStretches(stData || []);
      setFlows(Array.isArray(flData) ? flData : []);
    } catch {}
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const filtered = stretches.filter(s => {
    const q = search.toLowerCase().trim();
    if (q) {
      if ((s.name || "").toLowerCase().includes(q)) return true;
      const muscles = [...(s.primary_muscles || []), ...(s.secondary_muscles || [])];
      return muscles.some(m => m && m.toLowerCase().includes(q));
    }
    if (selectedMuscle) {
      const libKey = MUSCLE_TO_LIBRARY[selectedMuscle] || selectedMuscle;
      return [...(s.primary_muscles || []), ...(s.secondary_muscles || [])].includes(libKey);
    }
    return true;
  });

  const handleMuscleSelect = (key) => setSelectedMuscle(prev => prev === key ? null : key);

  const addStretchToFlow = (s) => {
    setFlowStretches(prev => [...prev, {
      _id: nextFlowId(),
      exercise_name: s.name,
      duration_seconds: s.duration_per_side_sec || 60,
      sets: s.sets || 1,
      rest_seconds: 0,
      order_index: prev.length,
    }]);
  };

  const updateStretch = (id, field, value) => {
    setFlowStretches(prev => prev.map(e => e._id === id ? { ...e, [field]: value } : e));
  };

  const removeStretch = (id) => {
    setFlowStretches(prev => prev.filter(e => e._id !== id));
  };

  const toggleTag = (tag) => {
    setFlowTags(prev => prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag]);
  };

  const startNew = () => {
    setEditMode(true);
    setFlowName(""); setFlowDesc(""); setFlowTags([]);
    setFlowStretches([]); setIncludeInPlan(true); setSyncToWatch(true);
    setEditingId(null);
  };

  const startEdit = (flow) => {
    setEditMode(true);
    setFlowName(flow.name);
    setFlowDesc(flow.description || "");
    setFlowTags(flow.tags || []);
    setIncludeInPlan(flow.include_in_plan !== false);
    setSyncToWatch(flow.sync_to_watch !== false);
    setFlowStretches(toEditor((flow.stretches || []).map(s => ({
      _id: nextFlowId(),
      exercise_name: s.exercise_name,
      duration_seconds: s.duration_seconds || 60,
      sets: s.sets || 1,
      rest_seconds: s.rest_seconds || 0,
      order_index: s.order_index,
      item_kind: s.item_kind, group_uid: s.group_uid, group_kind: s.group_kind,
      group_rounds: s.group_rounds, group_rest_seconds: s.group_rest_seconds,
    }))));
    setEditingId(flow.id);
  };

  const saveFlow = async () => {
    if (!flowName.trim() || fromEditor(flowStretches).length === 0) return;
    setSaving(true);
    try {
      const body = {
        name: flowName.trim(), description: flowDesc || null,
        tags: flowTags, include_in_plan: includeInPlan,
        sync_to_watch: syncToWatch,
        stretches: fromEditor(flowStretches).map(({ _id, ...s }) => s),
      };
      if (editingId) await api.updateFlow(editingId, body);
      else await api.createFlow(body);
      await load();
      setEditMode(false); setEditingId(null);
    } catch (err) { alert(err.message || "Failed to save flow"); }
    setSaving(false);
  };

  const startFlow = (name, rows) => {
    const steps = planFlow(rows, Object.fromEntries(stretches.map(s => [s.name, s])));
    if (steps.length === 0) return;
    setPlaying({ name, steps });
  };

  // No exercises on the log: a stretch has no weight or reps, and the session
  // endpoint's progression machinery is for lifts. The name is the record.
  const logFlow = (rpe) => api.logWorkoutSession({ session_rpe: rpe, notes: `Mobility: ${playing.name}` });

  const deleteFlow = async (id) => {
    if (!confirm("Delete this flow?")) return;
    try { await api.deleteFlow(id); await load(); } catch {}
  };

  if (loading) return (
    <div className="flex justify-center py-11">
      <div className="spinner" />
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
              className="field field-sm flex-1" />
          </div>
          <div className="space-y-1">
            {filtered.slice(0, 60).map(s => (
              <StretchPickRow key={s.name}
                name={s.name} primary={s.primary_muscles} secondary={s.secondary_muscles}
                duration={s.duration_per_side_sec} eachSide={s.each_side}
                equipment={s.equipment}
                onAdd={() => addStretchToFlow(s)}
                onViewDetail={() => setDetailStretch(s)} />
            ))}
            {stretches.length > 0 && filtered.length === 0 && (
              <p className="text-xs text-slate-400 italic text-center py-3.5">No stretches match your search</p>
            )}
          </div>
        </div>
      </div>

      {/* ── RIGHT ─────────────────────────────────────────────────────────── */}
      <div className="flex-1 min-w-0 overflow-y-auto" style={{ maxHeight: "calc(100vh - 100px)" }}>
        {!editMode ? (
          <>
            <div className="flex items-center justify-between mb-3">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">Your Flows ({flows.length})</p>
              <button onClick={startNew}
                className="btn btn-primary btn-sm"><PlusIcon />New Flow</button>
            </div>
            {flows.length === 0 ? (
              <div className="text-center py-11 text-slate-400 dark:text-slate-500">
                <p className="text-sm mb-2">No saved flows yet</p>
                <p className="text-xs">Create a flexibility flow to add to your training plan or sync to your watch</p>
              </div>
            ) : (
              <div className="space-y-2">
                {flows.map(flow => (
                  <div key={flow.id} className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-3.5 hover:border-accent-300 dark:hover:border-accent-700 transition-colors">
                    <div className="flex items-start justify-between mb-2">
                      <div>
                        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                          {flow.name}
                          {flow.include_in_plan !== false && <span className="ml-2 text-[10px] text-accent-600 dark:text-accent-400 font-normal">(in plan)</span>}
                          {flow.sync_to_watch !== false && <span className="ml-2 text-[10px] text-sky-600 dark:text-sky-400 font-normal">(watch)</span>}
                        </h3>
                        {flow.description && <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{flow.description}</p>}
                      </div>
                      <div className="flex gap-1">
                        <button onClick={() => startFlow(flow.name, flow.stretches)} className="btn btn-primary btn-sm">Start</button>
                        <button onClick={() => startEdit(flow)} className="btn btn-neutral btn-sm">Edit</button>
                        <button onClick={() => deleteFlow(flow.id)} className="btn btn-danger btn-sm">Delete</button>
                      </div>
                    </div>
                    {(flow.tags || []).length > 0 && (
                      <div className="flex gap-1 mb-2 flex-wrap">
                        {flow.tags.map(t => <span key={t} className="badge bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-400">{TAG_LABELS[t] || t}</span>)}
                      </div>
                    )}
                    <div className="text-[11px] text-slate-500 dark:text-slate-400">
                      {(flow.stretches || []).length} stretches
                      {(flow.stretches || []).slice(0, 4).map(s => (
                        <span key={s.exercise_name} className="ml-2 text-slate-400 dark:text-slate-500">{s.exercise_name} {s.duration_seconds || 60}s</span>
                      ))}
                      {(flow.stretches || []).length > 4 && <span className="ml-2">…</span>}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">{editingId ? "Edit Flow" : "New Flow"}</p>
              <div className="flex gap-2">
                <button onClick={() => { setEditMode(false); setEditingId(null); }}
                  className="btn btn-neutral btn-sm">Cancel</button>
                {/* Play what is in the editor without saving it, as the phone
                    plays a picked set of stretches as a "Quick flow". */}
                <button onClick={() => startFlow(flowName.trim() || "Quick flow", fromEditor(flowStretches))}
                  disabled={fromEditor(flowStretches).filter(s => s.item_kind !== "rest").length === 0}
                  className="btn btn-tonal btn-sm">Start now</button>
                <button onClick={saveFlow} disabled={saving || !flowName.trim() || fromEditor(flowStretches).length === 0}
                  className="btn btn-primary btn-sm">
                  {saving ? "Saving…" : "Save Flow"}
                </button>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="field-label">Flow Name *</label>
                <input type="text" value={flowName} onChange={e => setFlowName(e.target.value)}
                  placeholder="e.g. Post-Run Recovery"
                  className="field" />
              </div>
              <div>
                <label className="field-label">Description</label>
                <input type="text" value={flowDesc} onChange={e => setFlowDesc(e.target.value)}
                  placeholder="Optional notes…"
                  className="field" />
              </div>
            </div>

            {/* Named for what they do, as on the phone's builder: "include in
                training plans" / "sync to watch" said little to a new user.
                The "?" beside each is outside its label so it does not tick it. */}
            <div className="flex items-center gap-2">
            <Checkbox checked={includeInPlan} onChange={setIncludeInPlan}>Schedule in my plan</Checkbox>
            <InfoTooltip><p>The plan generator may put this flow into your training plan.</p></InfoTooltip>
            </div>

            <div className="flex items-center gap-2">
            <Checkbox checked={syncToWatch} onChange={setSyncToWatch}>Save to my watch</Checkbox>
            <InfoTooltip><p>Saved onto your watch as a workout, to start from there. The watch holds up to 25.</p></InfoTooltip>
            </div>

            <div>
              <label className="field-label">Tags</label>
              <div className="flex gap-1.5 flex-wrap">
                {VALID_TAGS.map(tag => (
                  <button key={tag} type="button" aria-pressed={flowTags.includes(tag)}
                    onClick={() => toggleTag(tag)} className="chip chip-sm">{TAG_LABELS[tag]}</button>
                ))}
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <p className="text-xs font-medium text-slate-600 dark:text-slate-400">Structure ({flowStretches.filter(s => !isMarker(s) && s.item_kind !== "rest").length})</p>
              </div>
                <BlockStructure rows={flowStretches} setRows={setFlowStretches} restField="duration_seconds" restDefault={30} noun="stretch"
                  renderRow={(s) => (
                    <div className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-1.5 hover:border-accent-300 dark:hover:border-accent-700 transition-colors">
                      <div className="flex items-center justify-between mb-1.5">
                        <span className="text-xs font-semibold text-slate-800 dark:text-slate-200">{s.exercise_name}</span>
                        <span className="flex items-center gap-2">
                          <button onClick={() => setFlowStretches(prev => removeRow(prev, s._id))} className="text-sm text-red-500 hover:text-red-700 shrink-0 ml-2 p-1 leading-none">&#x2715;</button>
                        </span>
                      </div>
                      <div className="grid grid-cols-3 gap-1.5 text-[10px]">
                        <div>
                          <label className="text-slate-400 block">Seconds</label>
                          <input type="number" min={5} max={600} value={s.duration_seconds || 60}
                            onChange={e => updateStretch(s._id, "duration_seconds", parseInt(e.target.value) || 60)}
                            className="field field-sm" />
                        </div>
                        <div className={s.group_uid ? "invisible" : ""}>
                          <label className="text-slate-400 block">Sets</label>
                          <input type="number" min={1} max={10} value={s.sets || 1}
                            onChange={e => updateStretch(s._id, "sets", parseInt(e.target.value) || 1)}
                            className="field field-sm" />
                        </div>
                        <div>
                          <label className="text-slate-400 block">Rest</label>
                          <input type="number" min={0} max={120} value={s.rest_seconds || 0}
                            onChange={e => updateStretch(s._id, "rest_seconds", parseInt(e.target.value) || 0)}
                            className="field field-sm" />
                        </div>
                      </div>
                    </div>
                  )} />
            </div>
          </div>
        )}
      </div>

      {playing && (
        <FlowPlayer
          title={playing.name}
          steps={playing.steps}
          onClose={() => setPlaying(null)}
          onLog={logFlow}
        />
      )}

      {detailStretch !== undefined && (
        <StretchDetailModal
          stretch={detailStretch}
          onClose={() => setDetailStretch(undefined)}
        />
      )}
    </div>
  );
}
