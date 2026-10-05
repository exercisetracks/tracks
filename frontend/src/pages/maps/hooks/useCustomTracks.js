// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";

// Owns the custom-tracks data: feeds the `custom_tracks` geojson source for the
// map layer AND exposes the summary list for the manager/detail UI. refresh() is
// called after any create/edit/delete so map + UI stay in sync. The last geojson
// is re-fed on style reloads (which reset geojson sources to empty).
export function useCustomTracks(map, ready) {
  const [tracks, setTracks] = useState([]);
  const [folders, setFolders] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const lastGeojson = useRef(null);
  const reqRef = useRef(0);

  const feed = useCallback((geojson) => {
    lastGeojson.current = geojson;
    if (!map) return;
    try {
      const src = map.getSource("custom_tracks");
      if (src) src.setData(geojson);
    } catch { /* source not ready */ }
  }, [map]);

  const refresh = useCallback(async () => {
    const id = ++reqRef.current;
    try {
      const [list, gj, folderList] = await Promise.all([
        api.getCourses(), api.getCoursesGeojson(), api.getCourseFolders().catch(() => []),
      ]);
      if (id !== reqRef.current) return;
      setTracks(list);
      setFolders(folderList);
      setLoaded(true);
      feed(gj);
    } catch { /* ignore */ }
  }, [feed]);

  useEffect(() => {
    if (ready && map) refresh();
  }, [ready, map, refresh]);

  // Re-feed the source after a style reload dropped its data.
  useEffect(() => {
    if (!ready || !map) return;
    const onStyle = () => { if (lastGeojson.current) feed(lastGeojson.current); };
    map.on("styledata", onStyle);
    return () => map.off("styledata", onStyle);
  }, [map, ready, feed]);

  return { tracks, folders, loaded, refresh };
}
