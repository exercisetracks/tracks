// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Auto-launches a section's tour the first time the user lands on it. Mounted
// once inside the app shell (Layout). Waits for the flags to hydrate, respects
// the master "enabled" switch and per-tour "seen" flags, and gives the page a
// short beat to render its `data-tour` anchors before starting.
import { useEffect, useRef } from "react";
import { useLocation } from "react-router-dom";
import { useTour } from "./TourContext";
import { tourIdForPath } from "./tours";

export function useTourAutoStart() {
  const { pathname } = useLocation();
  const { hydrated, enabled, seen, activeTour, startTour, abortTour } = useTour();
  const timerRef = useRef(null);

  useEffect(() => {
    const tourId = tourIdForPath(pathname);

    // A tour is already active: if we've navigated off its page, drop it quietly
    // (not marked seen, so it can reappear). Either way, don't schedule another.
    if (activeTour) {
      if (activeTour !== tourId) abortTour();
      return;
    }

    clearTimeout(timerRef.current);
    if (!hydrated || !enabled || !tourId || seen[tourId]) return;
    timerRef.current = setTimeout(() => startTour(tourId), 500);
    return () => clearTimeout(timerRef.current);
  }, [pathname, hydrated, enabled, seen, activeTour, startTour, abortTour]);
}
