// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";

// Selection + hover for the user's tracks and places, by feature-state.
// Clicking a place's mark or a track's wide invisible hit line selects it
// (→ MapView opens the detail panel); clicking anywhere else clears the
// selection so the panel closes. A place is tested first: it sits on top, and
// a place on a route is the thing somebody meant to click.
//
// `selected` and `onSelect` deal in `{ kind: "track" | "place", id }`.
const HIT = "custom_tracks_hit";
const PLACE_LAYERS = ["saved_places_icon", "saved_places_label"];
const SOURCE = { track: "custom_tracks", place: "saved_places" };

export function useCourseSelection(map, ready, enabled, selected, onSelect) {
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  // Apply the `selected` feature-state when the selection changes, and again
  // after a style reload resets feature-state.
  const prevSel = useRef(null);
  useEffect(() => {
    if (!ready || !map) return undefined;
    const apply = () => {
      const prev = prevSel.current;
      if (prev && (prev.kind !== selected?.kind || prev.id !== selected?.id)) {
        try { map.setFeatureState({ source: SOURCE[prev.kind], id: prev.id }, { selected: false }); } catch { /* gone */ }
      }
      if (selected) {
        try { map.setFeatureState({ source: SOURCE[selected.kind], id: selected.id }, { selected: true }); } catch { /* not ready */ }
      }
      prevSel.current = selected;
    };
    apply();
    map.on("styledata", apply);
    return () => map.off("styledata", apply);
  }, [map, ready, selected]);

  useEffect(() => {
    if (!ready || !map) return undefined;
    let hovered = null;   // { source, id }
    const has = (id) => { try { return !!map.getLayer(id); } catch { return false; } };

    const clearHover = () => {
      if (!hovered) return;
      try { map.setFeatureState(hovered, { hover: false }); } catch { /* gone */ }
      hovered = null;
      map.getCanvas().style.cursor = "";
    };
    const hoverOn = (source) => (e) => {
      if (!enabledRef.current) return;
      const f = e.features && e.features[0];
      const id = f && f.id != null ? f.id : null;
      map.getCanvas().style.cursor = id != null ? "pointer" : "";
      if (hovered && hovered.source === source && hovered.id === id) return;
      clearHover();
      if (id != null) {
        hovered = { source, id };
        try { map.setFeatureState(hovered, { hover: true }); } catch { /* not ready */ }
      }
    };
    const onTrackMove = hoverOn(SOURCE.track);
    const onPlaceMove = hoverOn(SOURCE.place);

    const onClick = (e) => {
      // Ctrl/⌘-click is reserved for merge multi-select (useMergeSelect).
      const oe = e.originalEvent;
      if (oe && (oe.ctrlKey || oe.metaKey)) return;
      if (!enabledRef.current) return;
      const box = [[e.point.x - 7, e.point.y - 7], [e.point.x + 7, e.point.y + 7]];
      const query = (layers) => {
        const live = layers.filter(has);
        if (!live.length) return null;
        try {
          const f = map.queryRenderedFeatures(box, { layers: live })[0];
          const raw = f ? (f.id ?? f.properties?.id) : null;
          return raw != null ? Number(raw) : null;
        } catch { return null; }
      };
      const place = query(PLACE_LAYERS);
      if (place != null) { onSelectRef.current({ kind: "place", id: place }); return; }
      const track = query([HIT]);
      onSelectRef.current(track != null ? { kind: "track", id: track } : null);
    };

    if (has(HIT)) {
      map.on("mousemove", HIT, onTrackMove);
      map.on("mouseleave", HIT, clearHover);
    }
    for (const l of PLACE_LAYERS) {
      if (!has(l)) continue;
      map.on("mousemove", l, onPlaceMove);
      map.on("mouseleave", l, clearHover);
    }
    map.on("click", onClick);
    return () => {
      try {
        map.off("mousemove", HIT, onTrackMove); map.off("mouseleave", HIT, clearHover);
        for (const l of PLACE_LAYERS) { map.off("mousemove", l, onPlaceMove); map.off("mouseleave", l, clearHover); }
      } catch { /* ignore */ }
      map.off("click", onClick);
      clearHover();
    };
  }, [map, ready]);
}
