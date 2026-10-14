// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState } from "react";
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

const SRC = "search_results";
const DOT = "search_results_dot";
const LABEL = "search_results_label";
const EMPTY = { type: "FeatureCollection", features: [] };
/** The server's cap on one view's markers; reaching it means "zoom in for the rest". */
const CAP = 300;

// The search box (top-left). Names, coordinates and street corners go through
// the geocoder's own list as before. A *category* — "campgrounds",
// "restaurants", "gas stations" — submitted with Enter also marks every match
// in view on the map, the way a map search does, and the marks follow the
// map as it moves until the search is cleared or replaced.
//
// `onTyping` is told when the person starts typing, so the place-details
// panel, which sits right under the box, gets out of the way of the results.
export function usePoiSearch(map, ready, { onTyping } = {}) {
  const [marked, setMarked] = useState(null);   // { category, count } | null
  const onTypingRef = useRef(onTyping);
  onTypingRef.current = onTyping;

  useEffect(() => {
    if (!ready || !map) return undefined;
    let category = null;      // the query whose matches are marked
    let req = 0;
    let moveTimer = null;

    const ensureLayers = () => {
      try {
        if (!map.getSource(SRC)) map.addSource(SRC, { type: "geojson", data: EMPTY });
        if (!map.getLayer(DOT)) {
          map.addLayer({
            id: DOT, type: "circle", source: SRC,
            paint: {
              "circle-radius": 7, "circle-color": "#dc2626",
              "circle-stroke-color": "#ffffff", "circle-stroke-width": 2,
            },
          });
        }
        if (!map.getLayer(LABEL)) {
          map.addLayer({
            id: LABEL, type: "symbol", source: SRC,
            layout: {
              // Named explicitly: a font stack the glyph endpoint does not
              // serve renders no text at all.
              "text-font": ["Noto Sans Regular"],
              "text-field": ["get", "name"],
              "text-size": 11,
              "text-anchor": "top",
              "text-offset": [0, 0.8],
              "text-optional": true,
            },
            paint: { "text-color": "#991b1b", "text-halo-color": "#ffffff", "text-halo-width": 1.4 },
          });
        }
      } catch { /* style mid-reload; styledata re-adds */ }
    };

    const show = (fc) => { ensureLayers(); try { map.getSource(SRC)?.setData(fc); } catch { /* not ready */ } };

    const clear = () => {
      category = null;
      req++;
      show(EMPTY);
      setMarked(null);
    };

    // Ask for everything of the category in view; anything else clears the marks.
    const markAll = async (query) => {
      const id = ++req;
      let data;
      try { data = await api.searchPoi(query, CAP, getBbox(map), { inView: true }); } catch { return; }
      if (id !== req) return;
      if (!data?.category) { clear(); return; }
      category = query;
      show({ type: "FeatureCollection", features: data.features || [] });
      setMarked({ category: data.category, count: (data.features || []).length });
    };

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
    if (input) input.placeholder = "Search places, or “campgrounds”…";
    const onKey = (e) => {
      if (e.key === "Enter" && input.value.trim().length >= 2) markAll(input.value.trim());
    };
    const onInput = () => {
      onTypingRef.current?.();
      if (!input.value.trim()) clear();
    };
    input?.addEventListener("keydown", onKey);
    input?.addEventListener("input", onInput);
    geocoder.on("clear", clear);

    // The marks follow the map: a pan or zoom asks again for what is in view.
    const onMove = () => {
      if (!category) return;
      clearTimeout(moveTimer);
      moveTimer = setTimeout(() => markAll(category), 350);
    };
    map.on("moveend", onMove);
    map.on("styledata", ensureLayers);

    return () => {
      clearTimeout(moveTimer);
      input?.removeEventListener("keydown", onKey);
      input?.removeEventListener("input", onInput);
      map.off("moveend", onMove);
      map.off("styledata", ensureLayers);
      try { map.removeControl(geocoder); } catch { /* gone */ }
      for (const l of [LABEL, DOT]) { try { map.removeLayer(l); } catch { /* gone */ } }
      try { map.removeSource(SRC); } catch { /* gone */ }
    };
  }, [ready, map]);

  return { marked, capped: marked != null && marked.count >= CAP };
}
