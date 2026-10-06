// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * GarminAnimationPicker — typeahead picker for selecting a Garmin Connect
 * animation by name. Used by the custom exercise + custom stretch modals so
 * users can pin their custom row to an animating (cat, subtype) pair.
 *
 * Props:
 *   app          — "strength" | "yoga"   filters the manifest by app context
 *   value        — { category, subtype } | null
 *   onChange     — fn({ category, subtype, name, display }) | fn(null)
 *
 * The component fetches the manifest filtered by app on mount, caches it in
 * a module-level map so repeated mounts don't re-fetch, and renders a
 * combobox-style filter + a small "Animated" / "No animation selected"
 * badge for at-a-glance feedback.
 */
import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import AnimationBadge from "./AnimationBadge";

const _cache = new Map();   // app → list of animations

function _displayLabel(a) {
  // ALL_FOURS → "All Fours"
  return a.display.toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, c => c.toUpperCase());
}

export default function GarminAnimationPicker({ app = "strength", value, onChange }) {
  const [items, setItems] = useState([]);
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (_cache.has(app)) {
      setItems(_cache.get(app));
      return;
    }
    let cancelled = false;
    setLoading(true);
    api.getGarminAnimations({ app }).then(data => {
      if (cancelled) return;
      _cache.set(app, data || []);
      setItems(data || []);
    }).catch(() => {
      if (!cancelled) setItems([]);
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [app]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return items.slice(0, 50);   // cap initial render
    return items.filter(a =>
      a.name.includes(q) || a.cat.includes(q) || a.display.toLowerCase().includes(q)
    ).slice(0, 50);
  }, [items, search]);

  const selected = useMemo(() => {
    if (!value?.category || value?.subtype == null) return null;
    return items.find(a => a.cat === value.category && a.name_int === value.subtype) || null;
  }, [items, value]);

  return (
    <div>
      <div className="flex items-center justify-between mb-1.5">
        <label className="text-xs font-medium text-slate-600 dark:text-slate-400">
          Garmin animation
        </label>
        <AnimationBadge hasAnimation={!!selected} show />
      </div>
      <p className="text-[11px] text-slate-400 dark:text-slate-500 mb-1.5">
        Pin this entry to a Garmin animation so the watch plays the demo during a synced workout.
      </p>

      {/* Selected pill */}
      {selected ? (
        <div className="flex items-center justify-between rounded-lg border border-accent-200 dark:border-accent-800 bg-accent-50 dark:bg-accent-900/20 px-2.5 py-1.5 mb-2">
          <div className="text-sm text-slate-700 dark:text-slate-200">
            <span className="font-medium">{_displayLabel(selected)}</span>
            <span className="text-[11px] text-slate-400 dark:text-slate-500 ml-2">
              ({selected.cat} · {selected.name_int})
            </span>
          </div>
          <button type="button" onClick={() => onChange(null)}
            className="btn btn-neutral btn-sm">clear</button>
        </div>
      ) : (
        <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 px-2.5 py-1.5 mb-2 text-xs text-slate-500 dark:text-slate-400">
          No animation selected — this entry will display as text only on the watch.
        </div>
      )}

      {/* Search */}
      <div className="relative">
        <input type="text" value={search}
          onChange={e => { setSearch(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          placeholder={loading ? "Loading…" : `Search Garmin's ${items.length} animations…`}
          className="field" />

        {open && search && filtered.length > 0 && (
          <div className="absolute left-0 right-0 top-full mt-1 max-h-64 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg z-10">
            {filtered.map(a => (
              <button
                key={`${a.cat}-${a.name_int}`}
                type="button"
                onClick={() => {
                  onChange({
                    category: a.cat,
                    subtype:  a.name_int,
                    name:     a.name,
                    display:  a.display,
                  });
                  setSearch("");
                  setOpen(false);
                }}
                className="w-full text-left px-2.5 py-1.5 hover:bg-accent-50 dark:hover:bg-accent-900/20 border-b border-slate-100 dark:border-slate-700 last:border-b-0 transition-colors"
              >
                <div className="text-sm text-slate-800 dark:text-slate-200">{_displayLabel(a)}</div>
                <div className="text-[11px] text-slate-400 dark:text-slate-500">
                  {a.cat} · {a.apps.join(", ")}
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
