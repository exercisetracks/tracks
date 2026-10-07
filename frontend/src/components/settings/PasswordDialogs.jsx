// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Changing a password, from User management: your own with your current one,
// or (as an admin) someone else's — which is a different operation, because
// their data is encrypted under a key only their password or recovery key can
// unwrap (backend api/users.py admin_reset_password says how each case goes).

import { useState } from "react";
import { api, TOKEN_KEY } from "../../api/client";
import Modal from "../ui/Modal";
import Checkbox from "../ui/Checkbox";

function PasswordField({ label, value, onChange, placeholder, autoFocus }) {
  const [show, setShow] = useState(false);
  return (
    <div>
      <label className="field-label">{label}</label>
      <div className="relative">
        <input
          className="field pr-10"
          type={show ? "text" : "password"}
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          autoFocus={autoFocus}
        />
        <button type="button" tabIndex={-1} onClick={() => setShow((v) => !v)} aria-label={show ? "Hide" : "Show"}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round"
              d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
          </svg>
        </button>
      </div>
    </div>
  );
}

function check(newPass, confirm) {
  if (newPass.length < 8) return "The new password must be at least 8 characters.";
  if (newPass !== confirm) return "The passwords do not match.";
  return "";
}

/** Your own password, with your current one. */
export function ChangeOwnPasswordDialog({ onClose }) {
  const [current, setCurrent] = useState("");
  const [newPass, setNewPass] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(null);

  async function save(e) {
    e.preventDefault();
    const problem = check(newPass, confirm);
    if (problem) { setError(problem); return; }
    setBusy(true); setError("");
    try {
      const res = await api.changePassword({ current_password: current, new_password: newPass });
      // A password change refuses every token issued before it, this one
      // included; the server hands back a replacement for the same session.
      if (res?.access_token) localStorage.setItem(TOKEN_KEY, res.access_token);
      setDone(res);
    } catch (err) {
      setError(err.message ?? "The password was not changed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="Change password" onClose={busy ? undefined : onClose} width="max-w-sm">
      {done ? (
        <div className="space-y-3">
          <p className="text-sm text-slate-700 dark:text-slate-300">
            Password changed. Every other device and session has been signed out
            {done.revoked_device_keys ? ` (${done.revoked_device_keys} device${done.revoked_device_keys === 1 ? "" : "s"})` : ""}.
          </p>
          <div className="flex justify-end"><button type="button" className="btn btn-primary" onClick={onClose}>Done</button></div>
        </div>
      ) : (
        <form onSubmit={save} className="space-y-3">
          <PasswordField label="Current password" value={current} onChange={setCurrent} autoFocus />
          <PasswordField label="New password" value={newPass} onChange={setNewPass} placeholder="8 characters minimum" />
          <PasswordField label="Confirm new password" value={confirm} onChange={setConfirm} />
          {error && <p className="alert-error">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn btn-neutral" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={busy || !current}>{busy ? "Saving…" : "Change password"}</button>
          </div>
        </form>
      )}
    </Modal>
  );
}

/** Another account's password, as an admin. */
export function AdminSetPasswordDialog({ user, onClose }) {
  const [newPass, setNewPass] = useState("");
  const [confirm, setConfirm] = useState("");
  const [recovery, setRecovery] = useState("");
  const [discard, setDiscard] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(null);
  const withKey = recovery.trim().length > 0;

  async function save(e) {
    e.preventDefault();
    const problem = check(newPass, confirm);
    if (problem) { setError(problem); return; }
    if (!withKey && !discard) { setError("Enter their recovery key, or confirm their data may be deleted."); return; }
    setBusy(true); setError("");
    try {
      setDone(await api.adminSetPassword(user.id, {
        new_password: newPass,
        ...(withKey ? { recovery_key: recovery.trim() } : { discard_data: true }),
      }));
    } catch (err) {
      setError(err.message ?? "The password was not changed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Set password for ${user.username}`} onClose={busy ? undefined : onClose} width="max-w-md">
      {done ? (
        <div className="space-y-3 text-sm text-slate-700 dark:text-slate-300">
          <p>
            Password set{done.data_kept ? ", and their data is intact" : ". Their old data was deleted"}.
            They have been signed out everywhere.
          </p>
          {done.recovery_key && (
            <div className="rounded-lg border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-3">
              <p className="text-amber-800 dark:text-amber-300">
                Their new recovery key — hand it to them with the password. It will not be shown again.
              </p>
              <div className="mt-2 font-mono text-xs break-words bg-white dark:bg-slate-900 border border-amber-200 dark:border-amber-800 rounded px-2 py-1.5 text-slate-900 dark:text-white select-all">
                {done.recovery_key}
              </div>
            </div>
          )}
          <div className="flex justify-end"><button type="button" className="btn btn-primary" onClick={onClose}>Done</button></div>
        </div>
      ) : (
        <form onSubmit={save} className="space-y-3">
          <PasswordField label="New password" value={newPass} onChange={setNewPass} placeholder="8 characters minimum" autoFocus />
          <PasswordField label="Confirm new password" value={confirm} onChange={setConfirm} />
          <div>
            <label className="field-label">Their recovery key</label>
            <input className="field font-mono" type="text" value={recovery} autoComplete="off"
              onChange={(e) => setRecovery(e.target.value)} placeholder="12 words, from when the account was made" />
            <p className="field-hint">
              Their activities, health records and files are encrypted under their password; only
              this key lets them be kept when someone else sets a new one.
            </p>
          </div>
          {!withKey && (
            <Checkbox checked={discard} onChange={setDiscard}>
              I don't have it — delete their data and give them a fresh start
            </Checkbox>
          )}
          {error && <p className="alert-error">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn btn-neutral" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="submit" className={`btn ${withKey ? "btn-primary" : "btn-danger"}`} disabled={busy || (!withKey && !discard)}>
              {busy ? "Saving…" : withKey ? "Set password" : "Delete data and set password"}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}
