// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The floating tip card for the active tour step. Non-blocking: there's no
// backdrop, so the whole page stays clickable — only this card and its highlight
// ring are drawn on top. The card anchors next to the step's target element
// (resolved from `data-tour="…"`), flipping sides and clamping to the viewport as
// needed; if the target is missing it falls back to a centered card.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTour } from "./TourContext";

const MARGIN = 8;   // keep the card this far from the viewport edge
const GAP = 12;     // space between anchor and card
const CARD_W = 288; // matches w-72

// Track the target element's viewport rect for the active step. Re-measures on
// scroll/resize and retries briefly if the element mounts a beat late.
function useAnchorRect(selector, activeKey) {
  const [rect, setRect] = useState(null);
  useLayoutEffect(() => {
    let cancelled = false;
    let detach = null;
    let attempts = 0;

    function attach(el) {
      const measure = () => { if (!cancelled) setRect(el.getBoundingClientRect()); };
      measure();
      window.addEventListener("scroll", measure, true);
      window.addEventListener("resize", measure);
      detach = () => {
        window.removeEventListener("scroll", measure, true);
        window.removeEventListener("resize", measure);
      };
    }

    function find() {
      if (cancelled) return;
      const el = selector ? document.querySelector(selector) : null;
      if (el) {
        try { el.scrollIntoView({ block: "center", inline: "nearest" }); } catch { /* noop */ }
        requestAnimationFrame(() => { if (!cancelled) attach(el); });
      } else if (selector && attempts < 20) {
        attempts += 1;
        setTimeout(find, 80);
      } else {
        setRect(null); // no anchor → centered fallback
      }
    }
    find();

    return () => { cancelled = true; if (detach) detach(); };
    // activeKey bundles tour id + step index so we re-resolve on every step.
  }, [selector, activeKey]);

  return rect;
}

function computePosition(rect, placement, cardH, vw, vh) {
  if (!rect) {
    return { top: Math.max(MARGIN, (vh - cardH) / 2), left: (vw - CARD_W) / 2, place: "center" };
  }
  let place = placement === "center" ? "bottom" : placement;

  // Flip to the opposite side when the preferred side lacks room but the other has it.
  if (place === "bottom" && rect.bottom + GAP + cardH > vh - MARGIN && rect.top - GAP - cardH > MARGIN) place = "top";
  else if (place === "top" && rect.top - GAP - cardH < MARGIN && rect.bottom + GAP + cardH < vh - MARGIN) place = "bottom";
  if (place === "right" && rect.right + GAP + CARD_W > vw - MARGIN && rect.left - GAP - CARD_W > MARGIN) place = "left";
  else if (place === "left" && rect.left - GAP - CARD_W < MARGIN && rect.right + GAP + CARD_W < vw - MARGIN) place = "right";

  let top, left;
  switch (place) {
    case "top":    top = rect.top - GAP - cardH;              left = rect.left + rect.width / 2 - CARD_W / 2; break;
    case "left":   left = rect.left - GAP - CARD_W;           top = rect.top + rect.height / 2 - cardH / 2;   break;
    case "right":  left = rect.right + GAP;                   top = rect.top + rect.height / 2 - cardH / 2;   break;
    case "bottom":
    default:       top = rect.bottom + GAP;                   left = rect.left + rect.width / 2 - CARD_W / 2; break;
  }
  left = Math.max(MARGIN, Math.min(left, vw - CARD_W - MARGIN));
  top  = Math.max(MARGIN, Math.min(top,  vh - cardH - MARGIN));
  return { top, left, place };
}

export default function TourTooltip() {
  const { activeTour, stepIndex, step, stepCount, next, prev, completeTour } = useTour();
  const selector = step?.anchor ?? null;
  const activeKey = activeTour ? `${activeTour}:${stepIndex}` : null;
  const rect = useAnchorRect(selector, activeKey);

  const cardRef = useRef(null);
  const [pos, setPos] = useState(null);

  // Position the card once it (and the anchor rect) are known. Depends on the
  // measured card height so top/left placements don't overshoot.
  useLayoutEffect(() => {
    if (!step) { setPos(null); return; }
    const cardH = cardRef.current?.offsetHeight ?? 160;
    setPos(computePosition(rect, step.placement, cardH, window.innerWidth, window.innerHeight));
  }, [rect, step, stepIndex]);

  // Escape dismisses the tour (counts as seen, like Skip).
  const onKey = useCallback((e) => { if (e.key === "Escape") completeTour(); }, [completeTour]);
  useEffect(() => {
    if (!activeTour) return;
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [activeTour, onKey]);

  if (!activeTour || !step) return null;

  const isLast = stepIndex >= stepCount - 1;
  const isFirst = stepIndex === 0;

  // Arrow offset along the card edge, pointing back at the anchor's center.
  let arrow = null;
  if (rect && pos && pos.place !== "center") {
    if (pos.place === "top" || pos.place === "bottom") {
      const x = Math.max(16, Math.min(rect.left + rect.width / 2 - pos.left, CARD_W - 16));
      arrow = { left: x, [pos.place === "top" ? "bottom" : "top"]: -5 };
    } else {
      const cardH = cardRef.current?.offsetHeight ?? 160;
      const y = Math.max(16, Math.min(rect.top + rect.height / 2 - pos.top, cardH - 16));
      arrow = { top: y, [pos.place === "left" ? "right" : "left"]: -5 };
    }
  }

  return (
    <>
      {/* Highlight ring around the anchor (never eats clicks). */}
      {rect && (
        <div
          className="fixed z-[55] rounded-lg ring-2 ring-accent-500 ring-offset-2 ring-offset-transparent pointer-events-none transition-all duration-150"
          style={{
            top: rect.top - 4,
            left: rect.left - 4,
            width: rect.width + 8,
            height: rect.height + 8,
          }}
        />
      )}

      {/* The tip card. */}
      <div
        ref={cardRef}
        role="dialog"
        aria-live="polite"
        className="fixed z-[60] w-72 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-xl p-3.5"
        style={{
          top: pos?.top ?? -9999,
          left: pos?.left ?? -9999,
          visibility: pos ? "visible" : "hidden",
        }}
      >
        {arrow && (
          <div
            className="absolute w-2.5 h-2.5 rotate-45 bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-700 border-b border-r"
            style={arrow}
          />
        )}

        <button
          type="button"
          onClick={completeTour}
          title="Dismiss"
          className="icon-btn absolute top-2.5 right-2.5"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>

        <h3 className="text-sm font-semibold text-slate-900 dark:text-white pr-4">{step.title}</h3>
        <p className="mt-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300">{step.body}</p>

        <div className="mt-3 flex items-center justify-between gap-2">
          <span className="text-[11px] font-medium tabular-nums text-slate-400 dark:text-slate-500">
            {stepIndex + 1} / {stepCount}
          </span>
          <div className="flex items-center gap-1.5">
            {stepCount > 1 && (
              <button
                type="button"
                onClick={completeTour}
                className="btn btn-neutral btn-sm"
              >
                Skip
              </button>
            )}
            {!isFirst && (
              <button
                type="button"
                onClick={prev}
                className="btn btn-neutral btn-sm"
              >
                Back
              </button>
            )}
            <button
              type="button"
              onClick={next}
              className="btn btn-primary btn-sm"
            >
              {isLast ? "Done" : "Next"}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
