// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Keep the screen awake during a guided session. Gracefully no-ops where the
// Screen Wake Lock API is unavailable (e.g. Firefox, insecure origins) and
// re-acquires the lock when the tab becomes visible again.
import { useEffect, useRef } from "react";

export function useWakeLock(active) {
  const lockRef = useRef(null);

  useEffect(() => {
    if (!active || !("wakeLock" in navigator)) return undefined;
    let cancelled = false;

    const acquire = async () => {
      try {
        lockRef.current = await navigator.wakeLock.request("screen");
      } catch {
        /* denied / unsupported — silently ignore */
      }
    };
    const onVisible = () => {
      if (document.visibilityState === "visible" && !cancelled) acquire();
    };

    acquire();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisible);
      lockRef.current?.release?.().catch(() => {});
      lockRef.current = null;
    };
  }, [active]);
}
