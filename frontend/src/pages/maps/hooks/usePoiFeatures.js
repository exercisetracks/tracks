// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";
import { rankPoiLabels } from "../utils/labelRank";

const EMPTY_FC = { type: "FeatureCollection", features: [] };
const FETCH_DEBOUNCE_MS = 250;

export function usePoiFeatures(map, ready) {
  const timerRef = useRef(null);
  // Guards against a slow request landing after a newer one — without this, a
  // fast pan-pan-pan can have an earlier (larger bbox / different area) fetch
  // resolve last and stomp the newer, more relevant result with stale data.
  const requestIdRef = useRef(0);
  const abortRef = useRef(null);

  const fetchAndUpdate = useCallback(async () => {
    if (!map) return;
    const zoom = Math.floor(map.getZoom());
    const b = map.getBounds();
    const bbox = `${b.getWest()},${b.getSouth()},${b.getEast()},${b.getNorth()}`;
    const requestId = ++requestIdRef.current;
    // A superseded in-flight request is pure waste (its response would be
    // dropped by the requestId guard anyway) — free its connection for the
    // fetch that matters. The guard stays as the belt to this abort's braces.
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const data = await api.getPoiFeatures(bbox, zoom, { signal: controller.signal });
      if (requestId !== requestIdRef.current) return; // superseded by a newer fetch
      const source = map.getSource("poi_features");
      // Bake deterministic icon_zoom/label_zoom visibility grades into the
      // features before they hit the map — see utils/labelRank.js.
      if (source && data) source.setData(rankPoiLabels(data));
    } catch {
      // Non-fatal (includes AbortError) — stale data stays on screen
    }
  }, [map]);

  useEffect(() => {
    if (!ready || !map) return;

    // Fetch immediately on first load
    fetchAndUpdate();

    const debouncedFetch = () => {
      clearTimeout(timerRef.current);
      timerRef.current = setTimeout(fetchAndUpdate, FETCH_DEBOUNCE_MS);
    };

    map.on("moveend", debouncedFetch);
    map.on("zoomend", debouncedFetch);

    return () => {
      clearTimeout(timerRef.current);
      abortRef.current?.abort();
      map.off("moveend", debouncedFetch);
      map.off("zoomend", debouncedFetch);
    };
  }, [ready, map, fetchAndUpdate]);
}
