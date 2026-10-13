// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// What a GPX file holds, read in the browser before anything is uploaded, so a
// file is filed as what it is: a line is a track, and a point is a place.
//
// The server's importer only knows tracks, and a GPX of one point — the usual
// way a single spot is shared — was refused as "no track". Classified here:
//   • two or more track (or, failing those, route) points → a track, which
//     the server imports as before;
//   • otherwise its waypoints, each a place;
//   • otherwise a lone track or route point, as a place named after the file.
// A file with a track *and* waypoints is a track: the waypoints of a route
// file are usually its own turns and stops, and filing them as places would
// scatter a dozen pins nobody asked for.

const num = (v) => (v == null || v === "" ? null : Number.isFinite(Number(v)) ? Number(v) : null);

function pointOf(el) {
  const lat = num(el.getAttribute("lat"));
  const lng = num(el.getAttribute("lon"));
  if (lat == null || lng == null) return null;
  const child = (tag) => [...el.children].find((c) => c.localName === tag)?.textContent?.trim() || null;
  return { lat, lng, ele_m: num(child("ele")), name: child("name") };
}

const all = (doc, tag) => [...doc.getElementsByTagNameNS("*", tag)];

/** `{ kind: "track" } | { kind: "places", places } | { kind: "empty" }` for a GPX file's text. */
export function classifyGpx(text, filename = "") {
  let doc;
  try { doc = new DOMParser().parseFromString(text, "application/xml"); } catch { return { kind: "empty" }; }
  if (!doc || doc.getElementsByTagName("parsererror").length) return { kind: "empty" };
  const trk = all(doc, "trkpt").map(pointOf).filter(Boolean);
  const rte = all(doc, "rtept").map(pointOf).filter(Boolean);
  if (trk.length >= 2 || rte.length >= 2) return { kind: "track" };

  const base = filename.replace(/\.[^.]+$/, "") || "Place";
  const wpts = all(doc, "wpt").map(pointOf).filter(Boolean);
  if (wpts.length) {
    return { kind: "places", places: wpts.map((w, i) => ({ ...w, name: w.name || (wpts.length > 1 ? `${base} ${i + 1}` : base) })) };
  }
  const lone = trk[0] || rte[0];
  if (lone) {
    // The point itself rarely has a name; its track or route usually does.
    const owner = [...all(doc, "trk"), ...all(doc, "rte")][0];
    const ownerName = owner && [...owner.children].find((c) => c.localName === "name")?.textContent?.trim();
    return { kind: "places", places: [{ ...lone, name: lone.name || ownerName || base }] };
  }
  return { kind: "empty" };
}
