// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The colour opposite the accent on the colour wheel, for a second chart
// series that must not be mistaken for the first — the weekly-volume
// duration line over its distance bars.
//
// Hue turns 180° in HSL; saturation keeps the accent's but never drops below
// 0.55; lightness is fixed per theme, because an accent's own lightness is
// tuned for a fill and its complement at that value can vanish into the
// background. The phone computes the same thing (mobile ui/theme/Complement.kt)
// and both are pinned to one set of vectors, so one accent gives one pair of
// colours on both.

/** `rgb` is [r, g, b] in 0..255; returns "#RRGGBB". */
export function complementOf([r8, g8, b8], dark) {
  const r = r8 / 255, g = g8 / 255, b = b8 / 255;
  const hi = Math.max(r, g, b), lo = Math.min(r, g, b);
  const l0 = (hi + lo) / 2, d = hi - lo;
  const s0 = d === 0 ? 0 : l0 <= 0.5 ? d / (hi + lo) : d / (2 - hi - lo);
  let h = d === 0 ? 0 : hi === r ? ((g - b) / d) / 6 : hi === g ? (2 + (b - r) / d) / 6 : (4 + (r - g) / d) / 6;
  if (h < 0) h += 1;
  h = (h + 0.5) % 1;
  const s = Math.max(s0, 0.55);
  const l = dark ? 0.68 : 0.42;
  const m2 = l <= 0.5 ? l * (1 + s) : l + s - l * s;
  const m1 = 2 * l - m2;
  const channel = (hue) => {
    const x = ((hue % 1) + 1) % 1;
    const v = x < 1 / 6 ? m1 + (m2 - m1) * x * 6 : x < 0.5 ? m2 : x < 2 / 3 ? m1 + (m2 - m1) * (2 / 3 - x) * 6 : m1;
    return Math.floor(v * 255 + 0.5).toString(16).padStart(2, "0").toUpperCase();
  };
  return `#${channel(h + 1 / 3)}${channel(h)}${channel(h - 1 / 3)}`;
}

/** The live accent (`--accent-600`, which the theme remaps for dark mode) as [r, g, b]. */
export function currentAccentRgb() {
  const raw = getComputedStyle(document.documentElement).getPropertyValue("--accent-600").trim();
  const parts = raw.split(/[\s,]+/).map(Number);
  return parts.length === 3 && parts.every(Number.isFinite) ? parts : [5, 150, 105];
}
