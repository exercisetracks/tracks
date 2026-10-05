// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// App-styled confirmation modal — the shared version of the inline ConfirmModal
// in Goals.jsx, so delete/destructive confirmations look consistent everywhere
// (replacing window.confirm()).
export default function ConfirmDialog({
  title, message, confirmLabel = "Confirm", cancelLabel = "Cancel",
  danger = false, onConfirm, onCancel,
}) {
  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-3.5" onClick={onCancel}>
      <div
        className="bg-white dark:bg-slate-900 rounded-xl shadow-lg max-w-sm w-full p-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h3>
        {message && <p className="text-sm text-slate-600 dark:text-slate-400 mt-2">{message}</p>}
        <div className="flex justify-end gap-2 mt-5">
          <button onClick={onCancel}
            className="btn btn-neutral">
            {cancelLabel}
          </button>
          <button onClick={onConfirm}
            className={`btn ${danger ? "btn-danger" : "btn-primary"}`}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
