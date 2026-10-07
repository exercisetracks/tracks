// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// User management: the accounts on this server, and each one's actions.
//
// Everyone sees their own row, with Change password — that used to be a whole
// Security section of three fields, for something done once a year. An admin
// sees every account too: set another's password (PasswordDialogs.jsx says
// why that is a different operation from changing your own), make or revoke
// admin, remove; and Add user, a dialog rather than a form left open under
// the list.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { Section } from "./primitives";
import Modal from "../ui/Modal";
import ConfirmDialog from "../ConfirmDialog";
import { AdminSetPasswordDialog, ChangeOwnPasswordDialog } from "./PasswordDialogs";

function RecoveryKeyNotice({ account, onDone }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="rounded-lg border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-3">
      <p className="text-sm text-amber-800 dark:text-amber-300">
        Recovery key for <strong>{account.username}</strong> — hand this to them along with
        their password. It won't be shown again, and without it their data cannot be kept
        if their password is ever reset.
      </p>
      <div className="mt-2 font-mono text-xs leading-relaxed break-words bg-white dark:bg-slate-900 border border-amber-200 dark:border-amber-800 rounded px-2 py-1.5 text-slate-900 dark:text-white select-all">
        {account.key}
      </div>
      <div className="mt-2 flex gap-2">
        <button type="button" className="btn btn-tonal btn-sm"
          onClick={() => navigator.clipboard?.writeText(account.key).then(() => { setCopied(true); setTimeout(() => setCopied(false), 2000); })}>
          {copied ? "Copied!" : "Copy"}
        </button>
        <button type="button" onClick={onDone} className="btn btn-neutral btn-sm ml-auto">Done</button>
      </div>
    </div>
  );
}

function AddUserDialog({ onClose, onCreated }) {
  const [form, setForm] = useState({ username: "", name: "", password: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setError("");
    try {
      const c = await api.createUser(form);
      setCreated({ username: c.username, key: c.recovery_key });
      onCreated();
    } catch (err) {
      setError(err.message ?? "Failed to create user");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="Add user" onClose={busy ? undefined : onClose} width="max-w-md">
      {created ? (
        <RecoveryKeyNotice account={created} onDone={onClose} />
      ) : (
        <form onSubmit={submit} className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="field-label">Username</label>
              <input className="field" type="text" placeholder="e.g. jane" value={form.username} autoFocus required minLength={3}
                onChange={(e) => setForm((f) => ({ ...f, username: e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, "") }))} />
            </div>
            <div>
              <label className="field-label">Display name</label>
              <input className="field" type="text" placeholder="e.g. Jane" value={form.name} required
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} />
            </div>
          </div>
          <div>
            <label className="field-label">Password</label>
            <input className="field" type="password" placeholder="Minimum 8 characters" value={form.password} required minLength={8}
              onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))} />
          </div>
          {error && <p className="alert-error">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn btn-neutral" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "Creating…" : "Create account"}</button>
          </div>
        </form>
      )}
    </Modal>
  );
}

export default function UserManagementSection() {
  const { user: me } = useAuth();
  const admin = Boolean(me?.is_admin);
  const [users, setUsers] = useState(null);
  const [dialog, setDialog] = useState(null); // { kind, user? }
  const [error, setError] = useState("");

  async function loadUsers() {
    if (!admin) { setUsers(me ? [me] : []); return; }
    try { setUsers(await api.listUsers()); } catch { setUsers(me ? [me] : []); }
  }

  useEffect(() => { loadUsers(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [admin, me?.id]);

  async function act(fn) {
    setError("");
    try { await fn(); await loadUsers(); } catch (err) { setError(err.message ?? "That did not work."); }
    setDialog(null);
  }

  return (
    <Section title="User management">
      {users === null ? (
        <div className="flex justify-center py-3.5"><div className="spinner" /></div>
      ) : (
        <div className="space-y-2">
          {users.map((u) => {
            const self = u.id === me?.id;
            return (
              <div key={u.id} className="flex flex-wrap items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800 px-2.5 py-1.5">
                <div className="min-w-0 flex-1">
                  <span className="text-sm font-medium text-slate-800 dark:text-slate-200">{u.username}</span>
                  <span className="ml-2 text-xs text-slate-400">{u.name}</span>
                  {u.is_admin && <span className="ml-2 badge">admin</span>}
                  {self && <span className="ml-2 text-xs text-slate-400">(you)</span>}
                </div>
                {self ? (
                  <button type="button" className="btn btn-tonal btn-sm" onClick={() => setDialog({ kind: "own" })}>
                    Change password
                  </button>
                ) : admin && (
                  <>
                    <button type="button" className="btn btn-tonal btn-sm" onClick={() => setDialog({ kind: "set", user: u })}>
                      Set password
                    </button>
                    <button type="button" className="btn btn-tonal btn-sm" onClick={() => setDialog({ kind: "admin", user: u })}>
                      {u.is_admin ? "Revoke admin" : "Make admin"}
                    </button>
                    <button type="button" className="btn btn-danger btn-sm" onClick={() => setDialog({ kind: "remove", user: u })}>
                      Remove
                    </button>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
      {error && <p className="alert-error">{error}</p>}
      {admin && (
        <div className="flex justify-end">
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setDialog({ kind: "add" })}>
            Add user
          </button>
        </div>
      )}

      {dialog?.kind === "own" && <ChangeOwnPasswordDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === "set" && <AdminSetPasswordDialog user={dialog.user} onClose={() => setDialog(null)} />}
      {dialog?.kind === "add" && <AddUserDialog onClose={() => setDialog(null)} onCreated={loadUsers} />}
      {dialog?.kind === "admin" && (
        <ConfirmDialog
          title={dialog.user.is_admin ? "Revoke admin" : "Make admin"}
          message={`${dialog.user.is_admin ? "Revoke admin from" : "Make"} ${dialog.user.username}${dialog.user.is_admin ? "" : " an admin"}?`}
          onConfirm={() => act(() => api.toggleAdmin(dialog.user.id))}
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "remove" && (
        <ConfirmDialog
          title="Remove user"
          danger
          confirmLabel="Remove"
          message={`Delete ${dialog.user.username}'s account and everything in it? This cannot be undone.`}
          onConfirm={() => act(() => api.deleteUser(dialog.user.id))}
          onCancel={() => setDialog(null)}
        />
      )}
    </Section>
  );
}
