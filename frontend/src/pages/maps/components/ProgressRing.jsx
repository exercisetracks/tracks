// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Circular progress wheel (SVG). `percent` 0-100; uses currentColor so the
// caller controls hue via text-* classes. Indeterminate (spins) when percent
// is null/undefined — for phases with no byte progress (e.g. tile merge).
export default function ProgressRing({ percent, size = 30, stroke = 3, className = "" }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const indeterminate = percent == null;
  const pct = Math.max(0, Math.min(100, percent || 0));
  const offset = c * (1 - pct / 100);

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      className={`${className} ${indeterminate ? "animate-spin" : ""}`}
    >
      <circle
        cx={size / 2} cy={size / 2} r={r}
        fill="none" stroke="currentColor" strokeOpacity="0.2" strokeWidth={stroke}
      />
      <circle
        cx={size / 2} cy={size / 2} r={r}
        fill="none" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={c}
        strokeDashoffset={indeterminate ? c * 0.75 : offset}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
        style={{ transition: indeterminate ? "none" : "stroke-dashoffset 0.4s ease" }}
      />
    </svg>
  );
}
