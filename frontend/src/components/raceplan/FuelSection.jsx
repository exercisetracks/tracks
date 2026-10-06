// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Race fuelling: the per-hour targets in force, the products to carry, the
// what-to-take-when timeline, and one line of gut training. All numbers come
// from the server's calculators/fuel_plan.py (/fuel/goals/{id}/plan); the
// phone computes the same plan itself from synced rows and lays it out the
// same way (mobile/.../ui/race/FuelSection.kt).
//
// Deliberately no helper text: the section had grown a sentence under every
// part, and the numbers explain themselves once they sit in one place. The
// target boxes live behind the pencil because they are set once per race,
// not read every time.
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import AnchoredPopover from "../ui/AnchoredPopover";
import Button, { PlusIcon } from "../ui/Button";

const INPUT = "field";
const KINDS = ["gel", "drink", "chew", "bar", "other"];
const PANEL = "w-72 p-4 space-y-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg";
const TARGET_FIELDS = [
  ["fuel_carbs_per_hour", "Carbs", "g/h", "carbs_g_per_h"],
  ["fuel_fluid_ml_per_hour", "Fluid", "ml/h", "fluid_ml_per_h"],
  ["fuel_sodium_mg_per_hour", "Sodium", "mg/h", "sodium_mg_per_h"],
  ["fuel_interval_min", "Every", "min", "interval_min"],
];

/** Race clock, h:mm: one width for every row, so the column lines up. */
export function clock(m) {
  return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}`;
}

/** What to take at one stop: the product (or plain grams), plus its fluid when it carries any. */
export function stopLabel(i) {
  const what = i.name ?? `${i.carbs_g} g carbs`;
  return i.fluid_ml > 0 ? `${what} + ${i.fluid_ml} ml` : what;
}

function Target({ label, unit, value, fallback, onSave }) {
  const [draft, setDraft] = useState(value ?? "");
  useEffect(() => setDraft(value ?? ""), [value]);
  return (
    <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
      {label}
      <span className="flex items-center gap-1.5">
        {/* Blank = calculated, and the calculated value is the placeholder,
            so clearing a box is how an override is undone. */}
        <input
          className={INPUT} type="number" min="0" value={draft} placeholder={String(fallback)}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => onSave(draft === "" ? null : Number(draft))}
        />
        <span className="w-10 shrink-0">{unit}</span>
      </span>
    </label>
  );
}

// `calculated` is the effective targets: a placeholder only shows in a blank
// box, and a blank box's effective value is the calculated one.
function TargetsEditor({ plan, calculated, onPatch, onClose }) {
  return (
    <div className={PANEL}>
      <h4 className="text-sm font-semibold">Fuelling targets</h4>
      <div className="grid grid-cols-2 gap-3">
        {TARGET_FIELDS.map(([field, label, unit, key]) => (
          <Target key={field} label={label} unit={unit} value={plan[field]} fallback={calculated[key]}
            onSave={(v) => onPatch({ [field]: v })} />
        ))}
      </div>
      <div className="flex gap-2">
        <Button variant="neutral" onClick={() => onPatch(Object.fromEntries(TARGET_FIELDS.map(([f]) => [f, null])))}>
          Reset to calculated
        </Button>
        <Button variant="primary" onClick={onClose}>Done</Button>
      </div>
    </div>
  );
}

function ProductForm({ onAdd, onCancel }) {
  const [draft, setDraft] = useState({ name: "", kind: "gel", carbs_g: 25, sodium_mg: 0, caffeine_mg: 0, fluid_ml: 0 });
  return (
    <div className={PANEL}>
      <h4 className="text-sm font-semibold">New product</h4>
      <input className={INPUT} placeholder="Name" value={draft.name} autoFocus
        onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
      <select className={INPUT} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}>
        {KINDS.map((k) => <option key={k}>{k}</option>)}
      </select>
      <div className="grid grid-cols-2 gap-2">
        {[["carbs_g", "Carbs (g)"], ["sodium_mg", "Sodium (mg)"], ["caffeine_mg", "Caffeine (mg)"], ["fluid_ml", "Fluid (ml)"]].map(([k, l]) => (
          <label key={k} className="text-xs text-slate-500 dark:text-slate-400 flex flex-col gap-1">{l}
            <input className={INPUT} type="number" min="0" value={draft[k]}
              onChange={(e) => setDraft({ ...draft, [k]: Number(e.target.value) })} />
          </label>
        ))}
      </div>
      <div className="flex gap-2">
        <Button variant="neutral" onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={!draft.name.trim()} onClick={() => onAdd(draft)}>Add</Button>
      </div>
    </div>
  );
}

export default function FuelSection({ goalId, plan, onPatch }) {
  const [fuel, setFuel] = useState(null);
  const [products, setProducts] = useState([]);
  const [open, setOpen] = useState(null); // "targets" | "product" | null
  const editRef = useRef(null);
  const addRef = useRef(null);

  const reload = useCallback(() => {
    api.getFuelPlan(goalId).then(setFuel).catch(() => setFuel(null));
    api.getFuelProducts().then(setProducts).catch(() => setProducts([]));
  }, [goalId]);
  useEffect(reload, [reload, plan]);

  if (!fuel) return null;
  const t = fuel.targets;
  const chosen = new Set(plan.fuel_product_uids || []);

  async function toggleProduct(uid) {
    const next = (plan.fuel_product_uids || []).filter((u) => u !== uid);
    if (!chosen.has(uid)) next.push(uid);
    await onPatch({ fuel_product_uids: next });
  }

  async function addProduct(draft) {
    const p = await api.createFuelProduct({ ...draft, name: draft.name.trim() });
    setOpen(null);
    await onPatch({ fuel_product_uids: [...(plan.fuel_product_uids || []), p.uid] });
  }

  const gut = Object.values(fuel.gut_training || {});

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-base font-medium tabular-nums">
            {t.carbs_g_per_h} g carbs · {t.fluid_ml_per_h} ml · {t.sodium_mg_per_h} mg Na / h
          </p>
          <p className="text-sm text-slate-500 dark:text-slate-400">every {t.interval_min} min</p>
        </div>
        <button ref={editRef} onClick={() => setOpen(open === "targets" ? null : "targets")}
          aria-label="Edit fuelling targets"
          className="shrink-0 w-9 h-9 grid place-items-center rounded-full text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
          <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M12 20h9M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4Z" />
          </svg>
        </button>
        <AnchoredPopover anchorRef={editRef} open={open === "targets"} onClose={() => setOpen(null)} align="right">
          <TargetsEditor plan={plan} calculated={t} onPatch={onPatch} onClose={() => setOpen(null)} />
        </AnchoredPopover>
      </div>

      <div className="flex flex-wrap gap-2">
        {products.map((p) => {
          const on = chosen.has(p.uid);
          return (
            <button key={p.id} onClick={() => toggleProduct(p.uid)} aria-pressed={on}
              className={`inline-flex items-center gap-1 h-8 px-3 text-sm rounded-full border transition-colors ${on
                ? "border-transparent bg-accent-600/[.12] text-accent-700"
                : "border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700"}`}>
              {on && <span aria-hidden="true">✓</span>}
              {p.name} {Number(p.carbs_g)}g
            </button>
          );
        })}
        <button ref={addRef} onClick={() => setOpen(open === "product" ? null : "product")} aria-label="Add product"
          className="inline-flex items-center justify-center h-8 w-8 rounded-full border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700">
          <span className="w-4 h-4"><PlusIcon /></span>
        </button>
        <AnchoredPopover anchorRef={addRef} open={open === "product"} onClose={() => setOpen(null)} align="left">
          <ProductForm onAdd={addProduct} onCancel={() => setOpen(null)} />
        </AnchoredPopover>
      </div>

      {fuel.timeline.items.length > 0 && (
        <ul className="text-sm divide-y divide-slate-100 dark:divide-slate-800">
          {fuel.timeline.items.map((i) => (
            <li key={i.minute} className="grid grid-cols-[3.5rem_5rem_1fr] py-1.5">
              <span className="tabular-nums">{clock(i.minute)}</span>
              <span className="tabular-nums text-slate-500 dark:text-slate-400">
                {i.distance_m != null ? `${(i.distance_m / 1000).toFixed(1)} km` : ""}
              </span>
              <span>{stopLabel(i)}</span>
            </li>
          ))}
        </ul>
      )}

      {gut.length > 0 && (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          Gut training: {Math.min(...gut)} → {Math.max(...gut)} g/h over {gut.length} {gut.length === 1 ? "run" : "runs"}
        </p>
      )}
    </div>
  );
}
