// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The API client shares one request between callers asking for the same GET
// at once, and never lets an invalidation be undone by a response that was
// already on the wire.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { api } from "../api/client";

function deferredFetch() {
  const calls = [];
  globalThis.fetch = vi.fn(() => new Promise((resolve) => {
    calls.push((body) => resolve({ ok: true, status: 200, json: async () => body }));
  }));
  return calls;
}

beforeEach(() => api.clearCache());

describe("concurrent GETs", () => {
  it("share one request", async () => {
    // A page mounting several widgets that all want the settings.
    const calls = deferredFetch();
    const a = api.getSettings();
    const b = api.getSettings();
    expect(fetch).toHaveBeenCalledTimes(1);
    calls[0]({ units: "metric" });
    expect(await a).toEqual({ units: "metric" });
    expect(await b).toEqual({ units: "metric" });
  });

  it("are not handed a response that an update made stale", async () => {
    const calls = deferredFetch();
    const before = api.getSettings();
    // An update lands while the first read is still in flight.
    const update = api.updateSettings({ units: "imperial" });
    const after = api.getSettings();
    expect(fetch).toHaveBeenCalledTimes(3); // the read, the patch, a fresh read
    calls[0]({ units: "metric" });
    calls[1]({});
    calls[2]({ units: "imperial" });
    await Promise.all([before, update]);
    expect(await after).toEqual({ units: "imperial" });
    // And the stale first response did not overwrite the cache.
    expect(await api.getSettings()).toEqual({ units: "imperial" });
    expect(fetch).toHaveBeenCalledTimes(3);
  });
});
