// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Evaluate the generated sport-taxonomy rules.
 *
 * Hand-written, unlike the rule table it walks (spec/sport_taxonomy.yaml →
 * sportTaxonomy.js). Templating this loop into three languages would be worse
 * to maintain than the drift it prevents, and it is small enough to read in one
 * sitting. What keeps it honest is spec/fixtures/sport_taxonomy.json: the same
 * input/output corpus runs against the Python, JavaScript, and Kotlin
 * evaluators, so an implementation that diverges fails its own test suite.
 *
 * If you change the matching semantics here, change them in
 * backend/app/spec/taxonomy.py and the Kotlin equivalent in the same commit —
 * the fixtures will tell you if you forgot, but only after the fact.
 */
import { FALLBACK, RULES, SPORT_TYPE_LIST } from "./sportTaxonomy";

export { SPORT_TYPE_LIST };

const NON_ALNUM = /[^a-z0-9]/g;

// Rule patterns are a small fixed set reused across every activity in a list,
// so they're compiled once rather than per call.
const patternCache = new Map();
function compiled(pattern) {
  let re = patternCache.get(pattern);
  if (!re) {
    re = new RegExp(pattern);
    patternCache.set(pattern, re);
  }
  return re;
}

export function normalise(value) {
  return (value || "").toLowerCase().replace(NON_ALNUM, "_");
}

function conditionMatches(cond, fields) {
  const value = fields[cond.field];
  if (cond.equals !== undefined) return value === cond.equals;
  return compiled(cond.matches).test(value);
}

function entryMatches(entry, fields) {
  if (entry.all) return entry.all.every((c) => conditionMatches(c, fields));
  return conditionMatches(entry, fields);
}

/**
 * Classify a FIT sport/sub_sport pair into a Tracks sport type.
 *
 * Rules are evaluated in order and the first match wins — see the ordering
 * notes in spec/sport_taxonomy.yaml before assuming any rule is independent.
 */
export function sportType(sport, subSport) {
  const s = normalise(sport);
  const ss = normalise(subSport);
  const fields = { sport: s, sub_sport: ss, combined: `${s} ${ss}` };

  for (const rule of RULES) {
    if (!rule.any.some((e) => entryMatches(e, fields))) continue;
    if (rule.none && rule.none.some((e) => entryMatches(e, fields))) continue;
    return rule.type;
  }
  return FALLBACK;
}
