// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Custom MapLibre WebGL layer that renders GPS tracks as additively-blended,
// soft-edged glowing lines — the self-hosted replacement for the old Leaflet
// additive-canvas heatmap. Overlapping tracks accumulate brightness (frequency
// mode ramps orange→yellow→white naturally via additive blend); value modes
// (pace/HR/gradient) carry a per-segment colour.
//
// Improvement over the canvas version: each segment is drawn as a feathered quad
// (bright core fading to transparent edges) instead of a hard 1px stroke, giving
// a smoother, true "glow" look.

// ── GL helpers ────────────────────────────────────────────────────────────────

function compileShader(gl, type, src) {
  const sh = gl.createShader(type);
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(sh);
    gl.deleteShader(sh);
    throw new Error("Heatmap shader compile failed: " + log);
  }
  return sh;
}

function linkProgram(gl, vsrc, fsrc) {
  const p = gl.createProgram();
  gl.attachShader(p, compileShader(gl, gl.VERTEX_SHADER, vsrc));
  gl.attachShader(p, compileShader(gl, gl.FRAGMENT_SHADER, fsrc));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
    throw new Error("Heatmap program link failed: " + gl.getProgramInfoLog(p));
  }
  return p;
}

const VERT_SRC = `
precision highp float;
attribute vec2  a_pos;    // web-mercator [0,1]
attribute vec2  a_perp;   // unit perpendicular in mercator space
attribute float a_side;   // -1 .. +1 across the line width
attribute vec3  a_color;
uniform mat4  u_matrix;
uniform float u_widthNDC; // 2 * halfWidthPx / canvasHeight
uniform float u_aspect;   // canvasWidth / canvasHeight
varying float v_side;
varying vec3  v_color;
void main() {
  vec4 p  = u_matrix * vec4(a_pos, 0.0, 1.0);
  vec4 pe = u_matrix * vec4(a_pos + a_perp * 1e-4, 0.0, 1.0);
  // perpendicular direction in screen space (aspect-corrected), then back to NDC
  vec2 d = (pe.xy / pe.w) - (p.xy / p.w);
  d.x *= u_aspect;
  d = length(d) > 0.0 ? normalize(d) : vec2(0.0);
  d.x /= u_aspect;
  gl_Position = p;
  gl_Position.xy += d * a_side * u_widthNDC * p.w;
  v_side = a_side;
  v_color = a_color;
}`;

const FRAG_SRC = `
precision highp float;
varying float v_side;
varying vec3  v_color;
uniform float u_intensity;
void main() {
  // bright core, smooth falloff to the feathered edge
  float edge = 1.0 - abs(v_side);
  float i = u_intensity * edge * edge;
  gl_FragColor = vec4(v_color * i, i);   // premultiplied; drawn with additive blend
}`;

const FLOATS_PER_VERT = 8;   // pos(2) perp(2) side(1) color(3)
const VERTS_PER_SEG   = 6;   // two triangles

// ── Custom layer ──────────────────────────────────────────────────────────────

export class HeatmapGlowLayer {
  constructor(id = "heatmap-glow") {
    this.id = id;
    this.type = "custom";
    this.renderingMode = "2d";
    this._verts = null;
    this._count = 0;
    this._halfWidthPx = 2.75;   // ~25% thicker tracks
    this._intensity = 0.20;
  }

  onAdd(map, gl) {
    this._map = map;
    this._gl = gl;
    this._program = linkProgram(gl, VERT_SRC, FRAG_SRC);
    this._buf = gl.createBuffer();
    this._loc = {
      a_pos:       gl.getAttribLocation(this._program, "a_pos"),
      a_perp:      gl.getAttribLocation(this._program, "a_perp"),
      a_side:      gl.getAttribLocation(this._program, "a_side"),
      a_color:     gl.getAttribLocation(this._program, "a_color"),
      u_matrix:    gl.getUniformLocation(this._program, "u_matrix"),
      u_widthNDC:  gl.getUniformLocation(this._program, "u_widthNDC"),
      u_aspect:    gl.getUniformLocation(this._program, "u_aspect"),
      u_intensity: gl.getUniformLocation(this._program, "u_intensity"),
    };
    if (this._verts) this._upload();
  }

  setData(verts, count) {
    this._verts = verts;
    this._count = count;
    if (this._gl && this._buf) this._upload();
    this._map?.triggerRepaint();
  }

  setStyle({ halfWidthPx, intensity }) {
    if (halfWidthPx != null) this._halfWidthPx = halfWidthPx;
    if (intensity != null) this._intensity = intensity;
    this._map?.triggerRepaint();
  }

  _upload() {
    const gl = this._gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this._buf);
    gl.bufferData(gl.ARRAY_BUFFER, this._verts, gl.STATIC_DRAW);
  }

  render(gl, args) {
    if (!this._count || !this._program) return;
    // MapLibre v4 passes the mercator matrix directly; newer/globe paths pass an
    // args object — support both.
    const matrix = (args && args.defaultProjectionData)
      ? args.defaultProjectionData.mainMatrix
      : args;

    gl.useProgram(this._program);
    gl.uniformMatrix4fv(this._loc.u_matrix, false, matrix);
    const h = gl.drawingBufferHeight || 1;
    const w = gl.drawingBufferWidth || 1;
    // Widen lines as you zoom out so short, dense segments don't wash to nothing
    // (mirrors the old per-zoom weight); tapers to the base width when zoomed in.
    const zoom = this._map ? this._map.getZoom() : 12;
    const halfW = Math.min(this._halfWidthPx + Math.max(0, 11 - zoom) * 0.225, 6.25);
    gl.uniform1f(this._loc.u_widthNDC, (2 * halfW) / h);
    gl.uniform1f(this._loc.u_aspect, w / h);
    gl.uniform1f(this._loc.u_intensity, this._intensity);

    gl.bindBuffer(gl.ARRAY_BUFFER, this._buf);
    const S = FLOATS_PER_VERT * 4;
    gl.enableVertexAttribArray(this._loc.a_pos);
    gl.vertexAttribPointer(this._loc.a_pos, 2, gl.FLOAT, false, S, 0);
    gl.enableVertexAttribArray(this._loc.a_perp);
    gl.vertexAttribPointer(this._loc.a_perp, 2, gl.FLOAT, false, S, 8);
    gl.enableVertexAttribArray(this._loc.a_side);
    gl.vertexAttribPointer(this._loc.a_side, 1, gl.FLOAT, false, S, 16);
    gl.enableVertexAttribArray(this._loc.a_color);
    gl.vertexAttribPointer(this._loc.a_color, 3, gl.FLOAT, false, S, 20);

    gl.enable(gl.BLEND);
    gl.blendEquation(gl.FUNC_ADD);
    gl.blendFunc(gl.ONE, gl.ONE);   // additive — overlaps accumulate brightness
    gl.disable(gl.DEPTH_TEST);
    gl.drawArrays(gl.TRIANGLES, 0, this._count);
  }

  onRemove() {
    const gl = this._gl;
    if (!gl) return;
    if (this._program) gl.deleteProgram(this._program);
    if (this._buf) gl.deleteBuffer(this._buf);
  }
}

// ── Geometry/colour building ──────────────────────────────────────────────────

// Frequency base colour (orange); additive accumulation ramps it to yellow→white.
const ORANGE = [1.0, 0.40, 0.05];
const SPEED_STOPS = [
  [0, [41, 98, 255]], [0.4, [16, 185, 129]], [0.7, [251, 191, 36]], [1, [239, 68, 68]],
];
const GRAD_STOPS = [
  [0, [16, 185, 129]], [0.5, [255, 255, 255]], [1, [139, 92, 246]],
];

function rgbFromStops(stops, t) {
  for (let i = 1; i < stops.length; i++) {
    const [t0, c0] = stops[i - 1], [t1, c1] = stops[i];
    if (t <= t1) {
      const f = (t - t0) / (t1 - t0);
      return [(c0[0] + (c1[0] - c0[0]) * f) / 255, (c0[1] + (c1[1] - c0[1]) * f) / 255, (c0[2] + (c1[2] - c0[2]) * f) / 255];
    }
  }
  const last = stops[stops.length - 1][1];
  return [last[0] / 255, last[1] / 255, last[2] / 255];
}

function haversineM(lat1, lng1, lat2, lng2) {
  const R = 6_371_000, f1 = lat1 * Math.PI / 180, f2 = lat2 * Math.PI / 180;
  const df = (lat2 - lat1) * Math.PI / 180, dl = (lng2 - lng1) * Math.PI / 180;
  const a = Math.sin(df / 2) ** 2 + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

const mercX = (lng) => (180 + lng) / 360;
const mercY = (lat) => {
  const s = Math.sin(lat * Math.PI / 180);
  return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
};

// Build the interleaved vertex buffer for all track segments.
// tracks: [[ [lat,lng,value?], ... ], ...].  Returns { verts: Float32Array, count }.
export function buildHeatmapVerts(tracks, mode) {
  if (!tracks?.length) return { verts: new Float32Array(0), count: 0 };

  let colorFor;
  if (mode === "gradient") {
    colorFor = (a, b) => {
      if (a[2] == null || b[2] == null) return null;
      const dist = haversineM(a[0], a[1], b[0], b[1]);
      const grade = dist < 3 ? 0 : Math.max(-0.2, Math.min(0.2, (b[2] - a[2]) / dist));
      return rgbFromStops(GRAD_STOPS, (grade + 0.2) / 0.4);
    };
  } else if (mode === "pace" || mode === "heartrate") {
    const vals = [];
    for (const tr of tracks) for (const pt of tr) if (pt[2] != null) vals.push(pt[2]);
    if (!vals.length) {
      colorFor = () => ORANGE;
    } else {
      vals.sort((x, y) => x - y);
      const pct = (p) => {
        const idx = (p / 100) * (vals.length - 1), lo = Math.floor(idx);
        return vals[lo] + (vals[Math.ceil(idx)] - vals[lo]) * (idx - lo);
      };
      const lo = pct(5), hi = pct(95), range = hi - lo || 1;
      colorFor = (a, b) => {
        if (a[2] == null || b[2] == null) return null;
        const t = Math.max(0, Math.min(1, ((a[2] + b[2]) / 2 - lo) / range));
        return rgbFromStops(SPEED_STOPS, t);
      };
    }
  } else {
    colorFor = () => ORANGE;   // frequency
  }

  let maxSegs = 0;
  for (const tr of tracks) if (tr.length > 1) maxSegs += tr.length - 1;
  const verts = new Float32Array(maxSegs * VERTS_PER_SEG * FLOATS_PER_VERT);
  let vi = 0;

  for (const tr of tracks) {
    for (let i = 0; i < tr.length - 1; i++) {
      const a = tr[i], b = tr[i + 1];
      const col = colorFor(a, b);
      if (!col) continue;
      const ax = mercX(a[1]), ay = mercY(a[0]);
      const bx = mercX(b[1]), by = mercY(b[0]);
      let dx = bx - ax, dy = by - ay;
      const len = Math.hypot(dx, dy) || 1e-9;
      const px = -dy / len, py = dx / len;     // unit perpendicular
      const r = col[0], g = col[1], bl = col[2];
      // two triangles: (a+ , a- , b+) and (b+ , a- , b-)
      const v = (x, y, side) => {
        verts[vi++] = x; verts[vi++] = y;
        verts[vi++] = px; verts[vi++] = py;
        verts[vi++] = side;
        verts[vi++] = r; verts[vi++] = g; verts[vi++] = bl;
      };
      v(ax, ay, 1); v(ax, ay, -1); v(bx, by, 1);
      v(bx, by, 1); v(ax, ay, -1); v(bx, by, -1);
    }
  }

  return { verts, count: vi / FLOATS_PER_VERT };
}

// Per-mode glow tuning. Frequency leans on accumulation (lower intensity, the
// glow builds where routes overlap); value modes need each segment legible.
export function glowStyleForMode(mode) {
  // Track half-widths bumped ~25% from the original 2.2 / 2.0.
  if (mode === "frequency") return { halfWidthPx: 2.75, intensity: 0.18 };
  return { halfWidthPx: 2.5, intensity: 0.55 };
}
