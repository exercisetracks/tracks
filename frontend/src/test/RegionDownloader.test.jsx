// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A map download that cannot happen has to say why. The estimate used to turn
// a source the server could not read into "Estimated size: <1 MB", and a failed
// area showed only its name in red — so a broken download read as a tiny one
// and then as nothing at all.
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("../api/client", () => ({
  api: { suggestRegionName: vi.fn(() => Promise.resolve({ name: "Somewhere" })) },
}));

import RegionDownloader from "../pages/maps/components/RegionDownloader";

const noop = () => {};
const props = {
  drawing: false, bbox: null, sizeEstimate: null, estimateError: null,
  regions: [], pending: false,
  onStart: noop, onCancel: noop, onDownload: noop, onDelete: noop,
  onHighlight: noop, onZoom: noop,
};

describe("RegionDownloader", () => {
  it("shows why an area could not be sized, instead of a size", () => {
    render(<RegionDownloader {...props} drawing bbox={[0, 0, 1, 1]}
      estimateError="Could not reach the map source — build.protomaps.com: 404 Not Found" />);
    expect(screen.getByText(/404 Not Found/)).toBeTruthy();
    expect(screen.queryByText(/Estimated size/)).toBeNull();
  });

  it("shows a failed area's reason beside its name", () => {
    render(<RegionDownloader {...props} regions={[
      { id: 3, name: "Idaho", status: "error", bbox: [0, 0, 1, 1],
        error: "basemap: pmtiles exited with 1" },
    ]} />);
    expect(screen.getByText("Idaho")).toBeTruthy();
    expect(screen.getByText("basemap: pmtiles exited with 1")).toBeTruthy();
  });
});
