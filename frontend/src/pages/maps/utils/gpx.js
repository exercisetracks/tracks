// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Escape XML-special characters so a route name containing & < > " ' (e.g.
// "Smith & Jones Trail") produces valid GPX rather than a malformed file Garmin
// and other tools reject. Coordinates are numeric, so only text needs escaping.
function _xmlEscape(s) {
  return String(s).replace(/[<>&'"]/g, (c) => (
    { "<": "&lt;", ">": "&gt;", "&": "&amp;", "'": "&apos;", '"': "&quot;" }[c]
  ));
}

export function formatGpx(geojson, name = "Route") {
  const coords = geojson?.features?.[0]?.geometry?.coordinates;
  if (!coords) return "";

  const trkpts = coords
    .map((c) => {
      const ele = c[2] != null ? `<ele>${c[2].toFixed(1)}</ele>` : "";
      return `<trkpt lat="${c[1]}" lon="${c[0]}">${ele}</trkpt>`;
    })
    .join("\n");

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Tracks"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${_xmlEscape(name)}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>`;
}
