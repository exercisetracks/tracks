// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Sport-type classification for the web app.
 *
 * The rules themselves are no longer here. They live in spec/sport_taxonomy.yaml,
 * are generated into src/spec/sportTaxonomy.js, and are evaluated by
 * src/spec/taxonomy.js — the same table and the same semantics the Python
 * backend and the Kotlin mobile core use. This module is now just the
 * app-facing shape of that: the SPORT_TYPES map components import, and the
 * `is*(activity)` predicates they call.
 *
 * The classification decides which of 19 activity layouts renders, so a
 * disagreement between clients means the phone and the browser show a
 * different page for the same ride. Sharing one table is what prevents that.
 *
 * To change how a sport is classified, edit spec/sport_taxonomy.yaml and run
 * `python3 spec/codegen.py`. Editing the generated file does nothing.
 */
import { SPORT_TYPE_LIST, sportType } from "../spec/taxonomy";

// Screaming-snake keys over the generated list, preserving the constant names
// the rest of the app already imports (SPORT_TYPES.INDOOR_CYCLING, …). Derived
// rather than hand-written so a type added to the spec appears here for free.
export const SPORT_TYPES = Object.fromEntries(
  SPORT_TYPE_LIST.map((t) => [t.toUpperCase(), t]),
);

export function getSportType(activity) {
  return sportType(activity?.sport, activity?.sub_sport);
}

export function isSportType(activity, wanted) {
  return getSportType(activity) === wanted;
}

export function isBouldering(activity)      { return isSportType(activity, SPORT_TYPES.BOULDERING); }
export function isClimbing(activity)        { return isSportType(activity, SPORT_TYPES.CLIMBING); }
export function isStrength(activity)        { return isSportType(activity, SPORT_TYPES.STRENGTH); }
export function isRunning(activity)         { return isSportType(activity, SPORT_TYPES.RUNNING); }
export function isHiking(activity)          { return isSportType(activity, SPORT_TYPES.HIKING); }
// Deliberately broader than isSportType: MTB rides are still rides, and every
// caller asking "is this cycling?" means the family, not the road sub-type.
export function isCycling(activity) {
  const t = getSportType(activity);
  return t === SPORT_TYPES.CYCLING || t === SPORT_TYPES.MTB;
}
export function isMTB(activity)             { return isSportType(activity, SPORT_TYPES.MTB); }
export function isIndoorCycling(activity)   { return isSportType(activity, SPORT_TYPES.INDOOR_CYCLING); }
export function isSwimming(activity)        { return isSportType(activity, SPORT_TYPES.SWIMMING); }
export function isRowing(activity)          { return isSportType(activity, SPORT_TYPES.ROWING); }
export function isTriathlon(activity)       { return isSportType(activity, SPORT_TYPES.TRIATHLON); }
export function isSkiing(activity)          { return isSportType(activity, SPORT_TYPES.SKIING); }
export function isNordicSkiing(activity)    { return isSportType(activity, SPORT_TYPES.NORDIC_SKIING); }
export function isPaddling(activity)        { return isSportType(activity, SPORT_TYPES.PADDLING); }
export function isGolf(activity)            { return isSportType(activity, SPORT_TYPES.GOLF); }
export function isTeamSports(activity)      { return isSportType(activity, SPORT_TYPES.TEAM_SPORTS); }
export function isFitnessEquipment(activity){ return isSportType(activity, SPORT_TYPES.FITNESS_EQUIPMENT); }
export function isMindBody(activity)        { return isSportType(activity, SPORT_TYPES.MIND_BODY); }

export default getSportType;
