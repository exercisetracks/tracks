// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A signed-in browser whose data key has expired (every server restart drops
// it) was left with every encrypted page empty — "No GPS data", activity
// details that never loaded — because nothing acted on the server's
// `session_expired`. The client now raises one app-wide event for it.
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, VAULT_LOCKED_EVENT } from "../api/client";

afterEach(() => { vi.restoreAllMocks(); });

describe("an expired data key", () => {
  it("is announced once, app-wide, as well as failing the request", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ detail: "session_expired" }), { status: 401 }));
    const heard = vi.fn();
    window.addEventListener(VAULT_LOCKED_EVENT, heard);
    await expect(api.getTrack(1)).rejects.toMatchObject({ status: 401 });
    expect(heard).toHaveBeenCalledTimes(1);
    window.removeEventListener(VAULT_LOCKED_EVENT, heard);
  });

  it("is not confused with a login that has simply gone", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ detail: "Invalid or expired token" }), { status: 401 }));
    const heard = vi.fn();
    window.addEventListener(VAULT_LOCKED_EVENT, heard);
    await expect(api.getTrack(2)).rejects.toMatchObject({ status: 401 });
    expect(heard).not.toHaveBeenCalled();
    window.removeEventListener(VAULT_LOCKED_EVENT, heard);
  });
});
