// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";
import { ensurePlaceImages, isPin, loadAtlas, placeImageId } from "../utils/placeIcons";

const EMPTY = { type: "FeatureCollection", features: [] };

// Owns the user's tracks, saved places and collections: feeds the
// `custom_tracks` and `saved_places` sources for the map, and exposes the lists
// for the library, editor and detail panels. refresh() is called after any
// change so map and panels stay in step. The last data is re-fed after a style
// reload, which empties geojson sources and drops added images.
//
// `setEditing` takes the item open in the editor off the map: the editor draws
// it itself, as handles or a draggable mark, and the saved copy underneath
// would read as a second one that did not move.
export function useMapItems(map, ready) {
  const [tracks, setTracks] = useState([]);
  const [places, setPlaces] = useState([]);
  const [folders, setFolders] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [atlas, setAtlas] = useState(null);
  const data = useRef({ tracks: null, places: [] });
  const editing = useRef({ track: null, place: null });
  const atlasRef = useRef(null);
  const reqRef = useRef(0);

  useEffect(() => {
    let live = true;
    loadAtlas().then((a) => { if (live) { atlasRef.current = a; setAtlas(a); feed(); } });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const feed = useCallback(() => {
    if (!map) return;
    const { tracks: gj, places: list } = data.current;
    const hideTrack = editing.current.track;
    const hidePlace = editing.current.place;
    try {
      if (gj) {
        map.getSource("custom_tracks")?.setData(hideTrack == null ? gj
          : { ...gj, features: gj.features.filter((f) => (f.id ?? f.properties?.id) !== hideTrack) });
      }
      const shown = list.filter((p) => p.id !== hidePlace);
      ensurePlaceImages(map, shown, atlasRef.current);
      map.getSource("saved_places")?.setData(shown.length ? {
        type: "FeatureCollection",
        features: shown.map((p) => {
          const image = placeImageId(p.icon, p.color, atlasRef.current);
          return {
            type: "Feature", id: p.id,
            geometry: { type: "Point", coordinates: [p.lng, p.lat] },
            properties: { id: p.id, name: p.name, image, pin: isPin(image) },
          };
        }),
      } : EMPTY);
    } catch { /* sources not ready; styledata re-feeds */ }
  }, [map]);

  const refresh = useCallback(async () => {
    const id = ++reqRef.current;
    try {
      const [list, gj, folderList, placeList] = await Promise.all([
        api.getCourses(), api.getCoursesGeojson(),
        api.getCourseFolders().catch(() => []), api.getWaypoints().catch(() => []),
      ]);
      if (id !== reqRef.current) return;
      setTracks(list);
      setFolders(folderList);
      setPlaces(placeList);
      setLoaded(true);
      data.current = { tracks: gj, places: placeList };
      feed();
    } catch { /* ignore */ }
  }, [feed]);

  const setEditing = useCallback((which) => {
    editing.current = { track: which?.track ?? null, place: which?.place ?? null };
    feed();
  }, [feed]);

  useEffect(() => {
    if (ready && map) refresh();
  }, [ready, map, refresh]);

  // Re-feed the sources (and images) after a style reload dropped them.
  useEffect(() => {
    if (!ready || !map) return undefined;
    const onStyle = () => feed();
    map.on("styledata", onStyle);
    return () => map.off("styledata", onStyle);
  }, [map, ready, feed]);

  return { tracks, places, folders, loaded, atlas, refresh, setEditing };
}
