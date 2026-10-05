// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The web's button: the `.btn` pill family from tailwind.config.js, the same
// four variants as the phone's PillButtons.kt. Choose by role — primary for
// the one action a screen or dialog exists for, danger for destructive,
// neutral for dismiss/cancel/skip, tonal for everything else. A plain
// <button className="btn btn-tonal"> is equally fine where a component would
// be in the way; this only saves spelling the classes.

const VARIANTS = new Set(["primary", "tonal", "neutral", "danger"]);

export function btn(variant = "tonal", { small = false, className = "" } = {}) {
  const v = VARIANTS.has(variant) ? variant : "tonal";
  return `btn btn-${v}${small ? " btn-sm" : ""}${className ? ` ${className}` : ""}`;
}

export default function Button({
  variant = "tonal", small = false, icon = null, className = "", type = "button", children, ...rest
}) {
  return (
    <button type={type} className={btn(variant, { small, className })} {...rest}>
      {icon}
      {children}
    </button>
  );
}

/** "+", sized by the `.btn svg` rule — for "Add …" actions, which are always tonal. */
export function PlusIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}
