// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The "?" that explains a figure or a choice — the phone's InfoTip. Hover
// (and keyboard focus) opens it on the desktop; it flips to the left when it
// sits in the right half of the viewport so it never clips off-edge.
//
// There were three identical copies of this (Fitness chart, activity charts,
// the Grit Flow graph) plus the Health page's click-to-open InfoButton drawn
// in another grey; all are this dot now, drawn by .info-dot.
import { useRef, useState } from "react";

export default function InfoTooltip({ children, label = "What is this?" }) {
  const [open, setOpen] = useState(false);
  const [openLeft, setOpenLeft] = useState(false);
  const btnRef = useRef(null);

  function show() {
    const rect = btnRef.current?.getBoundingClientRect();
    if (rect) setOpenLeft(rect.left > window.innerWidth / 2);
    setOpen(true);
  }

  return (
    <span className="relative inline-flex shrink-0">
      <button
        ref={btnRef}
        type="button"
        aria-label={label}
        aria-expanded={open}
        onMouseEnter={show}
        onMouseLeave={() => setOpen(false)}
        onFocus={show}
        onBlur={() => setOpen(false)}
        className="info-dot"
      >
        ?
      </button>
      {open && (
        <div className={`absolute top-6 ${openLeft ? "right-0" : "left-0"} w-72 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg p-2.5 shadow-xl z-20 text-xs font-normal normal-case tracking-normal text-slate-600 dark:text-slate-300 space-y-2 pointer-events-none`}>
          {children}
        </div>
      )}
    </span>
  );
}
