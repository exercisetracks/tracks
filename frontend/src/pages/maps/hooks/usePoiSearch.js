// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect } from "react";
import MaplibreGeocoder from "@maplibre/maplibre-gl-geocoder";
import "@maplibre/maplibre-gl-geocoder/dist/maplibre-gl-geocoder.css";
import maplibregl from "../../../lib/maplibre";
import { api } from "../../../api/client";

function getBbox(map) {
  const b = map.getBounds();
  return `${b.getWest()},${b.getSouth()},${b.getEast()},${b.getNorth()}`;
}

async function searchPoi(map, query, limit) {
  try {
    const bbox = getBbox(map);
    const data = await api.searchPoi(query, limit, bbox);
    if (data?.type === "FeatureCollection" && Array.isArray(data.features)) {
      return data;
    }
    return { type: "FeatureCollection", features: [] };
  } catch {
    return { type: "FeatureCollection", features: [] };
  }
}

export function usePoiSearch(map, ready) {
  useEffect(() => {
    if (!ready || !map) return;

    const geocoder = new MaplibreGeocoder(
      {
        // Suggestions: lightweight, small limit
        getSuggestions: (config) => searchPoi(map, config.query, 5),
        // Forward geocode: full result, larger limit
        forwardGeocode: (config) => searchPoi(map, config.query, 10),
      },
      {
        maplibregl,
        marker: true,
        showResultMarkers: false,
        clearOnBlur: true,
        debounceSearch: 300,
        showResultsWhileTyping: true,
        minLength: 2,
      }
    );

    map.addControl(geocoder, "top-left");

    const input = geocoder._container?.querySelector("input");
    if (input) input.placeholder = "Search places…";

    return () => { try { map.removeControl(geocoder); } catch {} };
  }, [ready, map]);
}
