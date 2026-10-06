// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The arithmetic behind the sleep chart: nights reduced to what it draws, each
// night placed on a clock, and an axis whose ticks fall on hours people count
// in. A port of the clock half of the phone's HealthSleep.kt, kept
// framework-free so the geometry can be tested without a DOM.

/**
 * Every night in the window that has a sleep total, oldest first.
 *
 * Nights rather than days: a day with no sleep recorded is not a night with no
 * sleep, and drawing it as a zero-height bar would claim otherwise.
 */
export function sleepNights(days) {
  return days
    .filter(d => d.sleep_hours != null && d.sleep_hours > 0)
    .map(d => {
      const night = {
        date:    d.date,
        total:   d.sleep_hours,
        deep:    d.sleep_deep_hours ?? 0,
        rem:     d.sleep_rem_hours ?? 0,
        light:   d.sleep_light_hours ?? 0,
        awake:   d.sleep_awake_hours ?? null,
        score:   d.sleep_score ?? null,
        startAt: d.sleep_start ?? null,
        endAt:   d.sleep_end ?? null,
      };
      // What the watch measured as waking, or — for every night recorded
      // before the parser kept awake time — what is left over between the
      // total and its stages. Once measured, the leftover is ignored.
      night.restless = night.awake ?? Math.max(0, night.total - night.deep - night.rem - night.light);
      return night;
    });
}

/** Local midnight of a YYYY-MM-DD, in milliseconds. */
function midnightOf(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).getTime();
}

/**
 * When a night ran, as hours from local midnight of the day it is filed under.
 *
 * Garmin names a night by the morning it ended, so going to bed is a negative
 * number: -1.5 is half past ten the previous evening, 6.75 a quarter to seven.
 * Deliberately not a wrapped 0–24 clock — bedtimes of 23:00 and 01:00 are two
 * hours apart, and a wrapped axis would draw them at opposite ends of the chart,
 * which is the one shape this view exists to make legible. The same signing is
 * why the average bedtime can be a plain mean: 23:30 and 00:30 average to
 * midnight rather than to noon.
 *
 * Stamps without an offset are read in the browser's zone, which is where the
 * person was sleeping; the server writes whatever the FIT file carried.
 *
 * Null when the night has no timeline, its stamps do not parse, or they run
 * backwards — "cannot place this night" rather than "no night", and the chart
 * says so.
 */
export function nightClock(night) {
  if (!night.startAt || !night.endAt || !/^\d{4}-\d{2}-\d{2}$/.test(night.date ?? "")) return null;
  const from = new Date(night.startAt).getTime();
  const to = new Date(night.endAt).getTime();
  if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) return null;
  const midnight = midnightOf(night.date);
  return { from: (from - midnight) / 3_600_000, to: (to - midnight) / 3_600_000 };
}

/** The divisions of a day, in the order an axis should try them. */
const CLOCK_STEPS = [1, 2, 3, 4, 6, 12];
/** Above this the labels crowd; below it the axis is coarser than the data. */
const CLOCK_TICKS = 6;

/**
 * A vertical axis for a clock, earliest at the top.
 *
 * Not the 1/2/2.5/5 steps a quantity axis uses: a nine-hour span would get a
 * 2.5-hour step and every other label at half past, which reads as an axis
 * that has slipped. Clocks divide by 1, 2, 3, 4, 6 and 12, so those are the
 * steps, and the bounds round outwards to them — hugging the nights rather
 * than padding to the next power of ten. Always at least one step tall: a
 * window whose nights all began and ended on the same hour would otherwise be
 * an axis with no extent.
 */
export function clockScale(earliest, latest) {
  const span = Math.max(latest - earliest, 1);
  const step = CLOCK_STEPS.find(s => span / s <= CLOCK_TICKS) ?? CLOCK_STEPS[CLOCK_STEPS.length - 1];
  const min = Math.floor(earliest / step) * step;
  let max = Math.ceil(latest / step) * step;
  if (max <= min) max = min + step;
  const ticks = [];
  for (let t = min; t <= max + step * 0.001; t += step) ticks.push(t);
  return { min, max, ticks };
}

/**
 * Hours from midnight as a time of day: -1.5 is "10:30 PM", 7 is "7 AM".
 *
 * Twelve-hour, because that is what the wearer's watch face says and a sleep
 * chart is read against a memory of what time it felt like. Wrapped only here,
 * at the point of writing a label, never in the geometry.
 */
export function clockLabel(hours) {
  const minutes = ((Math.round(hours * 60) % 1440) + 1440) % 1440;
  const h24 = Math.floor(minutes / 60);
  const m = minutes % 60;
  const h12 = h24 % 12 === 0 ? 12 : h24 % 12;
  const ampm = h24 < 12 ? "AM" : "PM";
  return m === 0 ? `${h12} ${ampm}` : `${h12}:${String(m).padStart(2, "0")} ${ampm}`;
}

/** "7h 24m". Decimal hours are a unit nobody sleeps in. */
export function hoursMinutes(hours) {
  const total = Math.round(hours * 60);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h === 0 ? `${m}m` : `${h}h ${m}m`;
}

/**
 * The lane a hypnogram span belongs in, top to bottom: awake, REM, light, deep.
 *
 * Matched on substrings because the watch's vocabulary is not fixed — files
 * carry `light`, `sleep_light`, `unmeasurable` and others across firmware —
 * and an unrecognised level lands in the awake lane rather than being dropped,
 * so a gap in the middle of a night is never silently invented.
 */
export function laneOf(level) {
  const l = (level ?? "").toLowerCase();
  if (["awake", "unmeasur", "wake"].some(k => l.includes(k))) return 0;
  if (l.includes("rem")) return 1;
  if (l.includes("deep")) return 3;
  if (["light", "sleep"].some(k => l.includes(k))) return 2;
  return 0;
}
