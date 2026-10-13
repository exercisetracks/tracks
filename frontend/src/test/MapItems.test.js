// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The rules the map page's library, editor and detail panels share
// (pages/maps/utils/mapItems.js) — ports of the phone's, so one item reads the
// same on both. Each case names the failure it guards against.
import { describe, expect, it } from "vitest";
import {
  handleIndices, handlesFromLine, MAX_HANDLES, naturalCompare, placeDeviceLabel, reduceLine,
  sortItems, stitchLine, trackDeviceLabel, trackOnWatch, gradeColor, pointAlong,
} from "../pages/maps/utils/mapItems";
import { buildStyle } from "../pages/maps/style";

describe("naturalCompare", () => {
  it("puts Trail 2 before Trail 10, as a person reads them", () => {
    const names = ["Trail 10", "trail 2", "Trail 19", "Trail 1"];
    expect([...names].sort(naturalCompare)).toEqual(["Trail 1", "trail 2", "Trail 10", "Trail 19"]);
  });

  it("reads leading zeros as the same number", () => {
    expect(naturalCompare("Loop 007", "Loop 7")).toBe(0);
  });

  it("sorts the library by name, or newest first by id", () => {
    const list = [{ id: 1, name: "b" }, { id: 3, name: "a" }, { id: 2, name: "c" }];
    expect(sortItems(list, "name").map((x) => x.id)).toEqual([3, 1, 2]);
    expect(sortItems(list, "newest").map((x) => x.id)).toEqual([3, 2, 1]);
  });
});

describe("watch labels", () => {
  it("says what the watch has confirmed, not what was asked for", () => {
    // Asked for but not yet confirmed is waiting, not on the watch: folding
    // the two together is what makes a sync feel like it lied.
    expect(trackDeviceLabel({ device_status: "pending_upload" })).toBe("waiting for the watch");
    expect(trackDeviceLabel({ device_status: "on_device" })).toBe("on the watch");
    expect(placeDeviceLabel({ load_to_device: true, on_watch: false })).toBe("waiting for the watch");
    expect(placeDeviceLabel({ load_to_device: false, on_watch: true })).toBe("coming off");
  });

  it("counts a track still coming off as on the watch, because it is", () => {
    expect(trackOnWatch({ device_status: "pending_remove" })).toBe(true);
    expect(trackOnWatch({ device_status: "pending_upload" })).toBe(false);
  });
});

describe("handles", () => {
  // A line north that swings ~500 m east and back several times, with a
  // vertex every ~11 m: a shape that needs a few handles to hold.
  const wiggle = Array.from({ length: 400 }, (_, i) => [7 + 0.006 * Math.sin(i / 20), 45 + i * 0.0001, 1000 + i]);

  it("stays editable on a long line", () => {
    const handles = handlesFromLine(wiggle);
    expect(handles.length).toBeLessThanOrEqual(MAX_HANDLES);
    expect(handles[0]).toEqual([wiggle[0][0], wiggle[0][1]]);
    expect(handles[handles.length - 1]).toEqual([wiggle[399][0], wiggle[399][1]]);
  });

  it("are vertices of the line, so each can be found in it", () => {
    const handles = handlesFromLine(wiggle);
    expect(handleIndices(handles, wiggle)).toHaveLength(handles.length);
  });

  it("keep the original line between handles that did not move", () => {
    // Moving one handle must not straighten the rest of a recorded track.
    const handles = handlesFromLine(wiggle);
    expect(handles.length).toBeGreaterThan(3);
    const base = handleIndices(handles, wiggle);
    const untouched = stitchLine(handles, wiggle, base);
    expect(untouched).toHaveLength(wiggle.length);
    expect(untouched[200][2]).toBe(wiggle[200][2]);

    const moved = handles.map((h, i) => (i === 1 ? [h[0] + 0.01, h[1]] : h));
    const restitched = stitchLine(moved, wiggle, base);
    // Only the two legs either side of the moved handle are straight now.
    expect(restitched.length).toBeLessThan(wiggle.length);
    expect(restitched[restitched.length - 1]).toEqual(wiggle[399]);
  });

  it("simplify keeps every surviving vertex exactly, elevation included", () => {
    const reduced = reduceLine(wiggle, 30);
    expect(reduced.length).toBeLessThan(wiggle.length);
    for (const p of reduced) expect(wiggle).toContainEqual(p);
  });
});

describe("profiles", () => {
  it("colours flat green and steep red, up or down", () => {
    expect(gradeColor(0)).toBe("rgb(22,163,74)");
    expect(gradeColor(0.2)).toBe("rgb(220,38,38)");
    expect(gradeColor(-0.2)).toBe("rgb(220,38,38)");
  });

  it("finds the spot on the map under the pointer on the profile", () => {
    const line = [[0, 0], [0, 1]];   // about 111 km north
    const mid = pointAlong(line, 55.6);
    expect(mid[0]).toBe(0);
    expect(mid[1]).toBeCloseTo(0.5, 2);
  });
});

describe("the map style", () => {
  it("draws saved places above the tracks, from their own source", () => {
    const style = buildStyle();
    const ids = style.layers.map((l) => l.id);
    expect(style.sources.saved_places).toBeDefined();
    expect(ids.indexOf("saved_places_icon")).toBeGreaterThan(ids.indexOf("custom_tracks_line"));
  });
});
