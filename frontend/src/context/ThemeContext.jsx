// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { createContext, useContext, useState, useEffect, useCallback } from "react";
import { api } from "../api/client";

const STORAGE_KEY_SCHEME = "tracks-color-scheme";
const STORAGE_KEY_ACCENT = "tracks-accent";

const ACCENT_PRESETS = {
  emerald: {
    name: "Emerald",
    light: { 50: "#ecfdf5", 100: "#d1fae5", 200: "#a7f3d0", 300: "#6ee7b7", 400: "#34d399", 500: "#10b981", 600: "#059669", 700: "#047857", 800: "#065f46", 900: "#064e3b", 950: "#022c22" },
    dark:  { 50: "#022c22", 100: "#064e3b", 200: "#065f46", 300: "#34d399", 400: "#6ee7b7", 500: "#10b981", 600: "#34d399", 700: "#6ee7b7", 800: "#047857", 900: "#064e3b", 950: "#022c22" },
  },
  blue: {
    name: "Blue",
    light: { 50: "#eff6ff", 100: "#dbeafe", 200: "#bfdbfe", 300: "#93c5fd", 400: "#60a5fa", 500: "#3b82f6", 600: "#2563eb", 700: "#1d4ed8", 800: "#1e40af", 900: "#1e3a8a", 950: "#172554" },
    dark:  { 50: "#172554", 100: "#1e3a8a", 200: "#1e40af", 300: "#60a5fa", 400: "#93c5fd", 500: "#3b82f6", 600: "#60a5fa", 700: "#93c5fd", 800: "#1d4ed8", 900: "#1e3a8a", 950: "#172554" },
  },
  violet: {
    name: "Violet",
    light: { 50: "#f5f3ff", 100: "#ede9fe", 200: "#ddd6fe", 300: "#c4b5fd", 400: "#a78bfa", 500: "#8b5cf6", 600: "#7c3aed", 700: "#6d28d9", 800: "#5b21b6", 900: "#4c1d95", 950: "#2e1065" },
    dark:  { 50: "#2e1065", 100: "#4c1d95", 200: "#5b21b6", 300: "#a78bfa", 400: "#c4b5fd", 500: "#8b5cf6", 600: "#a78bfa", 700: "#c4b5fd", 800: "#6d28d9", 900: "#4c1d95", 950: "#2e1065" },
  },
  rose: {
    name: "Rose",
    light: { 50: "#fff1f2", 100: "#ffe4e6", 200: "#fecdd3", 300: "#fda4af", 400: "#fb7185", 500: "#f43f5e", 600: "#e11d48", 700: "#be123c", 800: "#9f1239", 900: "#881337", 950: "#4c0519" },
    dark:  { 50: "#4c0519", 100: "#881337", 200: "#9f1239", 300: "#fb7185", 400: "#fda4af", 500: "#f43f5e", 600: "#fb7185", 700: "#fda4af", 800: "#be123c", 900: "#881337", 950: "#4c0519" },
  },
  amber: {
    name: "Amber",
    light: { 50: "#fffbeb", 100: "#fef3c7", 200: "#fde68a", 300: "#fcd34d", 400: "#fbbf24", 500: "#f59e0b", 600: "#d97706", 700: "#b45309", 800: "#92400e", 900: "#78350f", 950: "#451a03" },
    dark:  { 50: "#451a03", 100: "#78350f", 200: "#92400e", 300: "#fbbf24", 400: "#fcd34d", 500: "#f59e0b", 600: "#fbbf24", 700: "#fcd34d", 800: "#b45309", 900: "#78350f", 950: "#451a03" },
  },
  sky: {
    name: "Sky",
    light: { 50: "#f0f9ff", 100: "#e0f2fe", 200: "#bae6fd", 300: "#7dd3fc", 400: "#38bdf8", 500: "#0ea5e9", 600: "#0284c7", 700: "#0369a1", 800: "#075985", 900: "#0c4a6e", 950: "#082f49" },
    dark:  { 50: "#082f49", 100: "#0c4a6e", 200: "#075985", 300: "#38bdf8", 400: "#7dd3fc", 500: "#0ea5e9", 600: "#38bdf8", 700: "#7dd3fc", 800: "#0369a1", 900: "#0c4a6e", 950: "#082f49" },
  },
  teal: {
    name: "Teal",
    light: { 50: "#f0fdfa", 100: "#ccfbf1", 200: "#99f6e4", 300: "#5eead4", 400: "#2dd4bf", 500: "#14b8a6", 600: "#0d9488", 700: "#0f766e", 800: "#115e59", 900: "#134e4a", 950: "#042f2e" },
    dark:  { 50: "#042f2e", 100: "#134e4a", 200: "#115e59", 300: "#2dd4bf", 400: "#5eead4", 500: "#14b8a6", 600: "#2dd4bf", 700: "#5eead4", 800: "#0f766e", 900: "#134e4a", 950: "#042f2e" },
  },
};

const DEFAULT_ACCENT = "emerald";
const SCHEMES = ["system", "light", "dark"];

function hexToRgb(hex) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `${r} ${g} ${b}`;
}

function isHexAccent(v) {
  return typeof v === "string" && /^#[0-9a-fA-F]{6}$/.test(v);
}

// Build a Tailwind-like 50→950 shade scale from a single base hex (treated as the
// 600 level). Lighter shades mix toward white, darker toward black — so a custom
// accent colour behaves like the built-in presets across the whole UI. One palette
// is used for light and dark (the presets hand-tune dark; custom is good enough).
function paletteFromHex(hex) {
  const base = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
  const mix = (a, b, t) => Math.round(a + (b - a) * t);
  const toward = (target, t) => base.map((c) => mix(c, target, t));
  // ratio toward white (positive) for light shades, toward black for dark shades.
  const STOPS = {
    50: ["w", 0.92], 100: ["w", 0.82], 200: ["w", 0.64], 300: ["w", 0.44],
    400: ["w", 0.22], 500: ["w", 0.10], 600: ["n", 0], 700: ["b", 0.18],
    800: ["b", 0.34], 900: ["b", 0.48], 950: ["b", 0.66],
  };
  const out = {};
  for (const [shade, [dir, t]] of Object.entries(STOPS)) {
    const rgb = dir === "n" ? base : toward(dir === "w" ? 255 : 0, t);
    out[shade] = `${rgb[0]} ${rgb[1]} ${rgb[2]}`;
  }
  return out;
}

function applyAccentPalette(accentKey, isDark) {
  const root = document.documentElement;
  let rgbShades;
  if (isHexAccent(accentKey)) {
    rgbShades = paletteFromHex(accentKey);
  } else {
    const preset = ACCENT_PRESETS[accentKey] || ACCENT_PRESETS[DEFAULT_ACCENT];
    const shades = isDark ? preset.dark : preset.light;
    rgbShades = Object.fromEntries(Object.entries(shades).map(([s, hex]) => [s, hexToRgb(hex)]));
  }
  for (const s of [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950]) {
    root.style.setProperty(`--accent-${s}`, rgbShades[s]);
  }
  root.setAttribute("data-accent", isHexAccent(accentKey) ? "custom" : accentKey);
}

function applyColorScheme(scheme, accentKey) {
  const root = document.documentElement;
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const isDark = scheme === "dark" || (scheme === "system" && systemDark);
  root.classList.toggle("dark", isDark);
  applyAccentPalette(accentKey, isDark);
}

const ThemeContext = createContext(null);

export function ThemeProvider({ children }) {
  const [colorScheme, setColorSchemeState] = useState(() => {
    try { return localStorage.getItem(STORAGE_KEY_SCHEME) || "system"; }
    catch { return "system"; }
  });

  const [accent, setAccentState] = useState(() => {
    try { return localStorage.getItem(STORAGE_KEY_ACCENT) || DEFAULT_ACCENT; }
    catch { return DEFAULT_ACCENT; }
  });

  const [ready, setReady] = useState(false);

  // Both the accent and the colour scheme are account settings.
  //
  // The scheme used to stay in this browser, on the reasoning that light versus
  // dark depends on the device in your hand. The phone and the web are meant to
  // look like one product, so it now lives on the account (and syncs to phones
  // with every other setting); "system" is still a choice, for anyone who wants
  // each device to follow its own OS.
  //
  // localStorage is kept as a cache: it paints the first frame before the
  // request lands and keeps the choice on a reload with no network. The
  // server's answer wins when it arrives.
  useEffect(() => {
    let cancelled = false;
    api.getSettings()
      .then((settings) => {
        if (cancelled) return;
        const mode = settings?.theme_mode;
        if (SCHEMES.includes(mode)) {
          setColorSchemeState(mode);
          try { localStorage.setItem(STORAGE_KEY_SCHEME, mode); } catch { /* noop */ }
        }
        const remote = settings?.accent_color;
        if (!remote) return;
        if (!ACCENT_PRESETS[remote] && !isHexAccent(remote)) return;
        setAccentState(remote);
        try { localStorage.setItem(STORAGE_KEY_ACCENT, remote); } catch { /* noop */ }
      })
      // Signed out, offline, or an older server: keep whatever is local.
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    applyColorScheme(colorScheme, accent);
    setReady(true);

    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    function handleChange() {
      if (colorScheme === "system") {
        applyColorScheme("system", accent);
      }
    }
    mq.addEventListener("change", handleChange);
    return () => mq.removeEventListener("change", handleChange);
  }, [colorScheme, accent]);

  const setColorScheme = useCallback((scheme) => {
    if (!SCHEMES.includes(scheme)) return;
    setColorSchemeState(scheme);
    try { localStorage.setItem(STORAGE_KEY_SCHEME, scheme); } catch { /* noop */ }
    // As with the accent: applied locally first, pushed after.
    api.updateSettings({ theme_mode: scheme }).catch(() => {});
  }, []);

  const setAccent = useCallback((key) => {
    // Accepts a preset key OR an arbitrary #rrggbb custom colour.
    if (!ACCENT_PRESETS[key] && !isHexAccent(key)) return;
    setAccentState(key);
    try { localStorage.setItem(STORAGE_KEY_ACCENT, key); } catch { /* noop */ }
    // Applied locally first and pushed after, so the UI never waits on a
    // request to change a colour. A failed write costs the sync, not the tap.
    api.updateSettings({ accent_color: key }).catch(() => {});
  }, []);

  return (
    <ThemeContext.Provider value={{ colorScheme, setColorScheme, accent, setAccent, accentPresets: ACCENT_PRESETS, ready }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}

export { ACCENT_PRESETS, DEFAULT_ACCENT };
