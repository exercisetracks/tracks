// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Animation-feedback banner for the Dashboard.
//
// Shown only when there are completed workouts still waiting for the user to
// mark which exercises animated on their watch. Purely presentational: it takes
// the pending list and an onReview callback that opens the recap modal (the
// page owns the list + modal state). Renders nothing when the list is empty.

export default function RecapBanner({ pendingRecaps, onReview }) {
  if (pendingRecaps.length === 0) return null;

  return (
    <div
      className="rounded-xl border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-900/20 p-3.5 flex items-center justify-between gap-4 cursor-pointer hover:border-amber-400 transition-colors"
      onClick={onReview}
    >
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-amber-900 dark:text-amber-100">
          {pendingRecaps.length === 1
            ? "1 workout pending animation feedback"
            : `${pendingRecaps.length} workouts pending animation feedback`}
        </p>
        <p className="text-xs text-amber-700 dark:text-amber-300 mt-0.5">
          Tell Tracks which exercises animated on your watch so the planner can pick better next time.
        </p>
      </div>
      <button
        type="button"
        className="px-2.5 py-1 rounded-lg bg-amber-500 hover:bg-amber-600 text-white text-xs font-semibold whitespace-nowrap"
      >
        Review →
      </button>
    </div>
  );
}
