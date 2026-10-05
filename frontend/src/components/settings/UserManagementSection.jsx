// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// User management section (admin only): list users, create accounts, toggle
// admin, and remove users. The current user can't act on their own row.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { INPUT, Section } from "./primitives";

export default function UserManagementSection() {
  const { user: currentUser } = useAuth();
  const [users,    setUsers]   = useState([]);
  const [loading,  setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error,    setError]   = useState("");
  const [form,     setForm]    = useState({ username: "", name: "", password: "" });
  // Set right after a successful create — the API returns the new
  // account's recovery key exactly once (see backend app/api/users.py).
  // The admin already knows the password they just chose, so this is
  // nothing new to them, but it's the only chance to hand the key itself
  // to the new user — it's never retrievable again after this response.
  const [newAccountKey, setNewAccountKey] = useState(null);
  const [copied, setCopied] = useState(false);

  async function loadUsers() {
    try {
      const list = await api.listUsers();
      setUsers(list);
    } catch { /* ignore */ }
    finally { setLoading(false); }
  }

  useEffect(() => { loadUsers(); }, []);

  async function handleCreate(e) {
    e.preventDefault();
    setError("");
    setCreating(true);
    try {
      const created = await api.createUser(form);
      setNewAccountKey({ username: created.username, key: created.recovery_key });
      setForm({ username: "", name: "", password: "" });
      await loadUsers();
    } catch (err) {
      setError(err.message ?? "Failed to create user");
    } finally {
      setCreating(false);
    }
  }

  async function handleDelete(id, username) {
    if (!confirm(`Delete user "${username}"? This cannot be undone.`)) return;
    try {
      await api.deleteUser(id);
      await loadUsers();
    } catch (err) {
      alert(err.message ?? "Failed to delete user");
    }
  }

  async function handleToggleAdmin(id, username, currentlyAdmin) {
    const action = currentlyAdmin ? "revoke admin from" : "make admin";
    if (!confirm(`Are you sure you want to ${action} "${username}"?`)) return;
    try {
      await api.toggleAdmin(id);
      await loadUsers();
    } catch (err) {
      alert(err.message ?? "Failed to update admin status");
    }
  }

  return (
    <Section title="User Management">
      {newAccountKey && (
        <div className="mb-3.5 rounded-lg border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-3">
          <p className="text-sm text-amber-800 dark:text-amber-300">
            Recovery key for <strong>{newAccountKey.username}</strong> — hand this to them along with
            their password. It won't be shown again, and without it you can't recover their data if
            they forget their password.
          </p>
          <div className="mt-2 font-mono text-xs leading-relaxed break-words bg-white dark:bg-slate-900 border border-amber-200 dark:border-amber-800 rounded px-2 py-1.5 text-slate-900 dark:text-white select-all">
            {newAccountKey.key}
          </div>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => {
                navigator.clipboard?.writeText(newAccountKey.key).then(() => {
                  setCopied(true);
                  setTimeout(() => setCopied(false), 2000);
                });
              }}
              className="text-xs font-medium text-amber-800 dark:text-amber-300 hover:underline"
            >
              {copied ? "Copied!" : "Copy"}
            </button>
            <button
              type="button"
              onClick={() => setNewAccountKey(null)}
              className="btn btn-neutral btn-sm ml-auto"
            >
              Done
            </button>
          </div>
        </div>
      )}
      {loading ? (
        <div className="flex justify-center py-3.5">
          <div className="w-5 h-5 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
        </div>
      ) : (
        <div className="space-y-3">
          {users.map(u => (
            <div key={u.id} className="flex items-center justify-between rounded-lg bg-slate-50 dark:bg-slate-800 px-2.5 py-1.5">
              <div>
                <span className="text-sm font-medium text-slate-800 dark:text-slate-200">{u.username}</span>
                <span className="ml-2 text-xs text-slate-400">{u.name}</span>
                {u.is_admin && (
                  <span className="ml-2 text-xs font-semibold text-accent-600 dark:text-accent-400 bg-accent-50 dark:bg-accent-900/30 rounded px-1 py-0.5">admin</span>
                )}
              </div>
              {u.id !== currentUser?.id && (
                <div className="flex items-center gap-3">
                  <button
                    onClick={() => handleToggleAdmin(u.id, u.username, u.is_admin)}
                    className="btn btn-tonal btn-sm"
                  >
                    {u.is_admin ? "Revoke admin" : "Make admin"}
                  </button>
                  <button
                    onClick={() => handleDelete(u.id, u.username)}
                    className="btn btn-danger btn-sm"
                  >
                    Remove
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="border-t border-slate-100 dark:border-slate-800 pt-3.5">
        <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-3">Add user</p>
        {error && (
          <p className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-2.5 py-1.5 mb-3">{error}</p>
        )}
        <form onSubmit={handleCreate} className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Username</label>
              <input
                className={INPUT}
                type="text"
                placeholder="e.g. jane"
                value={form.username}
                onChange={e => setForm(f => ({ ...f, username: e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, "") }))}
                required
                minLength={3}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Display name</label>
              <input
                className={INPUT}
                type="text"
                placeholder="e.g. Jane"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                required
              />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Password</label>
            <input
              className={INPUT}
              type="password"
              placeholder="Minimum 8 characters"
              value={form.password}
              onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
              required
              minLength={8}
            />
          </div>
          <button
            type="submit"
            disabled={creating}
            className="btn btn-primary w-full"
          >
            {creating ? "Creating…" : "Create account"}
          </button>
        </form>
      </div>
    </Section>
  );
}
