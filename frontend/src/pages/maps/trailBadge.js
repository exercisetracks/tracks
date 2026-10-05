// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Canvas trail-emblem badge rendering + the MapLibre `styleimagemissing` handler.
// The registry + icon/colour expressions live in trailLogos.js.

import { byId, PREFIX, BADGE_PREFIX } from "./trailLogos";

const IMG_PX = 96;
export const ICON_PIXEL_RATIO = 3;

function _shade(hex, amt) {
  // amt > 0 lightens toward white, amt < 0 darkens toward black.
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || "#3f7a3f");
  let [r, g, b] = m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [63, 122, 63];
  const t = amt < 0 ? 0 : 255;
  const p = Math.abs(amt);
  r = Math.round((t - r) * p + r);
  g = Math.round((t - g) * p + g);
  b = Math.round((t - b) * p + b);
  return `rgb(${r},${g},${b})`;
}

function _shield(ctx, x, y, w, h) {
  // Rounded-top shield with a soft point at the bottom — a trail-blaze look.
  const r = w * 0.22, cx = x + w / 2;
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.arcTo(x + w, y, x + w, y + r, r);
  ctx.lineTo(x + w, y + h * 0.58);
  ctx.quadraticCurveTo(x + w, y + h * 0.82, cx, y + h);
  ctx.quadraticCurveTo(x, y + h * 0.82, x, y + h * 0.58);
  ctx.lineTo(x, y + r);
  ctx.arcTo(x, y, x + r, y, r);
  ctx.closePath();
}

// A coloured shield badge: vertical gradient for depth, white border + inner
// blaze stripe, bold abbreviation. `color` is the trail's colour (the splash).
function _badge(abbr, color) {
  const s = IMG_PX;
  const cv = document.createElement("canvas");
  cv.width = s; cv.height = s;
  const ctx = cv.getContext("2d");
  const pad = 7;

  // drop shadow
  ctx.save();
  ctx.shadowColor = "rgba(0,0,0,0.35)";
  ctx.shadowBlur = 5;
  ctx.shadowOffsetY = 2;
  _shield(ctx, pad, pad, s - 2 * pad, s - 2 * pad - 2);
  const grad = ctx.createLinearGradient(0, pad, 0, s - pad);
  grad.addColorStop(0, _shade(color, 0.28));
  grad.addColorStop(0.55, color);
  grad.addColorStop(1, _shade(color, -0.22));
  ctx.fillStyle = grad;
  ctx.fill();
  ctx.restore();

  // white border
  _shield(ctx, pad, pad, s - 2 * pad, s - 2 * pad - 2);
  ctx.lineWidth = 6;
  ctx.strokeStyle = "#ffffff";
  ctx.stroke();
  // thin dark keyline for contrast on light terrain
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = "rgba(0,0,0,0.25)";
  ctx.stroke();

  // label (or a white blaze for the generic emblem)
  ctx.fillStyle = "#ffffff";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  const cy = s / 2 - 3;
  if (abbr) {
    const n = abbr.length;
    const size = n >= 4 ? 24 : n === 3 ? 30 : 38;
    ctx.font = `800 ${size}px ui-sans-serif, system-ui, Arial, sans-serif`;
    ctx.shadowColor = "rgba(0,0,0,0.45)";
    ctx.shadowBlur = 3;
    ctx.fillText(abbr, s / 2, cy);
  } else {
    ctx.fillRect(s / 2 - 7, cy - 20, 14, 40);
  }
  return ctx.getImageData(0, 0, s, s);
}

function _addImage(map, id, data) {
  if (map.hasImage(id)) return;
  try { map.addImage(id, data, { pixelRatio: ICON_PIXEL_RATIO }); } catch { /* headless */ }
}

// Handle a `styleimagemissing` event for either id shape.
export async function installMissingTrailLogo(map, id) {
  if (!id || map.hasImage(id)) return;

  // Data-driven fallback badge: tlbadge|<#color>|<ABBR>
  if (id.startsWith(BADGE_PREFIX)) {
    const [, color, abbr] = id.split("|");
    _addImage(map, id, _badge(abbr || "", color || "#3f7a3f"));
    return;
  }

  if (!id.startsWith(PREFIX)) return;
  const slug = id.slice(PREFIX.length);
  const t = byId[slug] || byId.generic;
  // Add the curated canvas shield SYNCHRONOUSLY so the badge always renders on
  // this paint pass; previously a 404'd PNG fetch made the fallback resolve too
  // late and the major trails showed no shield at all.
  _addImage(map, id, _badge(t.abbr, t.color));
  // Best-effort upgrade to a real logo PNG if the backend actually has one.
  if (t.logo) {
    try {
      const resp = await fetch(`/api/maps/trail-logos/${slug}.png`);
      if (resp.ok) {
        const bmp = await createImageBitmap(await resp.blob());
        if (map.hasImage(id)) map.updateImage(id, bmp);
        else map.addImage(id, bmp, { pixelRatio: ICON_PIXEL_RATIO });
      }
    } catch { /* keep the curated canvas badge */ }
  }
}
