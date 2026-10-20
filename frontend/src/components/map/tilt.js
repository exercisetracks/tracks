// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useCallback } from "react";

// The camera tilt shared by every map that carries MapToolbar, so its compass
// and 3D pip drive the dashboard heatmap exactly as they drive the map page.
// The map page's use3D adds terrain and level-of-detail on top of these.

// Pitch (camera tilt) applied when toggling 3D on via the pip.
export const PITCH_3D = 55;
// Above this pitch the map counts as "3D" — lights the control and turns on the
// terrain mesh. Small enough that a deliberate drag-tilt registers, large enough
// that incidental wobble at pitch 0 doesn't.
export const PITCH_THRESHOLD = 5;
// Hard cap on tilt: a draw distance. Past 60° the view reaches toward the
// horizon, where the terrain tile fan explodes — 75° was usable plugged in and
// barely movable on battery. The phone caps at the same (MapScreen MAX_PITCH).
export const MAX_PITCH = 60;

// MapToolbar's camera handlers for a map that tilts without terrain. The
// heatmap is this: its glow tracks are a custom WebGL layer drawn on a flat
// plane, so a terrain mesh would sink them into hills or float them over
// valleys. Its basemap carries a DEM for hillshade, so leaving it out here is
// a choice, not a missing source.
export function useFlatTilt(map) {
  const [is3D, setIs3D] = useState(false);

  // From the camera, not from the controls, so a touchpad tilt lights the pip
  // the same as the pip does.
  useEffect(() => {
    if (!map) return;
    const onPitch = () => setIs3D(map.getPitch() > PITCH_THRESHOLD);
    onPitch();
    map.on("pitch", onPitch);
    return () => map.off("pitch", onPitch);
  }, [map]);

  const toggle = useCallback(() => {
    if (!map) return;
    map.easeTo({ pitch: map.getPitch() > PITCH_THRESHOLD ? 0 : PITCH_3D, duration: 600 });
  }, [map]);

  // No easing: the camera tracks the compass drag.
  const orbitTo = useCallback(({ bearing, pitch }) => {
    if (!map) return;
    const opts = {};
    if (bearing != null) opts.bearing = bearing;
    if (pitch != null) opts.pitch = Math.max(0, Math.min(MAX_PITCH, pitch));
    try { map.jumpTo(opts); } catch {}
  }, [map]);

  return { is3D, toggle, orbitTo };
}
