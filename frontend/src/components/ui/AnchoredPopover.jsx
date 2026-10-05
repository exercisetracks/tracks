// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Renders its children in a portal on document.body, anchored to `anchorRef`.
// Portalling is what lets a dropdown/calendar/colour popup escape an ancestor's
// `overflow-hidden` (e.g. the Settings Section card) instead of being clipped by
// it. The panel auto-flips above the anchor when there isn't room below, clamps
// horizontally to the viewport, and closes on outside-click / Escape / scroll.
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

export default function AnchoredPopover({
  anchorRef, open, onClose, children,
  align = "center",   // center | left | right (horizontal alignment to the anchor)
  gap = 8,            // px between anchor and panel
}) {
  const panelRef = useRef(null);
  const [pos, setPos] = useState(null);

  // Position before paint (useLayoutEffect) so there's no flash at -9999.
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const a = anchorRef.current?.getBoundingClientRect();
      const panel = panelRef.current;
      if (!a || !panel) return;
      const ph = panel.offsetHeight, pw = panel.offsetWidth;
      const spaceBelow = window.innerHeight - a.bottom;
      const openUp = spaceBelow < ph + gap && a.top > spaceBelow;
      const top = openUp ? a.top - ph - gap : a.bottom + gap;
      let left =
        align === "right" ? a.right - pw
        : align === "left" ? a.left
        : a.left + a.width / 2 - pw / 2;
      left = Math.max(8, Math.min(left, window.innerWidth - pw - 8));
      setPos({ top, left });
    };
    place();
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [open, align, gap, anchorRef]);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => {
      if (
        panelRef.current && !panelRef.current.contains(e.target) &&
        anchorRef.current && !anchorRef.current.contains(e.target)
      ) onClose?.();
    };
    const onKey = (e) => { if (e.key === "Escape") onClose?.(); };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, onClose, anchorRef]);

  if (!open) return null;
  return createPortal(
    <div
      ref={panelRef}
      style={{ position: "fixed", top: pos?.top ?? -9999, left: pos?.left ?? -9999, zIndex: 70 }}
    >
      {children}
    </div>,
    document.body,
  );
}
