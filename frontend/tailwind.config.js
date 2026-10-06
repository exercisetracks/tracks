// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { designTheme, designComponents } from "./src/design/tokens.js";
import kit from "./src/design/kit.js";

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  darkMode: "class",
  theme: {
    extend: {
      // Radii, spacing, type, tracking and the font come from spec/design.yaml,
      // shared with the phone. Colour stays here: it is the user's accent.
      ...designTheme,
      colors: {
        accent: {
          50:  "rgb(var(--accent-50) / <alpha-value>)",
          100: "rgb(var(--accent-100) / <alpha-value>)",
          200: "rgb(var(--accent-200) / <alpha-value>)",
          300: "rgb(var(--accent-300) / <alpha-value>)",
          400: "rgb(var(--accent-400) / <alpha-value>)",
          500: "rgb(var(--accent-500) / <alpha-value>)",
          600: "rgb(var(--accent-600) / <alpha-value>)",
          700: "rgb(var(--accent-700) / <alpha-value>)",
          800: "rgb(var(--accent-800) / <alpha-value>)",
          900: "rgb(var(--accent-900) / <alpha-value>)",
          950: "rgb(var(--accent-950) / <alpha-value>)",
        },
      },
      animation: {
        "slide-bar": "slideBar 1.6s ease-in-out infinite",
      },
      keyframes: {
        slideBar: {
          "0%": { transform: "translateX(-100%)" },
          "50%": { transform: "translateX(80%)" },
          "100%": { transform: "translateX(200%)" },
        },
      },
    },
  },
  // buttons: the .btn family below. kit: every other shared control —
  // card, section-title, bar-pill, chip, choice, switch, field, modal
  // (src/design/kit.js).
  plugins: [buttons, kit],
};

/**
 * The one button family, shared with the phone's PillButtons.kt: `.btn` plus
 * one of `.btn-primary` / `.btn-tonal` / `.btn-neutral` / `.btn-danger`, and
 * `.btn-sm` for rows that are dense by necessity.
 *
 * Geometry and tint strength come from spec/design.yaml `components.button`,
 * so a change there moves both apps; only the colours are chosen here, from
 * the user's accent. The web had grown bare accent-coloured text links,
 * outlined boxes and filled bubbles side by side, which read as three kinds of
 * control — every variant now has the same shape, and only colour says how
 * much an action matters.
 */
function buttons({ addComponents, theme }) {
  const b = designComponents.button;
  const sp = theme("spacing");
  const [size, { lineHeight }] = theme("fontSize")[b.type];
  const tint = (rgb, a) => `rgb(${rgb} / ${a})`;
  addComponents({
    ".btn": {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      gap: sp["1.5"],
      flexShrink: "0",
      height: sp[b.height],
      padding: `${sp[b.padding_y]} ${sp[b.padding_x]}`,
      borderRadius: theme("borderRadius")[b.radius],
      fontSize: size,
      lineHeight,
      fontWeight: String(theme("fontWeight")[b.weight]),
      whiteSpace: "nowrap",
      userSelect: "none",
      transitionProperty: "background-color, color",
      transitionDuration: "150ms",
      "&:disabled": { opacity: "0.5", cursor: "not-allowed" },
      "& svg": { width: "1.125rem", height: "1.125rem", flexShrink: "0" },
    },
    ".btn-sm": {
      height: sp[b.height_sm],
      padding: `0 ${sp[b.padding_x_sm]}`,
      gap: sp["1"],
      "& svg": { width: "1rem", height: "1rem" },
    },
    ".btn-primary": {
      backgroundColor: "rgb(var(--accent-600))",
      color: "#fff",
      "&:hover:not(:disabled)": { backgroundColor: "rgb(var(--accent-700))" },
    },
    // In dark mode the accent is remapped light, so white on it loses
    // contrast; the phone does the same with Material's onPrimary.
    ".dark .btn-primary": {
      color: "rgb(2 6 23)",
      "&:hover:not(:disabled)": { backgroundColor: "rgb(var(--accent-500))" },
    },
    ".btn-tonal": {
      backgroundColor: tint("var(--accent-600)", b.tonal_alpha),
      color: "rgb(var(--accent-700))",
      "&:hover:not(:disabled)": { backgroundColor: tint("var(--accent-600)", b.tonal_alpha * 2) },
    },
    ".btn-neutral": {
      backgroundColor: tint("15 23 42", b.neutral_alpha),
      color: "rgb(51 65 85)",
      "&:hover:not(:disabled)": { backgroundColor: tint("15 23 42", b.neutral_alpha * 2) },
    },
    ".dark .btn-neutral": {
      backgroundColor: tint("255 255 255", b.neutral_alpha),
      color: "rgb(226 232 240)",
      "&:hover:not(:disabled)": { backgroundColor: tint("255 255 255", b.neutral_alpha * 2) },
    },
    ".btn-danger": {
      backgroundColor: tint("220 38 38", b.tonal_alpha),
      color: "rgb(185 28 28)",
      "&:hover:not(:disabled)": { backgroundColor: tint("220 38 38", b.tonal_alpha * 2) },
    },
    ".dark .btn-danger": {
      backgroundColor: tint("248 113 113", b.tonal_alpha),
      color: "rgb(248 113 113)",
    },
  });
}
