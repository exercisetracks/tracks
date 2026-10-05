// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";

// Single-click selection for recorded activity lines (the activity_tracks layer).
// A plain click selects the activity under the cursor (→ MapView opens the activity
// detail panel + amber highlight); a click that hits nothing deselects. Modifier
// clicks and merge mode are left to useMergeSelect, and a custom track under the
// cursor wins (useCourseSelection handles that). onSelect receives {id,name,dist}
// or null.
const ACT = "activity_tracks_hit";
const TRACK = "custom_tracks_hit";
const BOX = 7;

export function useActivitySelect(map, ready, enabled, onSelect) {
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    if (!ready || !map) return undefined;

    const hits = (pt, layer) => {
      try {
        if (!map.getLayer(layer)) return [];
        const box = [[pt.x - BOX, pt.y - BOX], [pt.x + BOX, pt.y + BOX]];
        return map.queryRenderedFeatures(box, { layers: [layer] });
      } catch { return []; }
    };

    const onClick = (e) => {
      const oe = e.originalEvent;
      if (oe && (oe.ctrlKey || oe.metaKey)) return;   // merge multi-select
      if (!enabledRef.current) return;
      if (hits(e.point, TRACK).length > 0) return;     // a custom track click wins

      const f = hits(e.point, ACT)[0];
      if (!f) { onSelectRef.current(null); return; }   // clicked off → deselect
      const id = f.id != null ? f.id : f.properties?.id;
      if (id != null) onSelectRef.current({ id: Number(id), name: f.properties?.name, dist: f.properties?.distance_m });
    };

    map.on("click", onClick);
    return () => map.off("click", onClick);
  }, [map, ready]);
}
