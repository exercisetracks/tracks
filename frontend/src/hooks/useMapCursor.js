// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useRef, useCallback } from "react";

export function useMapCursor(routeRef) {
  const trackRef = useRef([]);

  const onHover = useCallback((payload) => {
    const elapsed = payload?.activeLabel;
    if (elapsed == null || !routeRef.current) return;
    const track = trackRef.current;
    if (!track.length) return;

    let lo = 0, hi = track.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (track[mid].elapsed < elapsed) lo = mid + 1;
      else hi = mid;
    }
    if (lo > 0 && Math.abs(track[lo - 1].elapsed - elapsed) < Math.abs(track[lo].elapsed - elapsed)) {
      lo--;
    }
    const pt = track[lo];
    if (pt?.lat && pt?.lng) routeRef.current.setCursor([pt.lat, pt.lng]);
  }, [routeRef]);

  const onLeave = useCallback(() => {
    routeRef.current?.setCursor(null);
  }, [routeRef]);

  return { trackRef, onHover, onLeave };
}
