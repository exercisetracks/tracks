// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The one dialog shell: backdrop, panel, title row with a close button,
// Escape and click-outside to dismiss. Every modal had written its own, at
// four backdrop strengths, two corner radii and with or without a border.
//
// Portalled to <body>: rendered in place, a dialog inherits whatever the page
// does to its children — the Health page's `space-y-8` gave the "fixed"
// backdrop a 2rem top margin, leaving a bright strip above the dimmed page.
import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";

export function CloseButton({ onClick, label = "Close" }) {
  return (
    <button type="button" onClick={onClick} aria-label={label} className="icon-btn">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </button>
  );
}

/**
 * `title` and `subtitle` make the header row; `header` replaces the title
 * with anything else (a title with a date picker beside it). Without either
 * the panel is bare and the caller draws its own top. `width` is a max-w
 * utility; `padded={false}` for a panel that lays out its own regions (a
 * fixed header over a scrolling body).
 */
export default function Modal({
  title, subtitle, header, onClose, children,
  width = "max-w-lg", padded = true, className = "", z = "", label,
}) {
  const backdrop = useRef(null);
  const pressedOnBackdrop = useRef(false);

  useEffect(() => {
    if (!onClose) return undefined;
    const onKey = e => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const hasHeader = title || header;
  return createPortal(
    <div
      ref={backdrop}
      className={`modal-backdrop ${z}`}
      // Only a press that starts and ends on the backdrop closes: a text
      // selection dragged out of a field must not dismiss the form.
      onMouseDown={e => { pressedOnBackdrop.current = e.target === backdrop.current; }}
      onClick={e => { if (onClose && pressedOnBackdrop.current && e.target === backdrop.current) onClose(); }}
    >
      <div
        role="dialog" aria-modal="true" aria-label={label ?? (typeof title === "string" ? title : undefined)}
        className={`modal ${width} ${padded ? "p-5 space-y-4" : "flex flex-col"} ${className}`}
      >
        {hasHeader && (
          <div className={`flex items-start justify-between gap-3 ${padded ? "" : "px-5 pt-5 pb-3"}`}>
            <div className="min-w-0">
              {header ?? <h2 className="modal-title">{title}</h2>}
              {subtitle && <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{subtitle}</div>}
            </div>
            {onClose && <CloseButton onClick={onClose} />}
          </div>
        )}
        {children}
      </div>
    </div>,
    document.body,
  );
}
