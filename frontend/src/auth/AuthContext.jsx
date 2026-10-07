// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, TOKEN_KEY } from "../api/client";
import { setAccountZone } from "../lib/today";

const AuthContext = createContext(null);

// authStatus values:
//   "loading"           — initial checks in flight
//   "needs_setup"       — no admin account configured; redirect to /setup
//   "needs_onboarding"  — account exists but setup wizard not completed
//   "unauthenticated"   — account exists but no valid token; redirect to /login
//   "authed"            — valid JWT in localStorage, setup complete
export function AuthProvider({ children }) {
  const [authStatus, setAuthStatus] = useState("loading");
  const [user, setUser]             = useState(null);

  const checkAuth = useCallback(async () => {
    setAuthStatus("loading");

    // Phase 1: check whether any account is configured.
    let configured;
    try {
      ({ configured } = await api.getAuthStatus());
    } catch {
      setTimeout(checkAuth, 2000);
      return;
    }

    if (!configured) {
      setAuthStatus("needs_setup");
      return;
    }

    // Phase 2: account exists — validate the stored token.
    try {
      const u = await api.getMe();
      setUser(u);
      // Phase 3: check if the user completed the onboarding wizard.
      const s = await api.getSettings();
      // Every "today" on the web is counted in the account's zone (lib/today.js).
      setAccountZone(s.timezone);
      if (!s.setup_complete) {
        setAuthStatus("needs_onboarding");
        return;
      }
      setAuthStatus("authed");
    } catch {
      localStorage.removeItem(TOKEN_KEY);
      setUser(null);
      setAuthStatus("unauthenticated");
    }
  }, []);

  useEffect(() => { checkAuth(); }, [checkAuth]);

  // A sync agent's ingest isn't tied to any particular browser session, so a
  // user who stays logged in while their watch syncs would otherwise see
  // nothing new until they log out and back in (processing is normally
  // triggered by login — see backend app.api.auth._create_authenticated_token).
  // Poll while authenticated to close that gap; best-effort, errors ignored.
  useEffect(() => {
    if (authStatus !== "authed") return;
    api.syncPendingImports().catch(() => {});
    const id = setInterval(() => {
      api.syncPendingImports().catch(() => {});
    }, 90_000);
    return () => clearInterval(id);
  }, [authStatus]);

  const login = useCallback(async (username, password) => {
    const data = await api.login(username, password);
    localStorage.setItem(TOKEN_KEY, data.access_token);
    await checkAuth();
  }, [checkAuth]);

  const setup = useCallback(async (username, name, password) => {
    const data = await api.setup(username, name, password);
    localStorage.setItem(TOKEN_KEY, data.access_token);
    await checkAuth();
  }, [checkAuth]);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setUser(null);
    setAuthStatus("unauthenticated");
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      const u = await api.getMe();
      setUser(u);
    } catch {
      // ignore
    }
  }, []);

  // Immediately mark auth as complete — used after the onboarding wizard
  // finishes so the route guards see authed state before the next render.
  const completeOnboarding = useCallback(async () => {
    // Also fetch the user so downstream components like Settings see
    // user.is_admin and render admin-only UI correctly.
    try {
      const u = await api.getMe();
      setUser(u);
    } catch {}
    setAuthStatus("authed");
  }, []);

  return (
    <AuthContext.Provider value={{ authStatus, user, login, setup, logout, refreshUser, checkAuth, completeOnboarding }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
