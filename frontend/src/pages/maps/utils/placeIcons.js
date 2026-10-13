// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A saved place's symbol, in the colour its owner chose — the phone's
// WaypointIcons and WaypointPins, on a canvas.
//
// The map's sprites are ordinary images, not signed distance fields, so
// MapLibre cannot tint them with icon-color. A bitmap can be recoloured by
// anyone, though: the symbol is cut out of the atlas, filled with the place's
// colour (alpha kept, so a monochrome silhouette recolours cleanly), ringed in
// white so it reads on pale rock and dark forest alike, and registered under
// its own name. A place with no particular symbol ("marker") wears a pin
// instead, which stands on its point rather than centring on it.
//
// The same images go to the list rows and the symbol picker as data URLs, so
// a place in the library looks exactly like the mark it makes on the map.

const SPRITE = "/api/sprite/usgs@2x";

/** As tall as the pin's head, so a symbol and a pin read as the same weight of mark. CSS px. */
const ICON_PX = 22;
const HALO_PX = 1.4;
/** Eight stamps: at four the diagonals stay bare and the outline reads as a cross. */
const HALO_STAMPS = 8;
const PIN_W = 22, PIN_H = 30, PIN_STROKE = 2, SHOULDER = 0.7, HOLE = 0.34;

/** Images are drawn at twice CSS size, for sharp marks on high-density screens. */
const RATIO = 2;

let atlasPromise = null;

/** The sprite atlas, fetched once: `{ index, image }`, or null if it is not served. */
export function loadAtlas() {
  if (!atlasPromise) {
    atlasPromise = (async () => {
      try {
        const [index, image] = await Promise.all([
          fetch(`${SPRITE}.json`).then((r) => (r.ok ? r.json() : null)),
          new Promise((resolve, reject) => {
            const img = new Image();
            img.onload = () => resolve(img);
            img.onerror = reject;
            img.src = `${SPRITE}.png`;
          }),
        ]);
        return index ? { index, image } : null;
      } catch {
        return null;
      }
    })();
  }
  return atlasPromise;
}

const spriteFor = (icon) => (icon && icon !== "marker" ? icon : null);
const hexKey = (hex) => String(hex || "").toLowerCase().replace("#", "");

/** The image id a place draws with on the map. */
export function placeImageId(icon, color, atlas) {
  const sprite = spriteFor(icon);
  if (sprite && atlas?.index?.[sprite]) return `tracks-icon-${sprite}-${hexKey(color)}`;
  return `tracks-pin-${hexKey(color)}`;
}

/** Whether a place's image is a pin (stands on its point) rather than a symbol (centred). */
export const isPin = (id) => id.startsWith("tracks-pin-");

function symbolCanvas(atlas, sprite, color, halo) {
  const box = atlas.index[sprite];
  const size = ICON_PX * RATIO;
  const scale = size / Math.max(box.width, box.height, 1);
  const w = Math.max(1, Math.round(box.width * scale));
  const h = Math.max(1, Math.round(box.height * scale));
  const ring = HALO_PX * RATIO;
  const pad = Math.ceil(ring);

  // The silhouette in one colour: drawn, then filled where it is opaque.
  const tinted = (fill) => {
    const c = document.createElement("canvas");
    c.width = w; c.height = h;
    const g = c.getContext("2d");
    g.drawImage(atlas.image, box.x, box.y, box.width, box.height, 0, 0, w, h);
    g.globalCompositeOperation = "source-in";
    g.fillStyle = fill;
    g.fillRect(0, 0, w, h);
    return c;
  };

  const out = document.createElement("canvas");
  out.width = w + pad * 2; out.height = h + pad * 2;
  const g = out.getContext("2d");
  if (halo) {
    const edge = tinted(halo);
    for (let k = 0; k < HALO_STAMPS; k++) {
      const a = (2 * Math.PI * k) / HALO_STAMPS;
      g.drawImage(edge, pad + Math.cos(a) * ring, pad + Math.sin(a) * ring);
    }
  }
  g.drawImage(tinted(color || "#0f172a"), pad, pad);
  return out;
}

function pinCanvas(color, accent) {
  const w = PIN_W * RATIO, h = PIN_H * RATIO, stroke = PIN_STROKE * RATIO;
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const g = c.getContext("2d");
  // Inset by the edge, which is drawn wholly outside the shape.
  const cx = w / 2, r = w / 2 - stroke, cy = r + stroke, tipY = h - stroke;
  // A teardrop: a round head over a curved tail. Curved sides, because a
  // triangle under a circle reads as an arrow.
  const head = () => { g.beginPath(); g.arc(cx, cy, r, 0, 2 * Math.PI); };
  const tail = () => {
    g.beginPath();
    g.moveTo(cx - r * SHOULDER, cy + r * SHOULDER);
    g.quadraticCurveTo(cx - r * 0.34, tipY - r * 0.45, cx, tipY);
    g.quadraticCurveTo(cx + r * 0.34, tipY - r * 0.45, cx + r * SHOULDER, cy + r * SHOULDER);
    g.closePath();
  };
  // The edge first, at twice its width, then the fill over it: what is left
  // of the edge is its outer half, all the way round the union of the two —
  // stroking them separately would draw a line through where they overlap.
  if (accent) {
    g.lineWidth = stroke * 2;
    g.lineJoin = "round";
    g.strokeStyle = accent;
    for (const part of [head, tail]) { part(); g.stroke(); }
  }
  g.fillStyle = color || "#0f172a";
  for (const part of [head, tail]) { part(); g.fill(); }
  // The hole in the head: what makes it a pin and not a balloon.
  g.beginPath();
  g.arc(cx, cy, r * HOLE, 0, 2 * Math.PI);
  g.fillStyle = accent || "#ffffff";
  g.fill();
  return c;
}

/** The canvas a place draws as. `halo` is the outline (white on the map; the theme's ink in a list). */
function placeCanvas(atlas, icon, color, halo) {
  const sprite = spriteFor(icon);
  if (sprite && atlas?.index?.[sprite]) return symbolCanvas(atlas, sprite, color, halo);
  return pinCanvas(color, halo);
}

/**
 * Register every image `places` needs on the map, before the layer that names
 * them: a symbol layer pointing at a missing image draws nothing, silently.
 * Re-checked on every call, because a style reload takes added images with it.
 */
export function ensurePlaceImages(map, places, atlas) {
  for (const p of places) {
    const id = placeImageId(p.icon, p.color, atlas);
    try {
      if (map.hasImage(id)) continue;
      const c = placeCanvas(atlas, p.icon, p.color, "#ffffff");
      const data = c.getContext("2d").getImageData(0, 0, c.width, c.height);
      map.addImage(id, data, { pixelRatio: RATIO });
    } catch { /* map mid-reload; the next call adds it */ }
  }
}

const urlCache = new Map();

/** A place's mark as an image URL for HTML — list rows, the picker, the editor's draggable pin. */
export function placeDataUrl(atlas, icon, color, halo = "#ffffff") {
  const key = `${placeImageId(icon, color, atlas)}|${halo}`;
  let url = urlCache.get(key);
  if (!url) {
    url = placeCanvas(atlas, icon, color, halo).toDataURL();
    urlCache.set(key, url);
  }
  return url;
}
