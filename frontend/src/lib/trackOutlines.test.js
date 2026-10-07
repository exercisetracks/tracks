// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from "vitest";

const pending = [];
vi.mock("../api/client", () => ({
  api: {
    getActivityOutline: vi.fn(() => new Promise((resolve) => pending.push(resolve))),
  },
}));

import { api } from "../api/client";
import { _resetOutlines, cachedOutline, fetchOutline } from "./trackOutlines";

const SHAPE = { points: [[0, 0], [1, 1]], inset_x: 0, inset_y: 0 };

describe("route outlines for the activity list", () => {
  beforeEach(() => {
    _resetOutlines();
    pending.length = 0;
    api.getActivityOutline.mockClear();
    localStorage.clear();
  });

  it("fetches a couple at a time so the table is never queued behind them", async () => {
    for (let i = 1; i <= 5; i++) fetchOutline(i, `k${i}`);
    expect(api.getActivityOutline).toHaveBeenCalledTimes(2);
    pending.shift()(SHAPE);
    await new Promise((r) => setTimeout(r, 0));
    expect(api.getActivityOutline).toHaveBeenCalledTimes(3);
  });

  it("asks about an activity once, and remembers one with no track too", async () => {
    const a = fetchOutline(1, "k1");
    const b = fetchOutline(1, "k1");
    expect(api.getActivityOutline).toHaveBeenCalledTimes(1);
    pending.shift()(null);
    expect(await a).toBeNull();
    expect(await b).toBeNull();
    expect(cachedOutline("k1")).toBeNull();
    await fetchOutline(1, "k1");
    expect(api.getActivityOutline).toHaveBeenCalledTimes(1);
  });
});
