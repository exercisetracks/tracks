// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Long-trail emblem registry + per-trail colour/icon expressions.
//
// Every named long-distance route gets an emblem so a trail is always
// identifiable. Recognised famous trails (the registry below) use a curated
// colour + abbreviation, and a real logo PNG when the backend has sourced one
// (routes/trail_logos.py). Anything not in the registry falls back to a
// data-driven badge built from the per-route colour + abbreviation the tile
// builder bakes in (route_registry._route_color / _route_abbr). The registry also
// UNIFIES a trail's many OSM section relations into one colour/emblem.
//
// The canvas badge rendering + styleimagemissing handler live in trailBadge.js.
// Two icon-image id shapes flow through MapLibre's `styleimagemissing` event:
//   traillogo-<id>            recognised trail → real PNG, else curated badge
//   tlbadge|<#color>|<ABBR>   unrecognised trail → badge from baked route props

export const PREFIX = "traillogo-";
export const BADGE_PREFIX = "tlbadge|";

// id, abbr, color, match[] (case-insensitive substrings of the route name).
// `logo:true` means a real emblem PNG is sourced by the backend for this id.
export const TRAIL_LOGOS = [
  // ── North America ──
  { id: "cdt", abbr: "CDT", color: "#2563a8", logo: true, match: ["continental divide", "cdt"] },
  { id: "pct", abbr: "PCT", color: "#1f7a4d", logo: true, match: ["pacific crest", "pct"] },
  { id: "at",  abbr: "AT",  color: "#2f5d34", logo: true, match: ["appalachian"] },
  { id: "azt", abbr: "AZT", color: "#b05423", logo: true, match: ["arizona trail", "azt"] },
  { id: "nct", abbr: "NCT", color: "#3a5fa0", logo: true, match: ["north country", "nct"] },
  { id: "ft",  abbr: "FT",  color: "#1f8a5c", logo: true, match: ["florida trail", "florida national scenic"] },
  { id: "ct",  abbr: "CT",  color: "#0b7a33", logo: true, match: ["colorado trail"] },
  { id: "adt", abbr: "ADT", color: "#9c3f7a", match: ["american discovery", "adt -", "adt "] },
  { id: "pnt", abbr: "PNT", color: "#1f6f8a", match: ["pacific northwest", "pnt"] },
  { id: "iat", abbr: "IAT", color: "#7a4fa0", match: ["ice age"] },
  { id: "pht", abbr: "PHT", color: "#8a6d1f", match: ["potomac heritage"] },
  { id: "jmt", abbr: "JMT", color: "#2f6f8a", match: ["john muir"] },
  { id: "lt",  abbr: "LT",  color: "#2f7a3f", match: ["long trail"] },
  { id: "sht", abbr: "SHT", color: "#1f6f6a", match: ["superior hiking"] },
  { id: "net", abbr: "NET", color: "#5c7a2f", match: ["new england trail"] },
  { id: "gdt", abbr: "GDT", color: "#3a6ea5", match: ["great divide trail"] },
  // ── Europe ──
  { id: "tmb",   abbr: "TMB",  color: "#c0392b", match: ["tour du mont"] },
  { id: "gr20",  abbr: "GR20", color: "#6c3483", match: ["gr 20", "gr20"] },
  { id: "gr10",  abbr: "GR10", color: "#1f8a8a", match: ["gr 10", "gr10"] },
  { id: "gr11",  abbr: "GR11", color: "#b9770e", match: ["gr 11", "gr11", "senda pirenaica"] },
  { id: "gr5",   abbr: "GR5",  color: "#2e86c1", match: ["gr 5", "gr5", "grande travers"] },
  { id: "haute", abbr: "HR",   color: "#922b21", match: ["haute route"] },
  { id: "camino", abbr: "CAM", color: "#d4ac0d", match: ["camino", "jakobsweg", "saint james", "santiago"] },
  { id: "whw",   abbr: "WHW",  color: "#196f3d", match: ["west highland way"] },
  { id: "pw",    abbr: "PW",   color: "#7d6608", match: ["pennine way"] },
  { id: "swcp",  abbr: "SWCP", color: "#1a5276", match: ["south west coast path"] },
  { id: "kungs", abbr: "KL",   color: "#5499c7", match: ["kungsleden"] },
  { id: "laug",  abbr: "LV",   color: "#a93226", match: ["laugavegur"] },
  { id: "av1",   abbr: "AV1",  color: "#6e2c00", match: ["alta via 1"] },
  // ── Asia / Middle East ──
  { id: "int",    abbr: "INT", color: "#2471a3", match: ["israel national", "israel trail"] },
  { id: "lycian", abbr: "LYC", color: "#ca6f1e", match: ["lycian", "likya"] },
  { id: "jordan", abbr: "JT",  color: "#935116", match: ["jordan trail"] },
  { id: "annap",  abbr: "AC",  color: "#1f618d", match: ["annapurna"] },
  { id: "ebc",    abbr: "EBC", color: "#515a5a", match: ["everest", "khumbu"] },
  // ── Oceania ──
  { id: "te",        abbr: "TA",   color: "#117a65", match: ["te araroa"] },
  { id: "milford",   abbr: "MIL",  color: "#1abc9c", match: ["milford track"] },
  { id: "routeburn", abbr: "RBN",  color: "#16a085", match: ["routeburn"] },
  { id: "overland",  abbr: "OLT",  color: "#2980b9", match: ["overland track"] },
  { id: "larapinta", abbr: "LAR",  color: "#ca5010", match: ["larapinta"] },
  { id: "bibb",      abbr: "BIB",  color: "#196f3d", match: ["bibbulmun"] },
  { id: "aawt",      abbr: "AAWT", color: "#6c3483", match: ["australian alps walking"] },
  // ── South America ──
  { id: "inca", abbr: "INCA", color: "#b7950b", match: ["inca trail", "camino inca"] },
  { id: "tdp",  abbr: "TDP",  color: "#1f618d", match: ["torres del paine", "paine circuit"] },
  { id: "huay", abbr: "HUA",  color: "#117864", match: ["huayhuash"] },
  { id: "toubkal", abbr: "TBK", color: "#a04000", match: ["toubkal"] },
  { id: "simien",  abbr: "SIM", color: "#7d6608", match: ["simien", "semien"] },
  // ── Africa ──
  { id: "otter", abbr: "OT",  color: "#1e8449", match: ["otter trail"] },
  { id: "drak",  abbr: "DRK", color: "#7e5109", match: ["drakensberg"] },
  { id: "frc",   abbr: "FRC", color: "#a04000", match: ["fish river canyon"] },
  // ── Central America ──
  { id: "ccr",   abbr: "CCR", color: "#1f8a5c", match: ["camino de costa rica", "camino costa rica"] },
  // ── Canada ── (gdt already defined under North America)
  { id: "sct",   abbr: "SCT", color: "#5499c7", match: ["sunshine coast trail"] },
  { id: "wct",   abbr: "WCT", color: "#1a5276", match: ["west coast trail"] },
  // ── New Zealand Great Walks ──
  { id: "kepler",    abbr: "KEP", color: "#117a65", match: ["kepler track"] },
  { id: "abel",      abbr: "ATC", color: "#16a085", match: ["abel tasman"] },
  { id: "tongariro", abbr: "TNC", color: "#ca5010", match: ["tongariro"] },
  { id: "heaphy",    abbr: "HEA", color: "#1e8449", match: ["heaphy"] },
  { id: "rakiura",   abbr: "RAK", color: "#6c3483", match: ["rakiura"] },
  { id: "paparoa",   abbr: "PAP", color: "#b9770e", match: ["paparoa"] },
  { id: "waikare",   abbr: "WAI", color: "#1f618d", match: ["waikaremoana"] },
  // ── Australia (more) ──
  { id: "threecapes", abbr: "3CT", color: "#922b21", match: ["three capes"] },
  { id: "c2cau",      abbr: "C2C", color: "#2980b9", match: ["cape to cape"] },
  { id: "heysen",     abbr: "HEY", color: "#8a6d1f", match: ["heysen"] },
  { id: "gow",        abbr: "GOW", color: "#1abc9c", match: ["great ocean walk"] },
  // ── Europe (more) ──
  { id: "cwt",   abbr: "CWT", color: "#515a5a", match: ["cape wrath"] },
  { id: "c2cuk", abbr: "C2C", color: "#196f3d", match: ["coast to coast"] },
  { id: "norte", abbr: "NOR", color: "#d4ac0d", match: ["camino del norte"] },
  { id: "vicentina", abbr: "RV", color: "#ca6f1e", match: ["rota vicentina", "fishermen"] },
  // ── Asia (more) ──
  { id: "kumano",  abbr: "KK",  color: "#a93226", match: ["kumano kodo"] },
  { id: "manaslu", abbr: "MAN", color: "#6e2c00", match: ["manaslu"] },
  { id: "markha",  abbr: "MKV", color: "#935116", match: ["markha"] },
  { id: "perdida", abbr: "CP",  color: "#117864", match: ["ciudad perdida", "lost city", "teyuna"] },
  { id: "choque",  abbr: "CHQ", color: "#b7950b", match: ["choquequirao"] },
  // generic fallback (kept last)
  { id: "generic", abbr: "", color: "#3f7a3f", match: [] },
];

export const byId = Object.fromEntries(TRAIL_LOGOS.map((t) => [t.id, t]));

// Build a MapLibre `icon-image` expression: recognised trails → a stable
// `traillogo-<id>` (real PNG or curated badge); everything else → a data-driven
// `tlbadge|<color>|<abbr>` from the route's baked props.
export function routeIconExpression() {
  const name = ["downcase", ["coalesce", ["get", "name"], ""]];
  let expr = ["concat", BADGE_PREFIX,
    ["coalesce", ["get", "color"], "#3f7a3f"], "|",
    ["coalesce", ["get", "abbr"], ""]];
  for (let i = TRAIL_LOGOS.length - 1; i >= 0; i--) {
    const t = TRAIL_LOGOS[i];
    for (const sub of t.match) {
      expr = ["case", [">=", ["index-of", sub, name], 0], PREFIX + t.id, expr];
    }
  }
  return expr;
}

// Build a `line-color` expression: recognised trails → curated colour; everything
// else → the per-route colour baked into the tile. Gives each long trail its own
// colour (and keeps a trail's many sections one colour).
export function routeColorExpression() {
  const name = ["downcase", ["coalesce", ["get", "name"], ""]];
  let expr = ["coalesce", ["get", "color"], "#b5421e"];
  for (let i = TRAIL_LOGOS.length - 1; i >= 0; i--) {
    const t = TRAIL_LOGOS[i];
    if (t.id === "generic") continue;
    for (const sub of t.match) {
      expr = ["case", [">=", ["index-of", sub, name], 0], t.color, expr];
    }
  }
  return expr;
}
