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
//
// Value modes average instead of adding. Summed, their colours ran to white
// wherever routes stacked up, and a busy street read as no pace at all — the
// one thing those modes are for. So they are drawn in two passes: `prerender`
// accumulates colour × weight and weight into a float texture, and `render`
// lays the weighted average over the map, more opaque and somewhat brighter
// where it is dense, never a different colour. The phone's dashboard does the
// same (HeatmapGlow.kt). Without float render targets they fall back to the
// additive glow.
//
// Light mode subtracts instead of adding — the same glow, mirrored. Additive
// light can only brighten, and over a near-white basemap there is almost no
// brightness left to add: every colour washed out to the ground and the lines
// were close to invisible. Subtracting (1 − ink) from white leaves the ink, and
// overlaps keep subtracting, so density deepens toward the colour the way it
// brightens toward white in dark mode. Shader, widths and intensities are
// shared; only the blend equation and the palette (inks, see LIGHT_INKS)
// differ by theme.

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
uniform float u_subtract;
void main() {
  // bright core, smooth falloff to the feathered edge
  float edge = 1.0 - abs(v_side);
  float i = u_intensity * edge * edge;
  // Light mode takes the ink's complement away from the map (see the header).
  vec3 c = u_subtract > 0.5 ? 1.0 - v_color : v_color;
  gl_FragColor = vec4(c * i, i);   // premultiplied; drawn with additive blend
}`;

// Pass one of the averaged modes: the same falloff, as a weight, summed
// with each colour scaled by it.
const ACCUM_FRAG_SRC = `
precision highp float;
varying float v_side;
varying vec3  v_color;
void main() {
  float edge = 1.0 - abs(v_side);
  float w = edge * edge;
  gl_FragColor = vec4(v_color * w, w);
}`;

// Pass two: one triangle over the whole screen.
const QUAD_VERT_SRC = `
precision highp float;
attribute vec2 a_pos;
void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }`;

// The sums back into a colour. Opacity rises with weight — one route about
// half, two most of the way — and a pile of routes lifts the average's
// brightness (dark map) or depth (light map) by up to u_lift, reaching most
// of it at u_liftRoutes overlapping. The hue is the average throughout.
const COMPOSITE_FRAG_SRC = `
precision highp float;
uniform sampler2D u_accum;
uniform vec2  u_size;
uniform float u_opacity;
uniform float u_lift;
uniform float u_liftRoutes;
uniform float u_dark;
void main() {
  vec4 acc = texture2D(u_accum, gl_FragCoord.xy / u_size);
  if (acc.a <= 0.0001) discard;
  vec3 avg = acc.rgb / acc.a;
  float a = 1.0 - exp(-acc.a * u_opacity);
  float lift = u_lift * (1.0 - exp(-max(0.0, acc.a - 1.0) / u_liftRoutes));
  vec3 c = u_dark > 0.5 ? min(avg * (1.0 + lift), 1.0) : avg * (1.0 - lift * 0.5);
  gl_FragColor = vec4(c * a, a);   // premultiplied, drawn over the map
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
    this._subtractive = false;
    this._average = false;
    this._accum = null;   // { fbo, tex, w, h } once a value mode has drawn
  }

  /** Light mode: subtract from the map rather than add to it (see the header). */
  setSubtractive(subtractive) {
    this._subtractive = !!subtractive;
    this._map?.triggerRepaint();
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
      u_subtract:  gl.getUniformLocation(this._program, "u_subtract"),
    };
    // Averaging needs a float texture it can render and blend into: WebGL2
    // with EXT_color_buffer_float (or its half-float sibling), which desktop
    // GPUs have. Without them the value modes keep the additive glow.
    const isGL2 = typeof WebGL2RenderingContext !== "undefined" && gl instanceof WebGL2RenderingContext;
    this._canAverage = isGL2 &&
      !!(gl.getExtension("EXT_color_buffer_float") || gl.getExtension("EXT_color_buffer_half_float"));
    if (this._canAverage) {
      try {
        this._accumProgram = linkProgram(gl, VERT_SRC, ACCUM_FRAG_SRC);
        this._accumLoc = {
          a_pos:      gl.getAttribLocation(this._accumProgram, "a_pos"),
          a_perp:     gl.getAttribLocation(this._accumProgram, "a_perp"),
          a_side:     gl.getAttribLocation(this._accumProgram, "a_side"),
          a_color:    gl.getAttribLocation(this._accumProgram, "a_color"),
          u_matrix:   gl.getUniformLocation(this._accumProgram, "u_matrix"),
          u_widthNDC: gl.getUniformLocation(this._accumProgram, "u_widthNDC"),
          u_aspect:   gl.getUniformLocation(this._accumProgram, "u_aspect"),
        };
        this._compositeProgram = linkProgram(gl, QUAD_VERT_SRC, COMPOSITE_FRAG_SRC);
        const cp = this._compositeProgram;
        this._compositeLoc = {
          a_pos:        gl.getAttribLocation(cp, "a_pos"),
          u_accum:      gl.getUniformLocation(cp, "u_accum"),
          u_size:       gl.getUniformLocation(cp, "u_size"),
          u_opacity:    gl.getUniformLocation(cp, "u_opacity"),
          u_lift:       gl.getUniformLocation(cp, "u_lift"),
          u_liftRoutes: gl.getUniformLocation(cp, "u_liftRoutes"),
          u_dark:       gl.getUniformLocation(cp, "u_dark"),
        };
        this._quadBuf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, this._quadBuf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      } catch {
        this._canAverage = false;
      }
    }
    if (this._verts) this._upload();
  }

  setData(verts, count) {
    this._verts = verts;
    this._count = count;
    if (this._gl && this._buf) this._upload();
    this._map?.triggerRepaint();
  }

  setStyle({ halfWidthPx, intensity, average }) {
    if (halfWidthPx != null) this._halfWidthPx = halfWidthPx;
    if (intensity != null) this._intensity = intensity;
    if (average != null) this._average = !!average;
    this._map?.triggerRepaint();
  }

  _averaging() {
    return this._average && this._canAverage;
  }

  _upload() {
    const gl = this._gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this._buf);
    gl.bufferData(gl.ARRAY_BUFFER, this._verts, gl.STATIC_DRAW);
  }

  // MapLibre v4 passes the mercator matrix directly; newer/globe paths pass an
  // args object — support both.
  _matrix(args) {
    return (args && args.defaultProjectionData) ? args.defaultProjectionData.mainMatrix : args;
  }

  // Widen lines as you zoom out so short, dense segments don't wash to nothing
  // (mirrors the old per-zoom weight); tapers to the base width when zoomed in.
  _halfWidth() {
    const zoom = this._map ? this._map.getZoom() : 12;
    return Math.min(this._halfWidthPx + Math.max(0, 11 - zoom) * 0.225, 6.25);
  }

  // Bind the track geometry to a program's attributes and set the shared uniforms.
  _bindLines(gl, loc, args) {
    const h = gl.drawingBufferHeight || 1;
    const w = gl.drawingBufferWidth || 1;
    gl.uniformMatrix4fv(loc.u_matrix, false, this._matrix(args));
    gl.uniform1f(loc.u_widthNDC, (2 * this._halfWidth()) / h);
    gl.uniform1f(loc.u_aspect, w / h);

    gl.bindBuffer(gl.ARRAY_BUFFER, this._buf);
    const S = FLOATS_PER_VERT * 4;
    gl.enableVertexAttribArray(loc.a_pos);
    gl.vertexAttribPointer(loc.a_pos, 2, gl.FLOAT, false, S, 0);
    gl.enableVertexAttribArray(loc.a_perp);
    gl.vertexAttribPointer(loc.a_perp, 2, gl.FLOAT, false, S, 8);
    gl.enableVertexAttribArray(loc.a_side);
    gl.vertexAttribPointer(loc.a_side, 1, gl.FLOAT, false, S, 16);
    gl.enableVertexAttribArray(loc.a_color);
    gl.vertexAttribPointer(loc.a_color, 3, gl.FLOAT, false, S, 20);
  }

  _unbindLines(gl, loc) {
    gl.disableVertexAttribArray(loc.a_pos);
    gl.disableVertexAttribArray(loc.a_perp);
    gl.disableVertexAttribArray(loc.a_side);
    gl.disableVertexAttribArray(loc.a_color);
  }

  // The float texture the averaged modes sum into, sized to the drawing buffer.
  _accumTarget(gl) {
    const w = gl.drawingBufferWidth || 1;
    const h = gl.drawingBufferHeight || 1;
    if (this._accum && this._accum.w === w && this._accum.h === h) return this._accum;
    if (this._accum) {
      gl.deleteFramebuffer(this._accum.fbo);
      gl.deleteTexture(this._accum.tex);
    }
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    this._accum = { fbo, tex, w, h };
    return this._accum;
  }

  // Pass one of the averaged modes, before MapLibre draws the map: sum every
  // track into the float texture. Runs in MapLibre's offscreen pass, which
  // exists for exactly this; the binding and viewport it had are put back.
  prerender(gl, args) {
    if (!this._count || !this._averaging()) return;
    const prevFbo = gl.getParameter(gl.FRAMEBUFFER_BINDING);
    const prevViewport = gl.getParameter(gl.VIEWPORT);
    const target = this._accumTarget(gl);
    gl.bindFramebuffer(gl.FRAMEBUFFER, target.fbo);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
      // Some drivers advertise the extension and still refuse the format.
      this._canAverage = false;
      gl.bindFramebuffer(gl.FRAMEBUFFER, prevFbo);
      return;
    }
    gl.viewport(0, 0, target.w, target.h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    gl.useProgram(this._accumProgram);
    this._bindLines(gl, this._accumLoc, args);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.STENCIL_TEST);
    gl.enable(gl.BLEND);
    gl.blendEquation(gl.FUNC_ADD);
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.drawArrays(gl.TRIANGLES, 0, this._count);
    this._unbindLines(gl, this._accumLoc);

    gl.bindFramebuffer(gl.FRAMEBUFFER, prevFbo);
    gl.viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
  }

  render(gl, args) {
    if (!this._count || !this._program) return;
    if (this._averaging() && this._accum) {
      this._composite(gl);
      return;
    }

    gl.useProgram(this._program);
    this._bindLines(gl, this._loc, args);
    gl.uniform1f(this._loc.u_intensity, this._intensity);
    gl.uniform1f(this._loc.u_subtract, this._subtractive ? 1 : 0);

    gl.enable(gl.BLEND);
    if (this._subtractive) {
      // dst − src for colour; alpha left alone, or the canvas would turn
      // see-through wherever a track was drawn.
      gl.blendEquationSeparate(gl.FUNC_REVERSE_SUBTRACT, gl.FUNC_ADD);
      gl.blendFuncSeparate(gl.ONE, gl.ONE, gl.ZERO, gl.ONE);
    } else {
      gl.blendEquation(gl.FUNC_ADD);
      gl.blendFunc(gl.ONE, gl.ONE);   // additive — overlaps accumulate brightness
    }
    gl.disable(gl.DEPTH_TEST);
    gl.drawArrays(gl.TRIANGLES, 0, this._count);
    this._unbindLines(gl, this._loc);
  }

  // Pass two: the sums from prerender, as the average colour, over the map.
  _composite(gl) {
    const loc = this._compositeLoc;
    gl.useProgram(this._compositeProgram);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this._accum.tex);
    gl.uniform1i(loc.u_accum, 0);
    gl.uniform2f(loc.u_size, this._accum.w, this._accum.h);
    gl.uniform1f(loc.u_opacity, AVERAGE_STYLE.opacity);
    gl.uniform1f(loc.u_lift, AVERAGE_STYLE.lift);
    gl.uniform1f(loc.u_liftRoutes, AVERAGE_STYLE.liftRoutes);
    gl.uniform1f(loc.u_dark, this._subtractive ? 0 : 1);

    gl.bindBuffer(gl.ARRAY_BUFFER, this._quadBuf);
    gl.enableVertexAttribArray(loc.a_pos);
    gl.vertexAttribPointer(loc.a_pos, 2, gl.FLOAT, false, 0, 0);
    gl.enable(gl.BLEND);
    // Over the map, not added to it — the colour is the average, not a sum.
    gl.blendEquation(gl.FUNC_ADD);
    gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE);
    gl.disable(gl.DEPTH_TEST);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.disableVertexAttribArray(loc.a_pos);
  }

  onRemove() {
    const gl = this._gl;
    if (!gl) return;
    if (this._program) gl.deleteProgram(this._program);
    if (this._buf) gl.deleteBuffer(this._buf);
    if (this._accumProgram) gl.deleteProgram(this._accumProgram);
    if (this._compositeProgram) gl.deleteProgram(this._compositeProgram);
    if (this._quadBuf) gl.deleteBuffer(this._quadBuf);
    if (this._accum) {
      gl.deleteFramebuffer(this._accum.fbo);
      gl.deleteTexture(this._accum.tex);
      this._accum = null;
    }
  }
}

// ── Geometry/colour building ──────────────────────────────────────────────────

// Frequency base colour (orange); additive accumulation ramps it to yellow→white.
const ORANGE_GLOW = [1.0, 0.40, 0.05];
const SPEED_GLOW = [
  [0, [41, 98, 255]], [0.4, [16, 185, 129]], [0.7, [251, 191, 36]], [1, [239, 68, 68]],
];
const GRAD_GLOW = [
  [0, [16, 185, 129]], [0.5, [255, 255, 255]], [1, [139, 92, 246]],
];

// Light mode's colours, as the inks a line leaves on white. Deeper and more
// saturated than the glow colours, since they darken a pale map rather than
// light a dark one; the gradient's "flat" is slate, because white is no ink.
export const LIGHT_INKS = {
  frequency: [234, 72, 12],
  speed: [[0, [29, 78, 216]], [0.4, [4, 140, 98]], [0.7, [217, 119, 6]], [1, [220, 38, 38]]],
  grad:  [[0, [4, 140, 98]], [0.5, [100, 116, 139]], [1, [124, 58, 237]]],
};

const hex = (rgb) => "#" + rgb.map((c) => c.toString(16).padStart(2, "0")).join("");

/** The legend's colours for a mode — the inks in light mode, the glow in dark. */
export function legendStops(mode, theme) {
  const light = theme === "light";
  const stops = mode === "gradient" ? (light ? LIGHT_INKS.grad : GRAD_GLOW) : (light ? LIGHT_INKS.speed : SPEED_GLOW);
  return stops.map(([, rgb]) => hex(rgb));
}

function rgbFromStopsRaw(stops, t) {
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
// Colours are what the line shows — the glow in dark mode, the ink in light;
// the additive shader takes the ink's complement itself when it subtracts,
// and the averaged modes need the ink as it is.
export function buildHeatmapVerts(tracks, mode, theme = "dark") {
  if (!tracks?.length) return { verts: new Float32Array(0), count: 0 };
  const light = theme === "light";
  const ORANGE = light ? LIGHT_INKS.frequency.map((c) => c / 255) : ORANGE_GLOW;
  const SPEED_STOPS = light ? LIGHT_INKS.speed : SPEED_GLOW;
  const GRAD_STOPS = light ? LIGHT_INKS.grad : GRAD_GLOW;
  const rgbFromStops = rgbFromStopsRaw;

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
// glow builds where routes overlap); value modes average (see the header), and
// their intensity is only the additive fallback's.
export function glowStyleForMode(mode) {
  // Track half-widths bumped ~25% from the original 2.2 / 2.0.
  if (mode === "frequency") return { halfWidthPx: 2.75, intensity: 0.18, average: false };
  return { halfWidthPx: 2.5, intensity: 0.55, average: true };
}

// The averaged modes' composite: how fast colour turns opaque with weight
// (one route about half, two most of the way), how much a pile of routes
// lifts it, and how many routes it takes to get most of that lift. The
// phone's HeatmapGlow.kt uses the same three.
const AVERAGE_STYLE = { opacity: 0.8, lift: 0.35, liftRoutes: 8 };
