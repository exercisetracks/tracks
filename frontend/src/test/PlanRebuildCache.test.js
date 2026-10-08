// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Changing workout days per week (or anything else the plan reads) rebuilds the
// plan on the server, but the plan the page then re-read came out of the
// client's one-minute cache — the plan from before the change — so on the
// desktop the edit looked as if it had done nothing.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { api } from "../api/client";

let plan;
beforeEach(() => {
  api.clearCache();
  plan = { weeks: [{ days: 4 }] };
  globalThis.fetch = vi.fn(async (url, init) => ({
    ok: true, status: 200,
    json: async () => (init?.method === "PATCH" ? {} : plan),
  }));
});

describe("an edit the plan reads", () => {
  it("shows the rebuilt plan after a goal edit", async () => {
    expect(await api.getPlan(1)).toEqual({ weeks: [{ days: 4 }] });
    await api.updateGoal(1, { days_per_week: 6 });
    plan = { weeks: [{ days: 6 }] };            // the server rebuilt it
    expect(await api.getPlan(1)).toEqual({ weeks: [{ days: 6 }] });
    expect(await api.getUpcomingWorkouts(14)).toEqual(plan);
  });

  it("shows the rebuilt plan after a settings edit", async () => {
    await api.getPlan(1);
    await api.updateSettings({ ftp_manual: 260 });
    plan = { weeks: [{ days: 5 }] };
    expect(await api.getPlan(1)).toEqual({ weeks: [{ days: 5 }] });
  });
});
