// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The visual slot for a movement inside the guided players, detail modals and
// step rows. No visual asset source is wired up yet — this renders a themed
// placeholder for the movement's name and side.
export default function MovementSlot({ name, muscles = [], side = null, size = "hero" }) {
  const isHero = size === "hero";

  return (
    <div
      className={`relative flex flex-col items-center justify-center rounded-2xl overflow-hidden
        bg-gradient-to-br from-accent-50 to-slate-100 dark:from-slate-800 dark:to-slate-900
        border border-slate-200 dark:border-slate-700 ${isHero ? "aspect-[4/3] w-full" : "w-16 h-16"}`}
      aria-label={name}
    >
      {isHero && (
        <div className="absolute bottom-3 left-0 right-0 text-center px-2.5">
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{name}</p>
          {side && (
            <p className="text-xs font-medium uppercase tracking-wide text-accent-600 dark:text-accent-400">
              {side} side
            </p>
          )}
        </div>
      )}
    </div>
  );
}
