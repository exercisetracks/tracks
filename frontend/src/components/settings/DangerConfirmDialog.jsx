// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The confirmation every irreversible action in Settings goes through: two
// deliberate steps. First the consequences and a click to go on; then a
// phrase to type that names the action — "delete my data", "factory reset" —
// so the thing being agreed to is spelled out by the person agreeing to it,
// and no single misclick, Enter key or autofilled DELETE can get there. The
// admin wipes check the same phrase on the server (api/users.py).

import { useState } from "react";
import ConfirmDialog from "../ConfirmDialog";

export default function DangerConfirmDialog({ title, phrase, actionLabel, children, onConfirm, onClose }) {
  const [step, setStep] = useState(1);
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const matches = typed.trim().toLowerCase() === phrase;

  async function run() {
    if (step === 1) { setStep(2); return; }
    if (!matches) return;
    setBusy(true);
    setError("");
    try {
      await onConfirm(phrase);
    } catch (e) {
      setError(e.message ?? "That did not work.");
      setBusy(false);
    }
  }

  return (
    <ConfirmDialog
      title={title}
      danger
      busy={busy}
      confirmLabel={step === 1 ? "Continue" : busy ? "Working…" : actionLabel}
      confirmDisabled={step === 2 && !matches}
      onConfirm={run}
      onCancel={() => { if (!busy) onClose(); }}
    >
      {step === 1 ? (
        <div className="space-y-2 text-sm text-slate-700 dark:text-slate-300">{children}</div>
      ) : (
        <div>
          <p className="text-sm text-slate-700 dark:text-slate-300 mb-2">
            Are you sure about that? This cannot be undone.
          </p>
          <label className="field-label">
            Type <span className="font-mono font-bold text-red-600 dark:text-red-400">{phrase}</span> to confirm
          </label>
          <input
            className="field"
            type="text"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && matches && run()}
            autoComplete="off"
            autoFocus
            disabled={busy}
          />
          {error && <p className="field-hint !text-red-600 dark:!text-red-400">{error}</p>}
        </div>
      )}
    </ConfirmDialog>
  );
}
