// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// App-styled confirmation, replacing window.confirm(): the shared Modal with a
// title, a line of explanation and two buttons. `danger` makes the confirm
// red. Above other dialogs (z-[70]) because it is often opened from one.
//
// `children` sits between the message and the buttons, for a confirmation
// that carries an option (delete — and allow re-import?) or a typed check
// (`confirmDisabled` until it is right).
import Modal from "./ui/Modal";

export default function ConfirmDialog({
  title, message, confirmLabel = "Confirm", cancelLabel = "Cancel",
  danger = false, busy = false, confirmDisabled = false, onConfirm, onCancel, children,
}) {
  return (
    <Modal title={title} onClose={busy ? undefined : onCancel} width="max-w-sm" z="z-[70]">
      {message && <p className="text-sm text-slate-600 dark:text-slate-400">{message}</p>}
      {children}
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" onClick={onCancel} disabled={busy} className="btn btn-neutral">
          {cancelLabel}
        </button>
        <button type="button" onClick={onConfirm} disabled={busy || confirmDisabled}
          className={`btn ${danger ? "btn-danger" : "btn-primary"}`}>
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
