// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Race fuelling: four gauges (carbs, sodium, potassium, fluid per hour), the
// race's stops on a strip along its clock, the products to carry — the
// built-in homemade mix first — and that mix's recipe when it is carried.
// All numbers come from the server's calculators/fuel_plan.py
// (/fuel/goals/{id}/plan); the phone computes the same plan itself from
// synced rows and lays it out the same way (mobile/.../ui/race/FuelSection.kt).
//
// A gauge's scale runs to what an elite ultra athlete takes per hour, its fill
// is what the chosen products deliver, and the tick is the target — so a gap
// between fill and tick is food still to plan, and how far round the tick
// sits is how demanding the plan is (docs/race-fuelling-research.md).
//
// Deliberately no helper text: the section had grown a sentence under every
// part, and the numbers explain themselves once they sit in one place. The
// target boxes live behind the pencil because they are set once per race,
// not read every time.
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import AnchoredPopover from "../ui/AnchoredPopover";
import Button, { PlusIcon } from "../ui/Button";
import RadialGauge from "../RadialGauge";
import Tabs from "../ui/Tabs";

/** The built-in homemade mix's uid among the race's products (fuel_plan.HOMEMADE_UID). */
export const HOMEMADE_UID = "homemade";

/** The gauges: the target key, what the timeline delivers, the ceiling key, and a colour each is named beside. */
export const GAUGES = [
  { key: "carbs_g_per_h", got: "carbs_g", label: "Carbs", unit: "g", color: "#eda100" },
  { key: "sodium_mg_per_h", got: "sodium_mg", label: "Sodium", unit: "mg", color: "#1baf7a" },
  { key: "potassium_mg_per_h", got: "potassium_mg", label: "Potassium", unit: "mg", color: "#e87ba4" },
  { key: "fluid_ml_per_h", got: "fluid_ml", label: "Fluid", unit: "ml", color: "#2a78d6" },
];

const PRESETS = [
  { key: "basic", label: "Basic" },
  { key: "high_carb", label: "High-carb" },
  { key: "natural", label: "Natural" },
];

const INPUT = "field";
const KINDS = ["gel", "drink", "chew", "bar", "other"];
const PANEL = "w-72 p-4 space-y-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg text-slate-800 dark:text-slate-100";
const TARGET_FIELDS = [
  ["fuel_carbs_per_hour", "Carbs", "g/h", "carbs_g_per_h"],
  ["fuel_fluid_ml_per_hour", "Fluid", "ml/h", "fluid_ml_per_h"],
  ["fuel_sodium_mg_per_hour", "Sodium", "mg/h", "sodium_mg_per_h"],
  ["fuel_potassium_mg_per_hour", "Potassium", "mg/h", "potassium_mg_per_h"],
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
      <h4 className="text-sm font-semibold text-slate-900 dark:text-white">Fuelling targets</h4>
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
  const [draft, setDraft] = useState({ name: "", kind: "gel", carbs_g: 25, sodium_mg: 0, potassium_mg: 0, caffeine_mg: 0, fluid_ml: 0 });
  return (
    <div className={PANEL}>
      <h4 className="text-sm font-semibold text-slate-900 dark:text-white">New product</h4>
      <input className={INPUT} placeholder="Name" value={draft.name} autoFocus
        onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
      <select className={INPUT} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}>
        {KINDS.map((k) => <option key={k}>{k}</option>)}
      </select>
      <div className="grid grid-cols-2 gap-2">
        {[["carbs_g", "Carbs (g)"], ["sodium_mg", "Sodium (mg)"], ["potassium_mg", "Potassium (mg)"], ["caffeine_mg", "Caffeine (mg)"], ["fluid_ml", "Fluid (ml)"]].map(([k, l]) => (
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

/** One gauge: filled to what the products deliver, ticked at the target, scaled to an elite's hour. */
function FuelGauge({ g, target, got, ceiling }) {
  return (
    <div className="flex flex-col items-center gap-1">
      <RadialGauge value={got ?? 0} min={0} max={ceiling} target={target} size={104} stroke={8}
        zones={[{ label: g.label, min: 0, max: ceiling, color: g.color }]}
        unit={g.unit} caption={`target ${target}`} captionColor="#94a3b8" />
      <span className="text-xs font-medium text-slate-600 dark:text-slate-300">{g.label} / h</span>
    </div>
  );
}

/**
 * The stops along the race clock, start to finish: a dot per stop, a sip of
 * the homemade mix small and a product large. Picking one names it; the full
 * list is folded underneath.
 */
export function TimelineStrip({ items, durationMin }) {
  const [picked, setPicked] = useState(null);
  const [all, setAll] = useState(false);
  if (!items.length || !durationMin) return null;
  const at = (m) => `${Math.min(100, (m / durationMin) * 100)}%`;
  const shown = picked ?? null;
  return (
    <div className="space-y-2">
      <div className="relative h-6" role="list" aria-label="Fuel stops">
        <div className="absolute left-0 right-0 top-1/2 h-0.5 -translate-y-1/2 rounded bg-slate-200 dark:bg-slate-700" />
        {items.map((i, n) => {
          const sip = i.product_uid === HOMEMADE_UID;
          return (
            <button key={`${i.minute}-${n}`} role="listitem" onClick={() => setPicked(i === picked ? null : i)}
              aria-label={`${clock(i.minute)} ${stopLabel(i)}`}
              className={`absolute top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-white dark:ring-slate-800 ${
                sip ? "w-2 h-2 bg-sky-500" : "w-3.5 h-3.5 bg-amber-500"} ${i === picked ? "scale-150" : ""}`}
              style={{ left: at(i.minute) }} />
          );
        })}
      </div>
      <div className="flex justify-between text-xs tabular-nums text-slate-500 dark:text-slate-400">
        <span>0:00</span>
        {shown
          ? <span className="text-slate-800 dark:text-slate-100">
              {clock(shown.minute)}{shown.distance_m != null ? ` · ${(shown.distance_m / 1000).toFixed(1)} km` : ""} · {stopLabel(shown)}
            </span>
          : <span>{items.length} stops</span>}
        <span>{clock(Math.round(durationMin))}</span>
      </div>
      <button className="text-xs font-medium text-accent-700 dark:text-accent-300" onClick={() => setAll(!all)}
        aria-expanded={all}>
        {all ? "Hide stops" : `Show all stops (${items.length})`}
      </button>
      {all && (
        <ul className="text-sm divide-y divide-slate-100 dark:divide-slate-800">
          {items.map((i, n) => (
            <li key={`${i.minute}-${n}`} className="grid grid-cols-[3.5rem_5rem_1fr] py-1.5">
              <span className="tabular-nums">{clock(i.minute)}</span>
              <span className="tabular-nums text-slate-500 dark:text-slate-400">
                {i.distance_m != null ? `${(i.distance_m / 1000).toFixed(1)} km` : ""}
              </span>
              <span>{stopLabel(i)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** "45 g (3½ tbsp)", or "60 ml (¼ cup)" for a liquid. */
export function amountLabel(i) {
  return i.ml != null ? `${i.ml} ml (${i.measure})` : `${i.grams} g (${i.measure})`;
}

/** The homemade mix's recipe: pick a recipe, size the bottle, read what goes in. */
function MixCard({ mix, plan, onPatch }) {
  const [bottle, setBottle] = useState(plan.fuel_mix_bottle_ml ?? "");
  useEffect(() => setBottle(plan.fuel_mix_bottle_ml ?? ""), [plan.fuel_mix_bottle_ml]);
  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h4 className="text-sm font-semibold text-slate-900 dark:text-white">Homemade mix</h4>
        <Tabs size="sm" label="Recipe" tabs={PRESETS} value={mix.preset}
          onChange={(k) => onPatch({ fuel_mix_preset: k })} />
      </div>
      <div className="flex items-center gap-2 text-sm">
        <span className="text-slate-500 dark:text-slate-400">Per bottle of</span>
        <input className="field w-24" type="number" min="100" step="50" aria-label="Bottle size (ml)"
          value={bottle} placeholder="750" onChange={(e) => setBottle(e.target.value)}
          onBlur={() => onPatch({ fuel_mix_bottle_ml: bottle === "" ? null : Number(bottle) })} />
        <span className="text-slate-500 dark:text-slate-400">ml · make {mix.bottles}</span>
      </div>
      <ul className="text-sm divide-y divide-slate-100 dark:divide-slate-800">
        {mix.ingredients.map((i) => (
          <li key={i.key} className="flex justify-between py-1.5">
            <span>{i.name}</span>
            <span className="tabular-nums">{amountLabel(i)}</span>
          </li>
        ))}
        <li className="flex justify-between py-1.5">
          <span>Water</span>
          <span className="tabular-nums">to {mix.bottle_ml} ml</span>
        </li>
      </ul>
      <p className="text-xs tabular-nums text-slate-500 dark:text-slate-400">
        {mix.concentration_pct}% · sip {mix.sip_ml} ml every {mix.interval_min} min
        {mix.preset === "natural" && " · coconut water varies: check its label"}
      </p>
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
  const got = fuel.timeline.per_hour || {};
  const duration = plan.predicted_seconds ? plan.predicted_seconds / 60 : 0;

  return (
    // Every line coloured from the theme: the figures used to take the
    // inherited default — black, on the dark theme's near-black card.
    <div className="space-y-5 text-slate-700 dark:text-slate-200">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-wrap gap-4" data-testid="fuel-gauges">
          {GAUGES.map((g) => (
            <FuelGauge key={g.key} g={g} target={t[g.key]} got={got[g.got]} ceiling={fuel.ceilings[g.key]} />
          ))}
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0">
          <button ref={editRef} onClick={() => setOpen(open === "targets" ? null : "targets")}
            aria-label="Edit fuelling targets"
            className="icon-btn">
            <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 20h9M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4Z" />
            </svg>
          </button>
          {/* The mix's sips set the stops when it is carried (fuel_plan.timeline). */}
          <span className="text-xs text-slate-500 dark:text-slate-400">every {fuel.homemade?.interval_min ?? t.interval_min} min</span>
        </div>
        <AnchoredPopover anchorRef={editRef} open={open === "targets"} onClose={() => setOpen(null)} align="right">
          <TargetsEditor plan={plan} calculated={t} onPatch={onPatch} onClose={() => setOpen(null)} />
        </AnchoredPopover>
      </div>

      <TimelineStrip items={fuel.timeline.items} durationMin={duration} />

      <div className="flex flex-wrap gap-2">
        <button onClick={() => toggleProduct(HOMEMADE_UID)} aria-pressed={chosen.has(HOMEMADE_UID)}
          className="chip">
          {chosen.has(HOMEMADE_UID) && <span aria-hidden="true">✓</span>}
          Homemade mix
        </button>
        {products.map((p) => {
          const on = chosen.has(p.uid);
          return (
            <button key={p.id} onClick={() => toggleProduct(p.uid)} aria-pressed={on} className="chip">
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

      {fuel.homemade && <MixCard mix={fuel.homemade} plan={plan} onPatch={onPatch} />}

      {gut.length > 0 && (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          Gut training: {Math.min(...gut)} → {Math.max(...gut)} g/h over {gut.length} {gut.length === 1 ? "run" : "runs"}
        </p>
      )}
    </div>
  );
}
