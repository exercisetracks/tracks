// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * AnimationBadge — pill rendering one of four animation states for an
 * exercise / stretch entry.
 *
 *   confirmed_yes  green "Animates" badge   — verified on the user's watch
 *   likely         amber "Likely"   badge   — in Garmin's manifest, unverified
 *   confirmed_no   red   "No animation"     — verified DOESN'T animate
 *   unknown        no badge rendered        — no Garmin mapping at all
 *
 * Backwards-compatible: passing `hasAnimation` (boolean) maps to the legacy
 * 2-state view (true → likely, false → no badge). Prefer passing `state`
 * for the 4-state experience.
 */

const _STATE_CONFIG = {
  confirmed_yes: {
    label: "Animates",
    title: "Verified to play on your watch",
    classes: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/40",
    icon: "play",
  },
  likely: {
    label: "Likely animates",
    title: "In Garmin's animation library but not yet verified on your watch",
    classes: "bg-amber-500/15 text-amber-300 ring-amber-500/40",
    icon: "play",
  },
  confirmed_no: {
    label: "No animation",
    title: "You marked this as not animating on your watch",
    classes: "bg-rose-500/15 text-rose-300 ring-rose-500/40",
    icon: "x",
  },
  unknown: null,   // intentionally renders nothing
};


function _Icon({ kind }) {
  if (kind === "play") {
    return (
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
        <path d="M10 8.5l5 3.5-5 3.5V8.5z" fill="currentColor" />
      </svg>
    );
  }
  if (kind === "x") {
    return (
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
        <path d="M9 9l6 6M15 9l-6 6" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </svg>
    );
  }
  return null;
}


export default function AnimationBadge({ state, hasAnimation, show = true, className = "" }) {
  if (!show) return null;
  // Resolve effective state. Prefer `state` (4-state); fall back to
  // `hasAnimation` boolean for legacy callsites.
  let effective = state;
  if (!effective) {
    effective = hasAnimation === true ? "likely"
              : hasAnimation === false ? "unknown"
              : "unknown";
  }
  const cfg = _STATE_CONFIG[effective];
  if (!cfg) return null;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ${cfg.classes} ${className}`}
      title={cfg.title}
    >
      <_Icon kind={cfg.icon} />
      {cfg.label}
    </span>
  );
}
