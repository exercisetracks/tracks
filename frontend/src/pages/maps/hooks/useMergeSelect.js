// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";

// Map multi-select for merging. A click toggles the track/activity under the
// cursor into the merge set when EITHER merge mode is active (the Merge control
// button is on) OR a Ctrl/⌘ modifier is held (works anytime). Custom tracks win
// over activities when both lie under the click. `onToggle` receives
// {kind:"track"|"activity", id, name, modifier} — MapView owns the ordered set.
const HIT = { track: "custom_tracks_hit", activity: "activity_tracks_hit" };
const BOX = 7; // px tolerance around the click

export function useMergeSelect(map, ready, active, onToggle, onEmpty) {
  const activeRef = useRef(active);
  activeRef.current = active;
  const onToggleRef = useRef(onToggle);
  onToggleRef.current = onToggle;
  const onEmptyRef = useRef(onEmpty);
  onEmptyRef.current = onEmpty;

  useEffect(() => {
    if (!ready || !map) return;

    const queryHit = (pt, layer) => {
      try {
        if (!map.getLayer(layer)) return null;
        const box = [[pt.x - BOX, pt.y - BOX], [pt.x + BOX, pt.y + BOX]];
        const f = map.queryRenderedFeatures(box, { layers: [layer] })[0];
        if (!f) return null;
        const id = f.id != null ? f.id : f.properties?.id;
        return id != null ? { id: Number(id), name: f.properties?.name, dist: f.properties?.distance_m } : null;
      } catch { return null; }
    };

    const onClick = (e) => {
      const oe = e.originalEvent;
      const modifier = !!(oe && (oe.ctrlKey || oe.metaKey));
      if (!activeRef.current && !modifier) return;

      const track = queryHit(e.point, HIT.track);
      const hit = track
        ? { kind: "track", ...track }
        : (() => { const a = queryHit(e.point, HIT.activity); return a ? { kind: "activity", ...a } : null; })();
      if (hit) {
        oe?.preventDefault?.();
        onToggleRef.current?.({ ...hit, modifier });
      } else if (activeRef.current) {
        // Empty click while merge mode is open (no modifier path) — let MapView
        // decide whether to dismiss the panel.
        onEmptyRef.current?.();
      }
    };

    map.on("click", onClick);
    return () => map.off("click", onClick);
  }, [map, ready]);
}
