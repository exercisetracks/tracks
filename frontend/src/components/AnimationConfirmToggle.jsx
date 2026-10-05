// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * AnimationConfirmToggle — pair of thumbs-up / thumbs-down icons that lets
 * the user mark whether an exercise's animation plays on their watch.
 *
 * Three render states based on the current `state` prop:
 *   confirmed_yes  → thumbs-up filled (active green); thumbs-down outline
 *   confirmed_no   → thumbs-down filled (active red);  thumbs-up  outline
 *   anything else  → both outline (default — invites a click)
 *
 * Clicking a filled icon clears the confirmation (DELETE). Clicking the
 * opposite icon flips the verdict (POST with the new bool).
 *
 * Hidden entirely when the entry has no (garmin_category, garmin_subtype) —
 * there's nothing to confirm. Also hidden when `disabled` (no primary
 * device set yet); we show a small "set a device" hint instead.
 */
import { useState } from "react";
import { api } from "../api/client";

export default function AnimationConfirmToggle({
  category, subtype, state,
  onChange, disabled = false, className = "",
}) {
  const [saving, setSaving] = useState(false);
  if (category == null || subtype == null) return null;

  const isYes = state === "confirmed_yes";
  const isNo  = state === "confirmed_no";

  if (disabled) {
    return (
      <span
        className={`text-[10px] text-slate-400 italic ${className}`}
        title="Set a primary device first (Settings → Devices) to record per-watch animation feedback"
      >
        set device
      </span>
    );
  }

  const click = async (verdict) => {  // verdict: true/false/null
    if (saving) return;
    setSaving(true);
    try {
      if (verdict === null) {
        await api.deleteAnimationConfirmation(category, subtype);
        onChange?.("likely");
      } else {
        const r = await api.upsertAnimationConfirmation(category, subtype, verdict);
        onChange?.(r?.animation_state || (verdict ? "confirmed_yes" : "confirmed_no"));
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <span className={`inline-flex items-center gap-0.5 ${className}`}
          onClick={e => e.stopPropagation()}>
      <button type="button"
        onClick={() => click(isYes ? null : true)}
        disabled={saving}
        title={isYes ? "You marked this as animating — click to clear" : "Mark as animating on your watch"}
        className={`p-1 rounded transition-colors text-sm leading-none ${
          isYes
            ? "text-emerald-400 bg-emerald-500/20"
            : "text-slate-400 hover:text-emerald-400 hover:bg-emerald-500/10"
        } disabled:opacity-40`}
      >
        {/* thumbs up — filled vs outline based on state */}
        <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true"
             fill={isYes ? "currentColor" : "none"}
             stroke="currentColor" strokeWidth="2"
             strokeLinecap="round" strokeLinejoin="round">
          <path d="M7 22h2c0.55 0 1-0.45 1-1v-9c0-0.55-0.45-1-1-1H7v11zm14-10c0-1.1-0.9-2-2-2h-6.31l0.95-4.57c0.02-0.1 0.03-0.21 0.03-0.32 0-0.41-0.17-0.79-0.44-1.06L12.17 3 5.59 9.59C5.22 9.95 5 10.45 5 11v9c0 1.1 0.9 2 2 2h9c0.83 0 1.54-0.5 1.84-1.22l3.02-7.05c0.09-0.23 0.14-0.47 0.14-0.73v-1z"/>
        </svg>
      </button>
      <button type="button"
        onClick={() => click(isNo ? null : false)}
        disabled={saving}
        title={isNo ? "You marked this as NOT animating — click to clear" : "Mark as not animating on your watch"}
        className={`p-1 rounded transition-colors text-sm leading-none ${
          isNo
            ? "text-rose-400 bg-rose-500/20"
            : "text-slate-400 hover:text-rose-400 hover:bg-rose-500/10"
        } disabled:opacity-40`}
      >
        {/* thumbs down — mirror of the up icon */}
        <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true"
             fill={isNo ? "currentColor" : "none"}
             stroke="currentColor" strokeWidth="2"
             strokeLinecap="round" strokeLinejoin="round"
             style={{ transform: "rotate(180deg)" }}>
          <path d="M7 22h2c0.55 0 1-0.45 1-1v-9c0-0.55-0.45-1-1-1H7v11zm14-10c0-1.1-0.9-2-2-2h-6.31l0.95-4.57c0.02-0.1 0.03-0.21 0.03-0.32 0-0.41-0.17-0.79-0.44-1.06L12.17 3 5.59 9.59C5.22 9.95 5 10.45 5 11v9c0 1.1 0.9 2 2 2h9c0.83 0 1.54-0.5 1.84-1.22l3.02-7.05c0.09-0.23 0.14-0.47 0.14-0.73v-1z"/>
        </svg>
      </button>
    </span>
  );
}
