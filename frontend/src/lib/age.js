// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Age as people answer it, stored as the birth year the server and phone
// already sync (`birth_year`). Storing the year rather than the age is what
// keeps it right next year without anyone editing it; asking for the age is
// what people can answer without arithmetic. The two are a year apart either
// side of a birthday, which the paces it feeds (running_fitness.py) cannot
// resolve anyway.
//
// The bounds match the phone's (ProfileForms.kt) so neither accepts an age
// the other would refuse to show.
export const MIN_AGE = 10;
export const MAX_AGE = 110;

export function ageFromBirthYear(year, now = new Date()) {
  return year == null ? null : now.getFullYear() - year;
}

// The birth year for a typed age, or null when it is not a plausible one.
export function birthYearFromAge(age, now = new Date()) {
  const n = typeof age === "number" ? age : Number(String(age).trim());
  if (!Number.isInteger(n) || n < MIN_AGE || n > MAX_AGE) return null;
  return now.getFullYear() - n;
}
