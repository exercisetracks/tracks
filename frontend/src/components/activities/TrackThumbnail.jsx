// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// A route thumbnail for one activity row — the phone's (mobile
// ActivitiesScreen.kt, TrackThumbnail), drawn the same way.
//
// A drawing rather than a map: at a stamp's size a basemap is a smear of
// landcover that competes with the line, and what identifies a ride is its
// shape. So it is the outline on a dark panel, with a dark casing under the
// accent line (the panel is a gradient, and the route crosses both ends of
// it) and a white dot at the start so an out-and-back can be told from a loop.
// The panel is translucent, so on either theme it sits on the row rather than
// being a hole cut in it.
//
// It asks for its outline only once it scrolls into view, and through the
// shared queue in lib/trackOutlines.js, so the table never waits on it.

import { useEffect, useRef, useState } from "react";
import { cachedOutline, fetchOutline, outlineKey } from "../../lib/trackOutlines";

const W = 76;
const H = 44;
const PAD = 6;

export default function TrackThumbnail({ activity }) {
  const key = outlineKey(activity);
  const [shape, setShape] = useState(() => cachedOutline(key));
  const ref = useRef(null);

  useEffect(() => {
    setShape(cachedOutline(key));
    if (cachedOutline(key) !== undefined) return;
    const el = ref.current;
    if (!el) return;
    let live = true;
    const start = () => fetchOutline(activity.id, key).then((s) => { if (live) setShape(s); });
    if (typeof IntersectionObserver === "undefined") { start(); return () => { live = false; }; }
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) { io.disconnect(); start(); }
    }, { rootMargin: "200px" });
    io.observe(el);
    return () => { live = false; io.disconnect(); };
  }, [activity.id, key]);

  // Known to have no track: no panel at all, so indoor rows stay plain.
  if (shape === null) return null;

  return (
    <div
      ref={ref}
      aria-hidden="true"
      className="shrink-0 rounded-lg overflow-hidden"
      style={{ width: W, height: H, background: "linear-gradient(135deg, rgba(34,40,49,0.77), rgba(10,12,15,0.95))" }}
    >
      {shape && <Outline shape={shape} />}
    </div>
  );
}

function Outline({ shape }) {
  // Fitted to the points' own extent, aspect kept and centred, so a long
  // valley ride uses the box's width and a compact loop its height.
  const xs = shape.points.map((p) => p[0]);
  const ys = shape.points.map((p) => p[1]);
  const minX = Math.min(...xs), minY = Math.min(...ys);
  const w = Math.max(...xs) - minX, h = Math.max(...ys) - minY;
  const k = Math.min((W - 2 * PAD) / (w || 1e-9), (H - 2 * PAD) / (h || 1e-9));
  const ox = (W - w * k) / 2, oy = (H - h * k) / 2;
  const pts = shape.points.map(([x, y]) => [ox + (x - minX) * k, oy + (y - minY) * k]);
  const d = pts.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`).join("");
  const [sx, sy] = pts[0];
  return (
    <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block">
      <path d={d} fill="none" stroke="rgba(0,0,0,0.6)" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" />
      <path d={d} fill="none" stroke="rgb(var(--accent-500))" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={sx} cy={sy} r={2.5} fill="#fff" />
    </svg>
  );
}
