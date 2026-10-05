// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The music push: what lands on the watch, in what order, and what it refuses
// to start. Ordering is load-bearing — an .m3u written before the tracks it
// names is a broken playlist on some firmware.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { pushMusic } from "../lib/deviceSync";

function makeTarget() {
  const events = [];
  return {
    events,
    async write(folder, filename, bytes, onProgress = null) {
      events.push({ op: "write", folder, filename, size: bytes.byteLength });
      if (onProgress) onProgress(bytes.byteLength, bytes.byteLength);
    },
    async remove(folder, filename) {
      events.push({ op: "remove", folder, filename });
    },
  };
}

/** Route the mocked fetch by path suffix; collect POST bodies for assertions. */
function mockApi(plan, { audioSize = 2048 } = {}) {
  const posts = [];
  vi.stubGlobal("fetch", vi.fn(async (url, opts = {}) => {
    const path = String(url).replace(/^https?:\/\/[^/]+/, "");
    if (opts.body) posts.push({ path, body: JSON.parse(opts.body) });

    if (path === "/music/device-plan") {
      return { ok: true, json: async () => plan };
    }
    if (path.endsWith("/audio")) {
      return { ok: true, arrayBuffer: async () => new ArrayBuffer(audioSize) };
    }
    if (path.startsWith("/music/mark-")) {
      return { ok: true, json: async () => ({ marked: 1 }) };
    }
    return { ok: false, status: 404, json: async () => ({}) };
  }));
  return posts;
}

const track = (id, extra = {}) => ({
  id, type: "music", filename: `${String(id).padStart(5, "0")} A - T${id}.mp3`,
  folder: "Music", url: `/music/tracks/${id}/audio`, size: 2048,
  title: `T${id}`, artist: "A", album: null, duration_s: 10, ...extra,
});

const basePlan = (over = {}) => ({
  add: [], remove: [], playlists: [], folder: "Music",
  bytes_to_add: 0, on_device_after: 0, device_file_limit: 500, ...over,
});

beforeEach(() => {
  localStorage.setItem("tracks_token", "test-token");
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("pushMusic", () => {
  it("writes added tracks into the root Music folder", async () => {
    mockApi(basePlan({ add: [track(1)], bytes_to_add: 2048, on_device_after: 1 }));
    const target = makeTarget();

    const result = await pushMusic(target);

    expect(result.added).toBe(1);
    expect(result.bytes).toBe(2048);
    // Music lives at the storage root, a sibling of GARMIN/ — writing it
    // inside GARMIN produces files the watch's scanner never sees.
    expect(target.events).toEqual([
      { op: "write", folder: "Music", filename: "00001 A - T1.mp3", size: 2048 },
    ]);
  });

  it("removes before it adds, and writes playlists last", async () => {
    mockApi(basePlan({
      add: [track(2)],
      remove: [{ id: 9, type: "music", filename: "old.mp3", folder: "Music" }],
      playlists: [{ id: 1, type: "playlist", filename: "Run.m3u", folder: "Music",
                    content: "00002 A - T2.mp3\n", track_count: 1 }],
      bytes_to_add: 2048, on_device_after: 2,
    }));
    const target = makeTarget();

    await pushMusic(target);

    expect(target.events.map((e) => `${e.op}:${e.filename}`)).toEqual([
      "remove:old.mp3",
      "write:00002 A - T2.mp3",
      "write:Run.m3u",
    ]);
  });

  it("records what it pushed so the next plan does not repeat it", async () => {
    const posts = mockApi(basePlan({
      add: [track(3)],
      remove: [{ id: 9, type: "music", filename: "old.mp3", folder: "Music" }],
      bytes_to_add: 2048, on_device_after: 1,
    }));

    await pushMusic(makeTarget());

    expect(posts).toEqual(expect.arrayContaining([
      { path: "/music/mark-deleted", body: { ids: [9] } },
      { path: "/music/mark-uploaded", body: { items: [{ id: 3, filename: "00003 A - T3.mp3" }] } },
    ]));
  });

  it("refuses to start a push that would overrun the device file limit", async () => {
    mockApi(basePlan({ add: [track(1)], on_device_after: 501, bytes_to_add: 2048 }));
    const target = makeTarget();

    // Overrunning does not error on the watch — the extra files are simply
    // never indexed — so the check has to happen before the transfer, not after.
    await expect(pushMusic(target)).rejects.toThrow(/500-file limit/);
    expect(target.events).toEqual([]);
  });

  it("reports progress across the whole queue, not per file", async () => {
    mockApi(basePlan({
      add: [track(1), track(2)], bytes_to_add: 4096, on_device_after: 2,
    }));
    const seen = [];

    await pushMusic(makeTarget(), { onProgress: (sent, total) => seen.push([sent, total]) });

    expect(seen.every(([, total]) => total === 4096)).toBe(true);
    expect(seen[seen.length - 1][0]).toBe(4096);
    const sent = seen.map(([s]) => s);
    expect([...sent].sort((a, b) => a - b)).toEqual(sent);
  });

  it("keeps going when one track fails and does not mark it uploaded", async () => {
    const posts = mockApi(basePlan({
      add: [track(1), track(2)], bytes_to_add: 4096, on_device_after: 2,
    }));
    const target = makeTarget();
    const realWrite = target.write;
    target.write = async (folder, filename, bytes, onProgress) => {
      if (filename.startsWith("00001")) throw new Error("device full");
      return realWrite.call(target, folder, filename, bytes, onProgress);
    };

    const result = await pushMusic(target);

    expect(result.added).toBe(1);
    expect(result.failures).toEqual([
      { filename: "00001 A - T1.mp3", error: "device full" },
    ]);
    const marked = posts.find((p) => p.path === "/music/mark-uploaded");
    expect(marked.body.items.map((i) => i.id)).toEqual([2]);
  });

  it("does nothing when there is nothing to carry", async () => {
    mockApi(basePlan());
    const target = makeTarget();

    const result = await pushMusic(target);

    expect(target.events).toEqual([]);
    expect(result).toEqual({ added: 0, removed: 0, playlists: 0, bytes: 0, failures: [] });
  });

  it("stops when cancelled mid-queue", async () => {
    mockApi(basePlan({ add: [track(1), track(2)], bytes_to_add: 4096, on_device_after: 2 }));
    const target = makeTarget();
    const cancelToken = {
      cancelled: false,
      throwIfCancelled() { if (this.cancelled) throw new Error("Sync cancelled"); },
    };
    const realWrite = target.write;
    target.write = async (...args) => {
      cancelToken.cancelled = true;
      return realWrite.apply(target, args);
    };

    await expect(pushMusic(target, { cancelToken })).rejects.toThrow(/cancelled/i);
    expect(target.events).toHaveLength(1);
  });
});
