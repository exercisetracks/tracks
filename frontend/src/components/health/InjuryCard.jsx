// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A single injury card for the Health page injury list. Renders one of two
// modes: the inline edit form (when this injury is being edited), or the
// read-only summary (body part / type / severity / active pill / date range /
// notes) with its Activities / Healed / Edit action buttons. Presentational —
// all mutations are delegated to the parent via callbacks; the parent also owns
// the highlight/edit selection state and the scroll-into-view ref registration.

import { fmtDate } from "./helpers";
import { Card, SeverityBadge } from "./ui";
import InjuryForm from "./InjuryForm";

export default function InjuryCard({
  injury,          // the injury record to render
  editing,         // true when this card is in inline-edit mode
  highlighted,     // true when selected from the timeline (ring highlight)
  formLoading,     // disables the edit form while a save is in flight
  registerRef,     // (el) => void — parent stores the ref for scroll-into-view
  onSelectActivities, // open the activities drawer for this injury
  onHeal,          // mark this injury healed (sets end_date to today)
  onEdit,          // enter inline-edit mode for this injury
  onSave,          // submit the edit form
  onCancelEdit,    // leave inline-edit mode without saving
  onDelete,        // delete this injury
}) {
  return (
    <Card className={highlighted ? "ring-2 ring-accent-500" : ""}>
      {/* Scroll anchor — parent registers this so timeline clicks can scroll here */}
      <div ref={registerRef} />
      {editing ? (
        <>
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">Edit injury</p>
          <InjuryForm
            initial={injury}
            onSave={onSave}
            onCancel={onCancelEdit}
            onDelete={onDelete}
            loading={formLoading}
          />
        </>
      ) : (
        <div className="flex items-start gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex flex-wrap items-center gap-2 mb-1">
              <span className="font-semibold text-slate-800 dark:text-slate-100 text-sm">
                {injury.body_part} — {injury.injury_type}
              </span>
              <SeverityBadge severity={injury.severity} />
              {!injury.end_date && (
                <span className="badge bg-red-100 text-red-600 dark:bg-red-900 dark:text-red-300">
                  Active
                </span>
              )}
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              {fmtDate(injury.start_date)}
              {injury.end_date ? ` → ${fmtDate(injury.end_date)}` : " → ongoing"}
            </p>
            {injury.notes && (
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 italic">{injury.notes}</p>
            )}
          </div>
          <div className="flex gap-1.5 shrink-0 flex-wrap justify-end">
            <button
              onClick={onSelectActivities}
              className="btn btn-neutral btn-sm"
            >
              Activities
            </button>
            {!injury.end_date && (
              <button
                onClick={onHeal}
                className="btn btn-tonal btn-sm"
              >
                Healed
              </button>
            )}
            <button
              onClick={onEdit}
              className="btn btn-neutral btn-sm"
            >
              Edit
            </button>
          </div>
        </div>
      )}
    </Card>
  );
}
