// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";

// Selection + hover for custom tracks, mirroring useRouteHover's feature-state
// approach. Clicking the wide invisible hit line selects that track (→ MapView
// opens the detail + elevation overlay); clicking anywhere else clears the
// selection so the overlay auto-closes. Hover lights the track white.
const HIT = "custom_tracks_hit";
const SRC = { source: "custom_tracks" };

export function useCourseSelection(map, ready, enabled, selectedId, onSelect) {
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  // Apply the `selected` feature-state when the selection changes.
  const prevSel = useRef(null);
  useEffect(() => {
    if (!ready || !map) return;
    if (prevSel.current != null && prevSel.current !== selectedId) {
      try { map.setFeatureState({ ...SRC, id: prevSel.current }, { selected: false }); } catch { /* gone */ }
    }
    if (selectedId != null) {
      try { map.setFeatureState({ ...SRC, id: selectedId }, { selected: true }); } catch { /* not ready */ }
    }
    prevSel.current = selectedId;
  }, [map, ready, selectedId]);

  // Hover (layer-specific) + click (general, so a click off any track deselects).
  useEffect(() => {
    if (!ready || !map) return;
    let hovered = null;
    const hasLayer = () => { try { return !!map.getLayer(HIT); } catch { return false; } };

    const clearHover = () => {
      if (hovered === null) return;
      try { map.setFeatureState({ ...SRC, id: hovered }, { hover: false }); } catch { /* gone */ }
      hovered = null;
      map.getCanvas().style.cursor = "";
    };
    const onMove = (e) => {
      const f = e.features && e.features[0];
      const id = f && f.id != null ? f.id : null;
      map.getCanvas().style.cursor = id != null ? "pointer" : "";
      if (id === hovered) return;
      clearHover();
      if (id != null) {
        hovered = id;
        try { map.setFeatureState({ ...SRC, id }, { hover: true }); } catch { /* not ready */ }
      }
    };
    const onClick = (e) => {
      // Ctrl/⌘-click is reserved for merge multi-select (useMergeSelect) — don't
      // also open the single-track detail on a modifier click.
      const oe = e.originalEvent;
      if (oe && (oe.ctrlKey || oe.metaKey)) return;
      if (!enabledRef.current || !hasLayer()) return;
      const box = [[e.point.x - 7, e.point.y - 7], [e.point.x + 7, e.point.y + 7]];
      let feats = [];
      try { feats = map.queryRenderedFeatures(box, { layers: [HIT] }); } catch { feats = []; }
      const raw = feats.length ? (feats[0].id ?? feats[0].properties?.id) : null;
      onSelectRef.current(raw != null ? Number(raw) : null);
    };

    if (hasLayer()) {
      map.on("mousemove", HIT, onMove);
      map.on("mouseleave", HIT, clearHover);
    }
    map.on("click", onClick);
    return () => {
      try { map.off("mousemove", HIT, onMove); map.off("mouseleave", HIT, clearHover); } catch { /* ignore */ }
      map.off("click", onClick);
      clearHover();
    };
  }, [map, ready]);
}
