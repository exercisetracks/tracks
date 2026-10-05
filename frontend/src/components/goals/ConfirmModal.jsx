// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Generic confirmation modal (backdrop-click + Cancel both dismiss). Used by the
// Goals page for delete confirmation; `danger` swaps the confirm button to red.

export default function ConfirmModal({ title, message, onConfirm, onCancel, danger }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3.5" onClick={onCancel}>
      <div className="bg-white dark:bg-slate-900 rounded-xl shadow-2xl max-w-sm w-full p-4"
        onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h3>
        <p className="text-sm text-slate-600 dark:text-slate-400 mt-2">{message}</p>
        <div className="flex justify-end gap-2 mt-5">
          <button onClick={onCancel}
            className="btn btn-neutral">
            Cancel
          </button>
          <button onClick={onConfirm}
            className={`btn ${danger ? "btn-danger" : "btn-primary"}`}>
            Confirm
          </button>
        </div>
      </div>
    </div>
  );
}
