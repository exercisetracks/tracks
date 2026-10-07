// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Which due doses the browser announces. The old engine fired only in a
 * two-minute window checked every 30 seconds by a timer on the Health page,
 * so a throttled background tab or a sleeping laptop missed the dose outright.
 */
import { describe, expect, it } from "vitest";
import { LATE_WINDOW_MIN, dosesToAnnounce, reminderKey } from "../components/medication/useNotifications";

const AT = "2026-09-30T15:00:00+00:00"; // 08:00 PDT
const dose = (over = {}) => ({
  schedule_id: 7, medication_name: "Levothyroxine", scheduled_for: AT,
  notify: true, status: null, ...over,
});
const minutesAfter = (m) => new Date(new Date(AT).getTime() + m * 60_000);

describe("dosesToAnnounce", () => {
  it("announces a dose once its time has come", () => {
    expect(dosesToAnnounce([dose()], minutesAfter(0), new Set())).toHaveLength(1);
    expect(dosesToAnnounce([dose()], minutesAfter(-1), new Set())).toHaveLength(0);
  });

  it("still announces it to a tab that wakes up late, within the hour", () => {
    expect(dosesToAnnounce([dose()], minutesAfter(25), new Set())).toHaveLength(1);
    expect(dosesToAnnounce([dose()], minutesAfter(LATE_WINDOW_MIN), new Set())).toHaveLength(0);
  });

  it("stays quiet for a dose already logged, or one that asked for no reminder", () => {
    expect(dosesToAnnounce([dose({ status: "taken" })], minutesAfter(1), new Set())).toHaveLength(0);
    expect(dosesToAnnounce([dose({ notify: false })], minutesAfter(1), new Set())).toHaveLength(0);
  });

  it("announces each dose only once", () => {
    const shown = new Set([reminderKey(dose())]);
    expect(dosesToAnnounce([dose()], minutesAfter(1), shown)).toHaveLength(0);
  });
});
