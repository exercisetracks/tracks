// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Tour state + account-synced persistence for the onboarding tutorial.
//
// Holds which tour is active, the current step, and the user's `seen`/`enabled`
// flags. Flags hydrate once from `/users/me/settings` and are written back with
// `api.updateSettings` (fire-and-forget, optimistic) so the tutorial's progress
// follows the account across devices — mirroring how the rest of Settings saves.
import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import { TOURS } from "./tours";

const TourContext = createContext(null);

export function TourProvider({ children }) {
  const [activeTour, setActiveTour] = useState(null);
  const [stepIndex, setStepIndex]   = useState(0);
  const [seen, setSeen]             = useState({});
  const [enabled, setEnabled]       = useState(true);
  const [hydrated, setHydrated]     = useState(false);

  // Hydrate the seen/enabled flags once from the user's settings.
  useEffect(() => {
    let cancelled = false;
    api.getSettings()
      .then((s) => {
        if (cancelled) return;
        setSeen(s?.tour_seen && typeof s.tour_seen === "object" ? s.tour_seen : {});
        setEnabled(s?.tour_enabled !== false);
      })
      .catch(() => { /* degrade to defaults (empty seen, enabled) */ })
      .finally(() => { if (!cancelled) setHydrated(true); });
    return () => { cancelled = true; };
  }, []);

  // Persist the current flags to the account. Optimistic: callers update local
  // state first, then hand the next values here. Failures are non-fatal.
  const persist = useCallback((nextSeen, nextEnabled) => {
    api.updateSettings({ tour_seen: nextSeen, tour_enabled: nextEnabled }).catch(() => {});
  }, []);

  const startTour = useCallback((id) => {
    if (!TOURS[id]?.length) return;
    setActiveTour(id);
    setStepIndex(0);
  }, []);

  // Finish a tour (Done or Skip): remember it so it won't auto-fire again.
  const completeTour = useCallback(() => {
    setActiveTour((cur) => {
      if (cur) {
        setSeen((prevSeen) => {
          const nextSeen = { ...prevSeen, [cur]: true };
          persist(nextSeen, enabled);
          return nextSeen;
        });
      }
      return null;
    });
    setStepIndex(0);
  }, [enabled, persist]);

  // Drop the active tour WITHOUT marking it seen (e.g. the user navigated away
  // mid-tour) so it can still appear next time they land on that page.
  const abortTour = useCallback(() => {
    setActiveTour(null);
    setStepIndex(0);
  }, []);

  const next = useCallback(() => {
    setStepIndex((i) => {
      const steps = TOURS[activeTour] ?? [];
      if (i >= steps.length - 1) { completeTour(); return 0; }
      return i + 1;
    });
  }, [activeTour, completeTour]);

  const prev = useCallback(() => {
    setStepIndex((i) => Math.max(0, i - 1));
  }, []);

  const setToursEnabled = useCallback((value) => {
    setEnabled(value);
    setSeen((prevSeen) => { persist(prevSeen, value); return prevSeen; });
    if (!value) { setActiveTour(null); setStepIndex(0); }
  }, [persist]);

  // "Restart tutorial": forget every seen tour and re-enable tips. The caller is
  // responsible for navigating to the Dashboard so the first tour fires.
  const restartTutorial = useCallback(() => {
    setSeen({});
    setEnabled(true);
    setActiveTour(null);
    setStepIndex(0);
    persist({}, true);
  }, [persist]);

  const steps = TOURS[activeTour] ?? [];
  const value = {
    activeTour, stepIndex, seen, enabled, hydrated,
    steps, step: steps[stepIndex] ?? null, stepCount: steps.length,
    startTour, next, prev, completeTour, abortTour, setToursEnabled, restartTutorial,
  };

  return <TourContext.Provider value={value}>{children}</TourContext.Provider>;
}

export function useTour() {
  const ctx = useContext(TourContext);
  if (!ctx) throw new Error("useTour must be used within a TourProvider");
  return ctx;
}
