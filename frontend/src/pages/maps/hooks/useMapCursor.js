// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect } from "react";

const SRC = "profile_cursor";
const EMPTY = { type: "FeatureCollection", features: [] };

// A dot on the map at a point along a line — where the pointer is on that
// line's height profile, so a bump in the chart can be found on the ground.
// Returns `show([lng, lat] | null)`.
export function useMapCursor(map, ready) {
  const ensure = useCallback(() => {
    if (!map) return;
    try {
      if (!map.getSource(SRC)) map.addSource(SRC, { type: "geojson", data: EMPTY });
      if (!map.getLayer(SRC)) {
        map.addLayer({
          id: SRC, type: "circle", source: SRC,
          paint: {
            "circle-radius": 6, "circle-color": "#ffffff",
            "circle-stroke-color": "#0f172a", "circle-stroke-width": 2.5,
          },
        });
      }
    } catch { /* style mid-reload */ }
  }, [map]);

  useEffect(() => {
    if (!map || !ready) return undefined;
    ensure();
    map.on("styledata", ensure);
    return () => map.off("styledata", ensure);
  }, [map, ready, ensure]);

  return useCallback((point) => {
    try {
      map?.getSource(SRC)?.setData(point
        ? { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: point } }
        : EMPTY);
    } catch { /* not ready */ }
  }, [map]);
}
