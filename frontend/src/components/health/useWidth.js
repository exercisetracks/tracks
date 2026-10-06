// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The rendered width of an element, kept current as it resizes.
//
// The hand-drawn health charts (sleep clock, hypnogram, stress) draw in real
// pixels rather than a stretched viewBox: stretching would distort their text
// and turn round dots into ovals. Recharts' ResponsiveContainer solves the same
// problem for the charts built on it.

import { useEffect, useRef, useState } from "react";

export default function useWidth(fallback = 600) {
  const ref = useRef(null);
  const [width, setWidth] = useState(fallback);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (el.clientWidth) setWidth(el.clientWidth);
    // Absent under jsdom, where the fallback is the whole story.
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      const w = Math.round(entry.contentRect.width);
      if (w > 0) setWidth(w);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return [ref, width];
}
