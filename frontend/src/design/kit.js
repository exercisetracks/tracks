// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The web's control kit: every recurring piece of chrome the desktop draws,
// as one Tailwind component class each, so a page names *what* a thing is
// (`card`, `chip`, `field`) rather than re-typing how it looks.
//
// Why this exists: each feature folder had grown its own Section, its own
// input-class string and its own switch, each a few pixels or a shade off the
// next — three section-heading styles, five chip variants in one form, seven
// modal backdrops. The phone had already been unified around shared Compose
// components; this is the same set for the browser, and each entry names the
// phone component it mirrors so the two can be read side by side.
//
// Geometry comes from spec/design.yaml where the spec has it (card, pill,
// section header). Where it does not, the value is the phone component's own,
// copied with a pointer to it. Colour is chosen here from the user's accent
// (the --accent-* variables, which dark mode remaps: accent-100 is a light
// tint in light mode and a deep one in dark, accent-700 the reverse — the same
// pairing as Material's primaryContainer / onPrimaryContainer).
//
// Components sit below utilities in Tailwind's cascade, so `card p-0` or
// `field w-24` override the kit where a page genuinely needs to.

import { designComponents } from "./tokens.js";

const accent = (shade, a) => (a == null ? `rgb(var(--accent-${shade}))` : `rgb(var(--accent-${shade}) / ${a})`);

// Tailwind's slate, the web's neutral ramp.
const slate = {
  50: "248 250 252", 100: "241 245 249", 200: "226 232 240", 300: "203 213 225",
  400: "148 163 184", 500: "100 116 139", 600: "71 85 105", 700: "51 65 85",
  800: "30 41 59", 900: "15 23 42", 950: "2 6 23",
};
const s = (shade, a) => (a == null ? `rgb(${slate[shade]})` : `rgb(${slate[shade]} / ${a})`);

// A selected state is reached by aria (what the control already announces) or
// by `.is-on` where the element is not a button.
const on = (cls) => `${cls}[aria-pressed="true"], ${cls}[aria-checked="true"], ${cls}[aria-selected="true"], ${cls}.is-on`;

export default function kit({ addComponents, theme }) {
  const sp = theme("spacing");
  const r = theme("borderRadius");
  const type = (key) => {
    const [fontSize, { lineHeight }] = theme("fontSize")[key];
    return { fontSize, lineHeight };
  };
  const w = (key) => String(theme("fontWeight")[key]);
  const tracking = theme("letterSpacing");
  const c = designComponents;

  addComponents({
    // ── Surfaces ────────────────────────────────────────────────────────────
    // spec card: the web's white card with a hairline border (the phone fills
    // instead; the border is the web's deliberate version of the same card).
    ".card": {
      backgroundColor: "#fff",
      border: `${c.card.border}px solid ${s(200)}`,
      borderRadius: r[c.card.radius],
      padding: sp[c.card.padding],
    },
    ".dark .card": { backgroundColor: s(900), borderColor: s(800) },
    // A card that is a button or link: the whole surface answers the pointer.
    ".card-interactive": {
      textAlign: "left",
      transitionProperty: "border-color, background-color",
      transitionDuration: "150ms",
      "&:hover": { borderColor: s(300) },
    },
    ".dark .card-interactive:hover": { borderColor: s(700) },

    // spec section_header: the label above a card ("OVERVIEW") — Dashboard's
    // SectionCard and Settings' SettingsCard on the phone put it outside the
    // card, so it reads as naming the group rather than as a heading in a box.
    ".section-title": {
      ...type(c.section_header.type),
      fontWeight: w(c.section_header.weight),
      letterSpacing: tracking[c.section_header.tracking],
      textTransform: "uppercase",
      color: s(400),
    },
    ".dark .section-title": { color: s(500) },

    // spec pill: status badges ("Active", "21 days"). Tone by text/bg utilities.
    ".badge": {
      display: "inline-flex",
      alignItems: "center",
      gap: sp["1"],
      ...type(c.pill.type),
      fontWeight: w(c.pill.weight),
      borderRadius: r[c.pill.radius],
      padding: `${sp[c.pill.padding_y]} ${sp[c.pill.padding_x]}`,
      whiteSpace: "nowrap",
    },

    // ── Selection ───────────────────────────────────────────────────────────
    // BarPill.kt (Components.kt): the small selector beside a page title — the
    // Dashboard period, the Health range, the activity sort. 9×4 dp padding,
    // labelMedium, radius lg; selected is the accent container.
    ".bar-pill": {
      display: "inline-flex",
      alignItems: "center",
      padding: "0.25rem 0.5625rem",
      borderRadius: r.lg,
      ...type("xs"),
      fontWeight: w("medium"),
      whiteSpace: "nowrap",
      backgroundColor: s(500, 0.1),
      color: s(500),
      transitionProperty: "background-color, color",
      transitionDuration: "150ms",
      "&:hover": { backgroundColor: s(500, 0.18), color: s(700) },
    },
    ".dark .bar-pill": { backgroundColor: "rgb(255 255 255 / 0.06)", color: s(400) },
    ".dark .bar-pill:hover": { backgroundColor: "rgb(255 255 255 / 0.12)", color: s(200) },
    [on(".bar-pill")]: { backgroundColor: accent(100), color: accent(700) },
    [on(".dark .bar-pill")]: { backgroundColor: accent(100), color: accent(700) },

    // A segmented control: one choice among a few, laid out as a track of
    // options (ui/Tabs) — in-page views, Units, Colour scheme. Selected is the
    // accent container, as the phone's SegmentedButton (Theme.kt maps its
    // secondaryContainer to the accent's) and as a selected bar pill.
    ".segmented": {
      display: "flex",
      gap: sp["1"],
      padding: sp["1"],
      borderRadius: r.xl,
      backgroundColor: s(100),
    },
    ".dark .segmented": { backgroundColor: s(800) },
    ".segmented > button": {
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      gap: sp["1.5"],
      padding: `${sp["1.5"]} ${sp["2.5"]}`,
      borderRadius: r.lg,
      ...type("sm"),
      fontWeight: w("medium"),
      color: s(500),
      transitionProperty: "background-color, color",
      transitionDuration: "150ms",
      "&:hover": { color: s(700) },
    },
    ".dark .segmented > button": { color: s(400) },
    ".dark .segmented > button:hover": { color: s(200) },
    ".segmented-sm > button": { ...type("xs") },
    [on(".segmented > button")]: { backgroundColor: accent(100), color: accent(700) },
    [on(".dark .segmented > button")]: { backgroundColor: accent(100), color: accent(700) },

    // Material's FilterChip as the phone draws it: an outlined chip that fills
    // with the accent container when picked. Sports, presets, filters — any
    // pick-one or pick-several among peers inside a form.
    ".chip": {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      gap: sp["1.5"],
      height: sp["8"],
      padding: `0 ${sp["3"]}`,
      borderRadius: r.lg,
      border: `1px solid ${s(300)}`,
      ...type("sm"),
      fontWeight: w("medium"),
      color: s(600),
      whiteSpace: "nowrap",
      transitionProperty: "background-color, border-color, color",
      transitionDuration: "150ms",
      "&:hover": { borderColor: accent(400) },
      "&:disabled": { opacity: "0.5", cursor: "not-allowed" },
    },
    ".dark .chip": { borderColor: s(700), color: s(300) },
    // Tags in a dense row — muscles on an exercise, days of the week.
    ".chip-sm": { height: sp["6"], padding: `0 ${sp["2"]}`, gap: sp["1"], ...type("xs") },
    [on(".chip")]: { backgroundColor: accent(100), borderColor: "transparent", color: accent(700) },
    [on(".dark .chip")]: { backgroundColor: accent(100), borderColor: "transparent", color: accent(700) },

    // ChoiceCard (ProfileForms.kt): an option with a title and a line under
    // it. Outlined like a card; picked, the border turns accent and doubles.
    // The second pixel is an inset shadow so picking does not shift layout.
    ".choice": {
      display: "block",
      width: "100%",
      textAlign: "left",
      borderRadius: r[c.card.radius],
      border: `1px solid ${s(200)}`,
      padding: `${sp["2.5"]} ${sp["3"]}`,
      transitionProperty: "border-color, box-shadow",
      transitionDuration: "150ms",
      "&:hover": { borderColor: s(300) },
    },
    ".dark .choice": { borderColor: s(700) },
    ".dark .choice:hover": { borderColor: s(600) },
    [on(".choice")]: { borderColor: accent(600), boxShadow: `inset 0 0 0 1px ${accent(600)}` },
    [on(".dark .choice")]: { borderColor: accent(600), boxShadow: `inset 0 0 0 1px ${accent(600)}` },

    // TracksSwitch.kt: Material's switch, with an "off" that can be seen —
    // an outlined track and small grey thumb, growing to a filled accent
    // track and a large thumb when on. Markup: <button class="switch"
    // role="switch" aria-checked> with an empty <span> thumb (ui/Switch.jsx).
    ".switch": {
      position: "relative",
      display: "inline-flex",
      alignItems: "center",
      flexShrink: "0",
      width: "2.75rem",
      height: "1.5rem",
      borderRadius: r.full,
      border: `2px solid ${s(400)}`,
      backgroundColor: s(900, 0.04),
      transitionProperty: "background-color, border-color",
      transitionDuration: "150ms",
      "&:disabled": { opacity: "0.5", cursor: "not-allowed" },
      "& > span": {
        position: "absolute",
        left: "0.25rem",
        width: "0.75rem",
        height: "0.75rem",
        borderRadius: r.full,
        backgroundColor: s(500),
        transitionProperty: "transform, width, height, left, background-color",
        transitionDuration: "150ms",
      },
    },
    ".dark .switch": { borderColor: s(500), backgroundColor: "rgb(255 255 255 / 0.06)" },
    ".dark .switch > span": { backgroundColor: s(400) },
    '.switch[aria-checked="true"]': { backgroundColor: accent(600), borderColor: accent(600) },
    '.switch[aria-checked="true"] > span': {
      left: "calc(100% - 1.125rem - 0.125rem)",
      width: "1.125rem",
      height: "1.125rem",
      backgroundColor: "#fff",
    },
    // The dark accent is light, so the thumb goes dark — Material's onPrimary.
    '.dark .switch[aria-checked="true"]': { backgroundColor: accent(600), borderColor: accent(600) },
    '.dark .switch[aria-checked="true"] > span': { backgroundColor: s(950) },

    // The map panels' size: same switch, for a line of 11px text.
    ".switch-sm": { width: "2.25rem", height: "1.25rem" },
    ".switch-sm > span": { left: "0.1875rem", width: "0.625rem", height: "0.625rem" },
    '.switch-sm[aria-checked="true"] > span': { left: "calc(100% - 0.875rem - 0.125rem)", width: "0.875rem", height: "0.875rem" },

    // ── Forms ───────────────────────────────────────────────────────────────
    // One text input, select and textarea. Four copies of this string lived
    // in four primitives files, at two different border shades and paddings.
    ".field": {
      display: "block",
      width: "100%",
      borderRadius: r.lg,
      border: `1px solid ${s(300)}`,
      backgroundColor: "#fff",
      color: s(900),
      padding: `${sp["1.5"]} ${sp["2.5"]}`,
      ...type("sm"),
      transitionProperty: "border-color, box-shadow",
      transitionDuration: "150ms",
      "&::placeholder": { color: s(400) },
      "&:focus": { outline: "none", borderColor: accent(500), boxShadow: `0 0 0 1px ${accent(500)}` },
      "&:disabled": { opacity: "0.5", cursor: "not-allowed" },
    },
    ".dark .field": { borderColor: s(700), backgroundColor: s(800), color: "#fff" },
    ".dark .field:focus": { borderColor: accent(500) },
    // Native select arrows differ per browser and ignore dark mode; one chevron.
    "select.field": {
      appearance: "none",
      cursor: "pointer",
      paddingRight: sp["8"],
      backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 20' fill='%2394a3b8'%3E%3Cpath d='M5.3 7.3a1 1 0 0 1 1.4 0L10 10.6l3.3-3.3a1 1 0 1 1 1.4 1.4l-4 4a1 1 0 0 1-1.4 0l-4-4a1 1 0 0 1 0-1.4z'/%3E%3C/svg%3E")`,
      backgroundRepeat: "no-repeat",
      backgroundPosition: `right ${sp["2"]} center`,
      backgroundSize: "1rem",
    },
    // The compact field, for places that are dense by necessity: map side
    // panels, a set table's reps and weights. Same look, smaller.
    ".field-sm": {
      padding: `${sp["1"]} ${sp["1.5"]}`,
      borderRadius: r.md,
      ...type("xs"),
    },
    "select.field-sm": { paddingRight: sp["6"], backgroundPosition: `right ${sp["1"]} center`, backgroundSize: "0.875rem" },
    // The label over a field: 12px medium, the size of the phone's floated
    // OutlinedTextField label and of most labels the web already had. Sentence
    // case — uppercase is for section titles and stat labels, and a form full
    // of shouting labels reads as a table.
    ".field-label": {
      display: "block",
      ...type("xs"),
      fontWeight: w("medium"),
      color: s(600),
      marginBottom: sp["1"],
    },
    ".dark .field-label": { color: s(400) },
    // The line under a field that says what it is for, or what is wrong.
    ".field-hint": { ...type("xs"), color: s(500), marginTop: sp["1"] },
    ".dark .field-hint": { color: s(400) },

    // ── Status ──────────────────────────────────────────────────────────────
    // The loading ring. Size with w-/h- utilities; it defaults to 20px.
    ".spinner": {
      display: "inline-block",
      flexShrink: "0",
      width: sp["5"],
      height: sp["5"],
      borderRadius: r.full,
      border: `2px solid ${accent(500)}`,
      borderTopColor: "transparent",
      animation: "spin 1s linear infinite",
    },
    // Declared here too: Tailwind only emits `spin` while some file uses
    // animate-spin, and the spinner should not depend on that.
    "@keyframes spin": { to: { transform: "rotate(360deg)" } },
    // Something failed and the person should know: a line on a red tint.
    ".alert-error": {
      ...type("sm"),
      color: "rgb(220 38 38)",
      backgroundColor: "rgb(254 242 242)",
      borderRadius: r.lg,
      padding: `${sp["2"]} ${sp["3"]}`,
    },
    ".dark .alert-error": { color: "rgb(248 113 113)", backgroundColor: "rgb(127 29 29 / 0.2)" },
    // Worth knowing before acting, not a failure: the same line on amber.
    ".alert-warn": {
      ...type("sm"),
      color: "rgb(146 64 14)",
      backgroundColor: "rgb(255 251 235)",
      borderRadius: r.lg,
      padding: `${sp["2"]} ${sp["3"]}`,
    },
    ".dark .alert-warn": { color: "rgb(252 211 77)", backgroundColor: "rgb(120 53 15 / 0.2)" },

    // ── Overlays ────────────────────────────────────────────────────────────
    ".modal-backdrop": {
      position: "fixed",
      inset: "0",
      zIndex: "50",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      padding: sp["3.5"],
      backgroundColor: "rgb(0 0 0 / 0.6)",
      backdropFilter: "blur(4px)",
      // A backdrop drawn in place inside a `space-y-*` stack picks up its top
      // margin and stops 2rem short of the top. ui/Modal portals to <body>;
      // this covers the dialogs that still render in place.
      margin: "0 !important",
    },
    ".modal": {
      width: "100%",
      maxHeight: "90vh",
      overflowY: "auto",
      backgroundColor: "#fff",
      border: `1px solid ${s(200)}`,
      borderRadius: r["2xl"],
      boxShadow: "0 25px 50px -12px rgb(0 0 0 / 0.25)",
    },
    ".dark .modal": { backgroundColor: s(900), borderColor: s(700) },
    ".modal-title": { ...type("base"), fontWeight: w("bold"), color: s(900) },
    ".dark .modal-title": { color: "#fff" },

    // A bare glyph button — close ×, chevrons, row menus. Round, quiet until
    // hovered; anything with a word on it is a .btn instead.
    ".icon-btn": {
      display: "inline-grid",
      placeItems: "center",
      flexShrink: "0",
      width: sp["8"],
      height: sp["8"],
      borderRadius: r.full,
      color: s(400),
      transitionProperty: "background-color, color",
      transitionDuration: "150ms",
      "&:hover:not(:disabled)": { backgroundColor: s(100), color: s(700) },
      // Most of these are a "×" character; sized so it reads as an icon.
      fontSize: "1.125rem",
      lineHeight: "1",
      "&:disabled": { opacity: "0.4", cursor: "not-allowed" },
      "& svg": { width: "1.125rem", height: "1.125rem" },
    },
    ".dark .icon-btn:hover:not(:disabled)": { backgroundColor: s(800), color: s(200) },
    // In a list row: smaller, so it does not set the row's height.
    ".icon-btn-sm": { width: sp["6"], height: sp["6"], fontSize: "1rem", "& svg": { width: "0.875rem", height: "0.875rem" } },
    // Removes something: red under the pointer, quiet otherwise, so a column
    // of them does not read as a column of warnings.
    ".icon-btn-danger:hover:not(:disabled)": { backgroundColor: "rgb(254 242 242)", color: "rgb(239 68 68)" },
    ".dark .icon-btn-danger:hover:not(:disabled)": { backgroundColor: "rgb(239 68 68 / 0.1)", color: "rgb(248 113 113)" },

    // InfoTip.kt: the "?" beside a heading that opens the explanation.
    ".info-dot": {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      flexShrink: "0",
      width: sp["4"],
      height: sp["4"],
      borderRadius: r.full,
      fontSize: "10px",
      lineHeight: "1",
      fontWeight: w("bold"),
      backgroundColor: s(200),
      color: s(500),
      transitionProperty: "background-color, color",
      transitionDuration: "150ms",
      "&:hover": { backgroundColor: s(300) },
    },
    ".dark .info-dot": { backgroundColor: s(700), color: s(400) },
    ".dark .info-dot:hover": { backgroundColor: s(600) },
    [on(".info-dot")]: { backgroundColor: s(700), color: "#fff" },
    [on(".dark .info-dot")]: { backgroundColor: s(200), color: s(900) },
  });
}
