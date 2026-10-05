// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Security section: change password (current + new + confirm) with show/hide
// toggles and client-side validation before calling the API.
import { useState } from "react";
import { api } from "../../api/client";
import { INPUT, Section, FieldRow, InlineError } from "./primitives";

export default function SecuritySection() {
  const [current, setCurrent] = useState("");
  const [newPass, setNewPass] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showCur, setShowCur] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);
  const [error,   setError]   = useState("");

  async function save() {
    if (newPass.length < 8) { setError("New password must be at least 8 characters."); return; }
    if (newPass !== confirm) { setError("Passwords do not match."); return; }
    setSaving(true); setError(""); setSaved(false);
    try {
      await api.changePassword({ current_password: current, new_password: newPass });
      setCurrent(""); setNewPass(""); setConfirm("");
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(e.message ?? "Password change failed.");
    } finally {
      setSaving(false);
    }
  }

  function PasswordInput({ label, value, onChange, show, onToggle, placeholder }) {
    return (
      <FieldRow label={label}>
        <div className="relative">
          <input
            className={INPUT + " pr-10"}
            type={show ? "text" : "password"}
            placeholder={placeholder}
            value={value}
            onChange={e => onChange(e.target.value)}
          />
          <button type="button" tabIndex={-1} onClick={onToggle}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round"
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
            </svg>
          </button>
        </div>
      </FieldRow>
    );
  }

  return (
    <Section title="Security">
      <PasswordInput label="Current password" value={current} onChange={setCurrent}
        show={showCur} onToggle={() => setShowCur(v => !v)} placeholder="Your current password" />
      <PasswordInput label="New password" value={newPass} onChange={setNewPass}
        show={showNew} onToggle={() => setShowNew(v => !v)} placeholder="8 characters minimum" />
      <FieldRow label="Confirm new password">
        <input className={INPUT} type="password"
          placeholder="Repeat new password"
          value={confirm}
          onChange={e => setConfirm(e.target.value)} />
      </FieldRow>
      <InlineError msg={error} />
      <div className="flex justify-end">
        <button
          type="button"
          onClick={save}
          disabled={saving}
          className="btn btn-primary btn-sm"
        >
          {saving ? "Saving…" : saved ? "Saved ✓" : "Change password"}
        </button>
      </div>
    </Section>
  );
}
