// Emit react-muscle-highlighter's anatomy as a Kotlin table the mobile app can
// draw natively.
//
// The web app shows muscle activation on an anatomical body; the phone needs
// the same picture. The library that draws it is a React component, so it
// cannot run in Compose — but its artwork is not React at all. It is plain SVG
// path strings in dist/esm/assets/*.js, keyed by part slug, with separate
// left/right arrays. Compose parses SVG path data natively.
//
// So this takes the third option between "hand-draw a body" (a week of work to
// end up with worse anatomy that then disagrees with the web app) and "put
// React in a WebView" (drag a UI framework into the APK to render one
// diagram): read the artwork, write it out as Kotlin. Same paths, same slugs,
// same licence — one body, drawn by two renderers.
//
// The viewBox is not in the assets. It lives in the library's two SVG wrapper
// components, and it differs per body — the male back is drawn at x >= 724 and
// the female front starts at -50,-40. Miss that and each body still renders,
// just cropped or offset, which is the failure mode a generator must not
// produce quietly. So the four are transcribed here and checked against the
// wrappers below rather than assumed.
//
// Usage: node scripts/build-body-art.mjs [outfile]

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve as resolvePath } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_OUT = resolvePath(
  HERE,
  "../../mobile/core/src/commonMain/kotlin/com/tracks/core/spec/BodyArtData.kt",
);

// Reached by path rather than by package name. The library's `exports` map
// publishes only its entry point, so both the assets and its own package.json
// are unreachable through a bare specifier — and the assets are exactly what
// this script wants, precisely because they are not the React part.
const LIB = resolvePath(HERE, "../node_modules/react-muscle-highlighter");
const pkg = JSON.parse(readFileSync(resolvePath(LIB, "package.json"), "utf8"));

// The four bodies the library ships. Male is what the web app renders today;
// female comes along because it is the same loop and the same artwork licence,
// and a fitness app whose only body model is male is a poor default to bake in
// at generation time.
const BODIES = [
  { name: "MALE_FRONT", module: "bodyFront", export: "bodyFront",
    wrapper: "SvgMaleWrapper", side: "front", viewBox: [0, 0, 724, 1448] },
  { name: "MALE_BACK", module: "bodyBack", export: "bodyBack",
    wrapper: "SvgMaleWrapper", side: "back", viewBox: [724, 0, 724, 1448] },
  { name: "FEMALE_FRONT", module: "bodyFemaleFront", export: "bodyFemaleFront",
    wrapper: "SvgFemaleWrapper", side: "front", viewBox: [-50, -40, 734, 1538] },
  { name: "FEMALE_BACK", module: "bodyFemaleBack", export: "bodyFemaleBack",
    wrapper: "SvgFemaleWrapper", side: "back", viewBox: [756, 0, 774, 1448] },
];

/**
 * Read the viewBox the wrapper component actually uses and check it matches.
 *
 * The wrappers are compiled JS, so this is a regex over source rather than
 * anything principled — but the alternative is four magic numbers per body
 * with nothing tying them to the drawing they describe.
 */
function assertViewBox(body) {
  const src = readFileSync(
    resolvePath(LIB, `dist/esm/components/${body.wrapper}.js`), "utf8");
  const match = src.match(
    /viewBox = side === "front" \? "([^"]*)" : "([^"]*)"/);
  if (!match) {
    throw new Error(
      `${body.wrapper} no longer states its viewBox the way this script reads it`);
  }
  const actual = (body.side === "front" ? match[1] : match[2]).split(/\s+/).map(Number);
  const expected = body.viewBox;
  if (actual.some((n, i) => n !== expected[i])) {
    throw new Error(
      `${body.name} viewBox changed upstream: ${actual.join(" ")} ` +
      `(this script has ${expected.join(" ")}). Update BODIES.`);
  }
}

async function load(body) {
  const file = resolvePath(LIB, `dist/esm/assets/${body.module}.js`);
  const mod = await import(pathToFileURL(file).href);
  const parts = mod[body.export];
  if (!Array.isArray(parts) || parts.length === 0) {
    throw new Error(`${body.module}.js did not export a part array`);
  }
  return parts;
}

function kotlinString(value) {
  // Path data is digits, letters, spaces, commas, dots and minus signs. No
  // escaping has ever been needed — but a generator that assumes that silently
  // is a generator that emits broken Kotlin the day it stops being true.
  if (/["\\$\n\r]/.test(value)) {
    throw new Error(`path data needs escaping, which this generator does not do: ${value.slice(0, 60)}`);
  }
  return `"${value}"`;
}

/**
 * Separate the flags in every elliptical-arc command.
 *
 * The artwork has been through an SVG optimiser, which packs an arc's two
 * one-digit flags against each other and against whatever follows:
 *
 *     a.84.83 51.1 01-.9-.1        ← large-arc 0, sweep 1, then -.9,-.1
 *     a.44.44 0 01.59.43           ← large-arc 0, sweep 1, then .59,.43
 *
 * That is valid SVG — a flag is defined as a single character, so no separator
 * is needed — and browsers read it correctly. Android's `PathParser`, which is
 * what Compose uses, does not: it splits the parameter list into numbers
 * greedily, reads `01` as one value, and every remaining parameter of that arc
 * shifts by one. The arc lands somewhere else and, because the pen ends up in
 * the wrong place, so does everything after it in the same path.
 *
 * On screen that was a body drawn almost right — the limbs and torso are cubic
 * and quadratic curves, which parse fine — with a stray slab beside the skull
 * and a shoulder cap adrift, on whichever shapes happened to use arcs.
 *
 * So the flags are given their own tokens here. The output is the same picture;
 * it is merely spelled in the dialect the phone can read.
 */
function separateArcFlags(d) {
  // Walk the string rather than regex it: an arc's parameters repeat, so
  // "a1 1 0 0110 0 1 1 0 0120 0" is two arcs, and knowing which number is a
  // flag means counting from the command.
  let out = "";
  let i = 0;
  const isCommand = (c) => /[a-zA-Z]/.test(c);
  const isNumberStart = (c) => /[-+.\d]/.test(c);

  while (i < d.length) {
    const c = d[i];
    if (c !== "a" && c !== "A") {
      out += c;
      i += 1;
      continue;
    }
    out += c;
    i += 1;

    // Read parameter sets of seven until the next command letter.
    while (i < d.length) {
      while (i < d.length && /[\s,]/.test(d[i])) { out += d[i]; i += 1; }
      if (i >= d.length || isCommand(d[i])) break;

      const params = [];
      for (let n = 0; n < 7 && i < d.length; n += 1) {
        while (i < d.length && /[\s,]/.test(d[i])) i += 1;
        // Parameters 4 and 5 (0-indexed 3 and 4) are the flags: exactly one
        // character each, whatever is crowded up against them.
        if (n === 3 || n === 4) {
          params.push(d[i]);
          i += 1;
          continue;
        }
        let start = i;
        if (isNumberStart(d[i]) && (d[i] === "-" || d[i] === "+")) i += 1;
        let seenDot = false;
        while (i < d.length && /[\d.eE+-]/.test(d[i])) {
          if (d[i] === ".") {
            // A second dot starts the next number: "1.5.5" is 1.5 then .5.
            if (seenDot) break;
            seenDot = true;
          } else if ((d[i] === "-" || d[i] === "+") && i > start &&
                     !/[eE]/.test(d[i - 1])) {
            break;
          }
          i += 1;
        }
        params.push(d.slice(start, i));
      }
      if (params.length < 7) {
        throw new Error(`arc with ${params.length} parameters, not 7: ${d.slice(0, 80)}`);
      }
      out += params.join(" ");
      if (i < d.length && !isCommand(d[i])) out += " ";
    }
  }
  return out;
}

function emitBody(name, parts, viewBox) {
  const entries = parts.map((part) => {
    // Three buckets, not two. `common` is for the parts a body has one of —
    // the head, the hair, the front of the throat — and reading only left and
    // right drops them entirely, which is a body with no head rather than an
    // error anyone would notice in a diff.
    const paths = [
      ...(part.path?.common ?? []),
      ...(part.path?.left ?? []),
      ...(part.path?.right ?? []),
    ].map(separateArcFlags);
    if (paths.length === 0) {
      throw new Error(`part ${part.slug} in ${name} has no paths`);
    }
    const list = paths.map((p) => `\n        ${kotlinString(p)},`).join("");
    return `    "${part.slug}" to listOf(${list}\n    ),`;
  });

  // Slugs repeat across left/right in some assets; the map would silently keep
  // the last one and drop the other half of the body.
  const slugs = parts.map((p) => p.slug);
  const duplicates = slugs.filter((s, i) => slugs.indexOf(s) !== i);
  if (duplicates.length > 0) {
    throw new Error(`${name} repeats slugs: ${[...new Set(duplicates)].join(", ")}`);
  }

  const [minX, minY, width, height] = viewBox;
  return `public val ${name}: BodyArt = BodyArt(
    minX = ${minX}f,
    minY = ${minY}f,
    width = ${width}f,
    height = ${height}f,
    parts = mapOf(
${entries.map((e) => e.split("\n").map((l) => "    " + l).join("\n")).join("\n")}
    ),
)`;
}

async function main() {
  const out = resolvePath(process.cwd(), process.argv[2] ?? DEFAULT_OUT);

  const bodies = [];
  for (const body of BODIES) {
    assertViewBox(body);
    bodies.push(emitBody(body.name, await load(body), body.viewBox));
  }

  // Two copyrights and two licences, because the file genuinely contains
  // both: the path data is the library's artwork under its MIT licence, and
  // only the Kotlin around it is ours. Declaring it AGPL alone would claim
  // someone else's drawing.
  const source = `// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-FileCopyrightText: 2024 My Muscle Contributors
// SPDX-License-Identifier: AGPL-3.0-or-later AND MIT
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: react-muscle-highlighter ${pkg.version} (MIT), its
//         dist/esm/assets/body*.js
// Regenerate: cd frontend && node scripts/build-body-art.mjs
//
// The artwork is the library's, under its own MIT licence — see NOTICE and
// REUSE.toml. Only the transcription into Kotlin is ours. The web app renders
// the same paths through the React component, so the two diagrams are the same
// drawing rather than two drawings that agree for now.
//
// Each entry is one part slug and every path that draws it, left and right
// sides concatenated: which limb a path belongs to matters to the library's
// hover behaviour and not to us, since a muscle shades as a whole.

package com.tracks.core.spec

/**
 * One body: the space its paths are drawn in, and the parts that draw it.
 *
 * The viewport is per body and not a constant. Male front starts at the origin,
 * male *back* starts at x = 724, and the female bodies have their own offsets
 * again — the assets were cut from single wide artboards. Rendering any of them
 * without translating by [minX] and [minY] first draws a body that is entirely
 * off-screen, so this travels with the paths rather than beside them.
 */
public data class BodyArt(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    /** Part slug to every path that draws it, common + left + right. */
    val parts: Map<String, List<String>>,
)

${bodies.join("\n\n")}
`;

  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, source);
  const parts = source.match(/" to listOf\(/g)?.length ?? 0;
  console.log(`body art: wrote ${parts} parts across ${BODIES.length} bodies to ${out}`);
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
