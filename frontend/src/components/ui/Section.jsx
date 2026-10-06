// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A titled block of a page, and the card it usually holds — the phone's
// SectionCard / SettingsCard. The title sits *outside* the card, so it labels
// the group rather than heading a box, and it is the same .section-title on
// every page. Before this, Dashboard, Health, Goals, Race Plan and Settings
// each had their own Section, in three different looks.

/**
 * `action` sits at the right of the title row (a button, a save status);
 * `info` beside the title (an InfoTooltip). Children are laid out as given —
 * wrap them in <Card> when they are one card.
 */
export function Section({ title, action, info, children, className = "", dataTour }) {
  return (
    <section className={className} data-tour={dataTour}>
      {(title || action) && (
        <div className="flex items-center justify-between gap-3 mb-3 min-h-[1.25rem]">
          <div className="flex items-center gap-2 min-w-0">
            {title && <h2 className="section-title truncate">{title}</h2>}
            {info}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

/** The card. Rendered as a button when it has an `onClick`. */
export function Card({ children, className = "", onClick, ...rest }) {
  if (onClick) {
    return (
      <button type="button" onClick={onClick} className={`card card-interactive w-full ${className}`} {...rest}>
        {children}
      </button>
    );
  }
  return <div className={`card ${className}`} {...rest}>{children}</div>;
}
