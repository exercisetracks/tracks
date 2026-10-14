// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useCallback, useEffect, useRef } from "react";

// Esc closes the map page's panels one at a time, the last one used first —
// opened, or clicked in — as a desktop's windows do. Map clicks while drawing
// do not count as using the editor; opening it does.
//
// `panels` is `{ id: { open, close } }`, rebuilt each render. A panel becomes
// the most recent when it opens; `touch(id)` (wired to a pointer-down inside
// it) makes it so again. Esc is left alone while typing in a field, where it
// already means "undo this edit".
export function usePanelStack(panels) {
  const order = useRef([]);          // ids, most recent last
  const wasOpen = useRef(new Set());
  const panelsRef = useRef(panels);
  panelsRef.current = panels;

  const touch = useCallback((id) => {
    order.current = [...order.current.filter((x) => x !== id), id];
  }, []);

  // A panel that has just opened is the most recent.
  useEffect(() => {
    const open = new Set(Object.entries(panels).filter(([, p]) => p.open).map(([id]) => id));
    for (const id of open) if (!wasOpen.current.has(id)) touch(id);
    order.current = order.current.filter((id) => open.has(id));
    wasOpen.current = open;
  });

  useEffect(() => {
    const onKey = (e) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      const t = e.target;
      if (t && (t.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName))) return;
      for (let i = order.current.length - 1; i >= 0; i--) {
        const id = order.current[i];
        const p = panelsRef.current[id];
        if (p?.open) {
          e.preventDefault();
          order.current = order.current.filter((x) => x !== id);
          p.close();
          return;
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return touch;
}
