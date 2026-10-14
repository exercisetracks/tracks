// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Enter your password to unlock your data" — the vault_locked state the
// server has always described (services/crypto_context.py) and the web app
// never had.
//
// A login lasts 30 days; the key that decrypts the account's data is cached
// for a shorter, sliding week, and only in memory — a restart of the server,
// which every update is, drops it. Until now that left the web app signed in
// with everything encrypted failing quietly: activity details that never
// loaded, a heatmap with "No GPS data". The phone re-opens its own with an
// enrolled device key; a browser has only the password, so it asks for it.
//
// Logging in again is the unlock — it caches a fresh key under a new session
// — and the page reloads, so everything that failed asks again.
import { useEffect, useState } from "react";
import { api, TOKEN_KEY, VAULT_LOCKED_EVENT } from "../api/client";
import { useAuth } from "./AuthContext";
import Modal from "../components/ui/Modal";

export default function VaultUnlock() {
  const { authStatus, user, logout } = useAuth();
  const [locked, setLocked] = useState(false);
  const [password, setPassword] = useState("");
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    const onLocked = () => setLocked(true);
    window.addEventListener(VAULT_LOCKED_EVENT, onLocked);
    return () => window.removeEventListener(VAULT_LOCKED_EVENT, onLocked);
  }, []);

  if (!locked || authStatus !== "authed" || !user) return null;

  const unlock = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const data = await api.login(user.username || username.trim(), password);
      localStorage.setItem(TOKEN_KEY, data.access_token);
      api.clearCache();
      window.location.reload();
    } catch (err) {
      setError(err.status === 401 ? "That password is not right." : (err.message || "Could not unlock"));
      setBusy(false);
    }
  };

  return (
    <Modal title="Unlock your data" width="max-w-sm" label="Unlock your data">
      <form onSubmit={unlock} className="space-y-3">
        <p className="text-sm text-slate-600 dark:text-slate-300">
          The server has restarted or a week has passed, so the key to your encrypted data — activities,
          tracks, health — needs your password again. You are still signed in.
        </p>
        {!user.username && (
          <label className="block">
            <span className="field-label">Username</span>
            <input className="field" value={username} autoComplete="username" onChange={(e) => setUsername(e.target.value)} />
          </label>
        )}
        <label className="block">
          <span className="field-label">{user.username ? `Password for ${user.username}` : "Password"}</span>
          <input type="password" autoFocus className="field" value={password}
                 autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <p className="alert-error">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-neutral" onClick={logout}>Sign out</button>
          <button type="submit" className="btn btn-primary" disabled={busy || !password}>{busy ? "Unlocking…" : "Unlock"}</button>
        </div>
      </form>
    </Modal>
  );
}
