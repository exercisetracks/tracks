// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useMemo } from "react";
import { COLORS } from "../style/palette";

// USGS-quad legend. A floating button toggles a scrollable panel that mirrors
// the official 7.5' topographic symbol sheet, scoped to what this map renders.
//
// Point + area symbols are cropped straight from the live `/api/sprite/usgs`
// sheet onto a canvas → data URL, so the legend is always pixel-identical to the
// icons on the map. Line/boundary/contour conventions are drawn as inline SVG
// from the shared palette so they stay in sync with the style.

const SPRITE_URL = "/api/sprite/usgs";

// ── sprite → data URL helpers ───────────────────────────────────────────────
function loadSprite() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const hi = dpr > 1;
  const base = `${SPRITE_URL}${hi ? "@2x" : ""}`;
  return Promise.all([
    fetch(`${base}.json`).then((r) => r.json()),
    new Promise((resolve, reject) => {
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = `${base}.png`;
    }),
  ]).then(([manifest, sheet]) => ({ manifest, sheet, dpr }));
}

function iconUrl(sheet, e, disp, dpr) {
  const c = document.createElement("canvas");
  c.width = disp * dpr;
  c.height = disp * dpr;
  const ctx = c.getContext("2d");
  const natW = e.width / e.pixelRatio;
  const natH = e.height / e.pixelRatio;
  const fit = Math.min(disp / natW, disp / natH, 1.15);
  const dw = natW * fit * dpr;
  const dh = natH * fit * dpr;
  ctx.drawImage(sheet, e.x, e.y, e.width, e.height,
    (c.width - dw) / 2, (c.height - dh) / 2, dw, dh);
  return c.toDataURL();
}

// Compose a line swatch whose distinctive marker (rail tie, pylon tower,
// pipeline bead) is drawn straight from the live sprite sheet — so the legend
// tracks the real glyph, and the base line uses the shared palette colour. One
// icon source, no divergence as the style evolves.
function markerLineUrl(sheet, e, dpr, { color, lineWidth, gap = 0, every, mScale }) {
  const W = 46, H = 14, mid = H / 2;
  const c = document.createElement("canvas");
  c.width = W * dpr;
  c.height = H * dpr;
  const ctx = c.getContext("2d");
  ctx.scale(dpr, dpr);
  ctx.strokeStyle = color;
  ctx.lineWidth = lineWidth;
  ctx.lineCap = "round";
  const rails = gap > 0 ? [mid - gap / 2, mid + gap / 2] : [mid];
  for (const y of rails) {
    ctx.beginPath();
    ctx.moveTo(1, y);
    ctx.lineTo(W - 1, y);
    ctx.stroke();
  }
  const natW = e.width / e.pixelRatio, natH = e.height / e.pixelRatio;
  const scale = mScale / Math.max(natW, natH);
  const dw = natW * scale, dh = natH * scale;
  for (let x = every / 2; x <= W; x += every) {
    ctx.drawImage(sheet, e.x, e.y, e.width, e.height, x - dw / 2, mid - dh / 2, dw, dh);
  }
  return c.toDataURL();
}

function patternUrl(sheet, e, w, h, dpr, tint) {
  const c = document.createElement("canvas");
  c.width = w * dpr;
  c.height = h * dpr;
  const ctx = c.getContext("2d");
  if (tint) {
    ctx.fillStyle = tint;
    ctx.fillRect(0, 0, c.width, c.height);
  }
  const tile = document.createElement("canvas");
  tile.width = e.width;
  tile.height = e.height;
  tile.getContext("2d").drawImage(sheet, e.x, e.y, e.width, e.height, 0, 0, e.width, e.height);
  const pat = ctx.createPattern(tile, "repeat");
  const s = (dpr / e.pixelRatio);
  if (pat.setTransform) pat.setTransform(new DOMMatrix([s, 0, 0, s, 0, 0]));
  ctx.fillStyle = pat;
  ctx.fillRect(0, 0, c.width, c.height);
  return c.toDataURL();
}

// ── symbol tables ────────────────────────────────────────────────────────────
const POINTS = [
  ["Recreation", [
    ["campground", "Campground"], ["picnic", "Picnic area"],
    ["trailhead", "Trailhead"], ["winter_rec", "Winter recreation"],
    ["viewpoint", "Viewpoint"], ["shelter", "Shelter"],
    ["alpine_hut", "Hut / cabin"], ["information", "Information"],
  ]],
  ["Water sources", [
    ["spring", "Spring / seep"], ["well", "Well"],
    ["drinking_water", "Drinking water"], ["gaging_station", "Gaging station"],
    ["falls", "Falls"], ["boat_ramp", "Boat ramp"],
  ]],
  ["Culture", [
    ["ranger_station", "Ranger station"], ["place_of_worship", "House of worship"],
    ["school", "School"], ["cemetery", "Cemetery"], ["tower", "Tower"],
    ["substation", "Substation"], ["aerodrome", "Airport"],
  ]],
  ["Mines & relief", [
    ["mine", "Mine"], ["mine_shaft", "Mine shaft"], ["quarry", "Quarry"],
    ["cave", "Cave entrance"], ["benchmark", "Benchmark (BM)"],
    ["spot_elevation", "Spot elevation"], ["monument", "Location monument"],
  ]],
  ["Parks & land", [
    ["park", "Park / preserve"], ["forest", "Forest"],
  ]],
  ["Services", [
    ["toilets", "Restroom"], ["parking", "Parking"],
    ["fuel", "Fuel"], ["hospital", "Hospital"],
  ]],
  ["Food & culture", [
    ["restaurant", "Restaurant"], ["cafe", "Café"], ["fast_food", "Fast food"],
    ["bar", "Bar / pub"], ["supermarket", "Supermarket"],
    ["convenience", "Convenience store"], ["library", "Library"],
    ["museum", "Museum"], ["theatre", "Theater"],
  ]],
];

// pattern swatches: [sprite name, label, tint]
const PATTERNS = [
  ["marsh", "Marsh", "#DCE9E2"], ["swamp", "Swamp / bog", "#D6E5DD"],
  ["mangrove", "Mangrove", "#D2E3DA"], ["orchard", "Orchard", "#E2EFD4"],
  ["vineyard", "Vineyard", "#E4EFD6"], ["scrub", "Scrub", COLORS.landcover.scrub],
  ["sand", "Sand", COLORS.landcover.sand], ["gravel", "Gravel / rock", COLORS.landcover.rock],
  ["mud", "Tidal / foreshore flat", COLORS.landcover.tidal],
  ["reef", "Rock or coral reef", COLORS.landcover.tidal],
];

const SOLIDS = [
  ["Woodland", COLORS.landcover.wood],
  ["Grassland", COLORS.landcover.grass],
  ["Glacier / snow", COLORS.landcover.glacier],
];

// Live wildfire/smoke overlay swatches (match style/layers/wildfires.js; the
// smoke tints are the plume wash at each NOAA density over legend paper).
const w = COLORS.wildfire;
const SMOKE_DENSITIES = [
  ["Smoke — light", "rgba(110, 100, 90, 0.11)"],
  ["Smoke — medium", "rgba(110, 100, 90, 0.20)"],
  ["Smoke — heavy", "rgba(110, 100, 90, 0.32)"],
];

// Public-land overlay tints (must match style/layers/publiclands.js FILL).
const PUBLIC_LANDS = [
  ["National Park / Monument", "#7FBE86"],
  ["National Forest", "#A6D199"],
  ["Wilderness Area", "#74B187"],
  ["BLM public land", "#E3CB80"],
  ["State public land", "#C2D98C"],
  ["Nature reserve", "#93C7BC"],
  ["Military land", "#D7968E"],
  ["Tribal / reservation", "#E0B17E"],
];

// ── inline-SVG line swatches ──────────────────────────────────────────────────
function Line({ color, width = 2, dash, casing, casingWidth }) {
  return (
    <svg width="46" height="14" className="shrink-0">
      {casing && (
        <line x1="1" y1="7" x2="45" y2="7" stroke={casing}
          strokeWidth={casingWidth || width + 2.5} strokeLinecap="round" />
      )}
      <line x1="1" y1="7" x2="45" y2="7" stroke={color} strokeWidth={width}
        strokeDasharray={dash} strokeLinecap={dash ? "butt" : "round"} />
    </svg>
  );
}

const r = COLORS.roads;
const t = COLORS.trails;
const b = COLORS.boundary;
const inf = COLORS.infra;
const TRAIL_INK = "#43301d";   // dark warm brown the OSM trails actually render in
const LINES = [
  ["Roads", [
    [<Line color={r.highwayFill} width={4} casing={r.highwayCasing} casingWidth={6} />, "Primary highway"],
    [<Line color={r.secondaryFill} width={3.5} casing={r.secondaryCasing} casingWidth={5} />, "Secondary highway"],
    [<Line color={r.lightFill} width={3.2} casing={r.lightCasing} casingWidth={5} />, "Light-duty road (paved)"],
    [<svg width="46" height="14" className="shrink-0">
      <line x1="1" y1="5" x2="45" y2="5" stroke={r.service} strokeWidth="1.3" strokeDasharray="4 3" strokeLinecap="butt" />
      <line x1="1" y1="9" x2="45" y2="9" stroke={r.service} strokeWidth="1.3" strokeDasharray="4 3" strokeLinecap="butt" />
    </svg>, "Service road (graded)"],
    [<Line color={t.track} width={2.6} dash="5 3" />, "Two-track / 4WD road"],
  ]],
  ["Trails", [
    // Hue = highest permitted use (Gaia convention); the pale-yellow casing is
    // the halo drawn under every trail on the map.
    [<Line color={TRAIL_INK} width={2} dash="3 2" casing={t.halo} casingWidth={5.5} />, "Hiking trail (foot only)"],
    [<Line color={t.horse} width={2} dash="3 2" casing={t.halo} casingWidth={5.5} />, "Horse trail (equestrian)"],
    [<Line color={t.bike} width={2} dash="3 2" casing={t.halo} casingWidth={5.5} />, "Bike-legal trail (MTB)"],
    [<Line color={t.moto} width={2} dash="3 2" casing={t.halo} casingWidth={5.5} />, "Motorized trail (OHV)"],
    [<Line color={t.steps} width={3} dash="1 2.5" />, "Steps / stairway"],
    [<Line color={t.difficult} width={2} dash="3 2" />, "Difficult / technical"],
    [<Line color={t.routeLine} width={3} />, "Long-distance route"],
  ]],
  // NB: "Railroads & utilities" is rendered separately (RAIL_UTIL) — its rail/
  // power/pipeline swatches composite the live sprite glyphs so they stay in
  // sync with the map.
  ["Boundaries", [
    [<Line color={b.national} width={2} dash="6 2 1 2" />, "National"],
    [<Line color={b.state} width={1.6} dash="5 2 1 2" />, "State"],
    [<Line color={b.county} width={1.4} dash="3 2 1 2" />, "County"],
    [<Line color={b.city} width={1.2} dash="1 2" />, "City / township"],
  ]],
  ["Relief", [
    [<Line color={COLORS.labels.contour} width={1.8} />, "Contour, index"],
    [<Line color={COLORS.labels.contour} width={0.9} />, "Contour, intermediate"],
  ]],
  ["Water", [
    [<Line color={COLORS.streamLine} width={2.6} />, "River"],
    [<Line color={COLORS.streamLine} width={1.8} />, "Perennial stream / canal"],
    [<Line color={COLORS.streamLine} width={1.6} dash="4 1.5 0.5 1.5" />, "Intermittent stream"],
    [<Line color={COLORS.streamLine} width={1.1} />, "Ditch / drain"],
  ]],
];

// Sprite-composited utility lines: the marker glyph is pulled from the live
// sprite sheet (single source of truth) so these mirror the map exactly. Keys
// match the data-URL keys generated in the `icons` memo (`line:<key>`).
//   rail      — double track (gap) strung with rail-tie crossties
//   power     — solid line carrying pylon towers
//   pipeline  — solid line strung with hollow beads
const MARKER_LINES = [
  { key: "rail", glyph: "rail_tie", label: "Railroad", color: inf.rail, lineWidth: 1.2, gap: 4, every: 6, mScale: 11 },
  { key: "power", glyph: "pylon", label: "Power transmission line", color: inf.power, lineWidth: 1.4, every: 17, mScale: 12 },
  { key: "pipeline", glyph: "pipeline_marker", label: "Pipeline", color: inf.pipeline, lineWidth: 1.4, every: 8, mScale: 8 },
];

function Swatch({ src, color, children }) {
  return (
    <span
      className="shrink-0 w-[34px] h-[20px] rounded border border-stone-400 bg-center"
      style={src ? { backgroundImage: `url(${src})` } : { backgroundColor: color }}
    >
      {children}
    </span>
  );
}

function Row({ children, label }) {
  return (
    <li className="flex items-center gap-2.5 py-0.5">
      {children}
      <span className="text-[12.5px] text-stone-700 leading-tight">{label}</span>
    </li>
  );
}

function Section({ title, children }) {
  // break-inside-avoid keeps a whole section (header + rows) in one column.
  return (
    <div className="space-y-0.5 break-inside-avoid mb-1.5">
      <h4 className="text-[11px] font-semibold uppercase tracking-wide text-stone-500 mt-1">{title}</h4>
      <ul>{children}</ul>
    </div>
  );
}

// Controlled popup: mounted by MapView's bottom-left control bar when the Legend
// button is active. Renders only the popup body (the toggle button lives in the
// shared control bar); calls onClose when its × is clicked.
export default function MapLegend({ onClose }) {
  const [sprite, setSprite] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (sprite || failed) return;
    let alive = true;
    loadSprite()
      .then((s) => alive && setSprite(s))
      .catch(() => alive && setFailed(true));
    return () => { alive = false; };
  }, [sprite, failed]);

  // Pre-render every sprite swatch to a data URL once the sheet loads.
  const icons = useMemo(() => {
    if (!sprite) return {};
    const { manifest, sheet, dpr } = sprite;
    const out = {};
    POINTS.forEach(([, items]) => items.forEach(([name]) => {
      const e = manifest[name];
      if (e) out[name] = iconUrl(sheet, e, 24, dpr);
    }));
    PATTERNS.forEach(([name, , tint]) => {
      const e = manifest[name];
      if (e) out[`pat:${name}`] = patternUrl(sheet, e, 34, 20, dpr, tint);
    });
    MARKER_LINES.forEach(({ key, glyph, ...opts }) => {
      const e = manifest[glyph];
      if (e) out[`line:${key}`] = markerLineUrl(sheet, e, dpr, opts);
    });
    return out;
  }, [sprite]);

  return (
    // Fixed light "paper" sheet (like a real quad legend) so the black USGS
    // culture/relief glyphs and dark boundary lines always read, regardless
    // of the app's dark theme.
    <div className="absolute bottom-4 left-16 z-40 w-[510px] max-w-[calc(100vw-5rem)] max-h-[72vh] overflow-y-auto bg-[#f7f4ec] text-stone-800 rounded-xl shadow-xl border border-stone-300 p-2.5">
      <div className="flex items-center justify-between mb-1">
        <h3 className="text-sm font-semibold text-stone-800">Map legend</h3>
        <button onClick={onClose} className="text-stone-500 hover:text-stone-700">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {failed && (
        <p className="text-[11px] text-stone-500">Legend symbols unavailable (sprite not loaded).</p>
      )}

      {/* Two newspaper-style columns; each Section stays intact (break-inside-avoid). */}
      <div className="columns-2 gap-x-4">
          {/* Point symbols */}
          {POINTS.map(([title, items]) => (
            <Section key={title} title={title}>
              {items.map(([name, label]) => (
                <Row key={name} label={label}>
                  <span className="shrink-0 w-[26px] h-[26px] flex items-center justify-center">
                    {icons[name]
                      ? <img src={icons[name]} alt="" width={24} height={24} />
                      : <span className="w-3.5 h-3.5 rounded-full bg-stone-300" />}
                  </span>
                </Row>
              ))}
            </Section>
          ))}

          {/* Lines */}
          {LINES.map(([title, items]) => (
            <Section key={title} title={title}>
              {items.map(([swatch, label], i) => (
                <Row key={i} label={label}>{swatch}</Row>
              ))}
            </Section>
          ))}

          {/* Railroads & utilities — rail/power/pipeline swatches composite the
              live sprite glyphs (single source); the rest are palette lines. */}
          <Section title="Railroads & utilities">
            {MARKER_LINES.map((m) => (
              <Row key={m.key} label={m.label}>
                {icons[`line:${m.key}`]
                  ? <img src={icons[`line:${m.key}`]} width={46} height={14} alt="" className="shrink-0" />
                  : <Line color={m.color} width={m.lineWidth} />}
              </Row>
            ))}
            <Row label="Power distribution line">
              <Line color={inf.powerMinor} width={1.2} dash="0.5 2.5" />
            </Row>
            <Row label="Dam / weir"><Line color={inf.dam} width={3.6} /></Row>
            <Row label="Levee"><Line color={inf.levee} width={2} dash="4 2" /></Row>
          </Section>

          {/* Vegetation / area fills */}
          <Section title="Vegetation & wetlands">
            {SOLIDS.map(([label, color]) => (
              <Row key={label} label={label}><Swatch color={color} /></Row>
            ))}
            {PATTERNS.map(([name, label]) => (
              <Row key={name} label={label}><Swatch src={icons[`pat:${name}`]} color="#eee" /></Row>
            ))}
          </Section>

          {/* Public-land ownership overlay */}
          <Section title="Public lands">
            {PUBLIC_LANDS.map(([label, color]) => (
              <Row key={label} label={label}><Swatch color={color} /></Row>
            ))}
          </Section>

          {/* Live wildfire + smoke overlay (opt-in Layers toggles) */}
          <Section title="Wildfire & smoke (live)">
            <Row label="Active fire (sized by acres)">
              <svg width="34" height="20" className="shrink-0">
                <circle cx="17" cy="10" r="8" fill={w.glow} opacity="0.35" />
                <circle cx="17" cy="10" r="4" fill={w.core} stroke="#fff" strokeWidth="1.4" />
              </svg>
            </Row>
            <Row label="Fire perimeter">
              <svg width="34" height="20" className="shrink-0">
                <rect x="2" y="3" width="30" height="14" rx="2"
                  fill={w.perimeterFill} fillOpacity="0.18"
                  stroke={w.perimeter} strokeWidth="1.6" />
              </svg>
            </Row>
            {SMOKE_DENSITIES.map(([label, color]) => (
              <Row key={label} label={label}><Swatch color={color} /></Row>
            ))}
          </Section>
      </div>
    </div>
  );
}
