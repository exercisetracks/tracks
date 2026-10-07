// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The dashboard heatmap follows the page's filters — the period pills and the
// sport pie — instead of carrying its own. These pin that it asks for what the
// page chose, that it no longer offers controls that could disagree with it,
// and that the camera re-frames when the filter moves but not when the mode does.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";

const api = vi.hoisted(() => ({ getHeatmap: vi.fn(), getSports: vi.fn() }));
vi.mock("../api/client", () => ({ api }));
vi.mock("../context/ThemeContext", () => ({ useTheme: () => ({ colorScheme: "light" }) }));

// A stand-in map that records every fit, ready as soon as it mounts.
const fits = vi.hoisted(() => []);
vi.mock("../components/map/MapLibreMap", async () => {
  const { useEffect } = await import("react");
  return {
    default: function FakeMap({ onReady }) {
      useEffect(() => {
        onReady({
          getLayer: () => null,
          addLayer: () => {},
          fitBounds: (b) => fits.push(b),
        });
      }, []);
      return null;
    },
  };
});
vi.mock("../components/map/HeatmapGlowLayer", () => ({
  HeatmapGlowLayer: class { setStyle() {} setData() {} setSubtractive() {} },
  buildHeatmapVerts: () => ({ verts: null, count: 0 }),
  glowStyleForMode: () => ({}),
  legendStops: () => ["#000", "#fff"],
}));

import ActivityHeatmap from "../components/Charts/ActivityHeatmap";

const TRACK = [[45.0, 7.0], [45.1, 7.1]];

beforeEach(() => {
  fits.length = 0;
  api.getHeatmap.mockReset().mockResolvedValue([TRACK]);
  api.getSports.mockReset().mockResolvedValue(["running"]);
  window.matchMedia = () => ({ matches: false });
  globalThis.requestIdleCallback = () => 0; // no background prefetch noise
  globalThis.cancelIdleCallback = () => {};
});

describe("the dashboard heatmap", () => {
  it("asks for the period and sport the page chose", async () => {
    render(<ActivityHeatmap sport="cycling" after="2026-09-07" />);
    await waitFor(() => expect(api.getHeatmap).toHaveBeenCalled());
    expect(api.getHeatmap).toHaveBeenCalledWith({ mode: "frequency", sport: "cycling", after: "2026-09-07" });
  });

  it("sends no window for lifetime", async () => {
    render(<ActivityHeatmap sport="" after={null} />);
    await waitFor(() => expect(api.getHeatmap).toHaveBeenCalledWith({ mode: "frequency" }));
  });

  it("offers no sport, date or fit controls of its own", async () => {
    // Each was a second filter that could disagree with the page around it.
    const { container } = render(<ActivityHeatmap after="2026-09-07" />);
    await waitFor(() => expect(api.getHeatmap).toHaveBeenCalled());
    expect(container.querySelector("select")).toBeNull();
    expect(container.querySelector('input[type="date"]')).toBeNull();
    expect(screen.queryByText(/fit to data/i)).toBeNull();
    expect(api.getSports).not.toHaveBeenCalled();
  });

  it("frames the activities when the filter changes, not when the mode does", async () => {
    const { rerender } = render(<ActivityHeatmap after="2026-09-07" />);
    await waitFor(() => expect(fits).toHaveLength(1));

    // A different mode is the same place, so the user's pan is kept.
    fireEvent.click(screen.getByText("Pace"));
    await waitFor(() => expect(api.getHeatmap).toHaveBeenCalledWith(expect.objectContaining({ mode: "pace" })));
    expect(fits).toHaveLength(1);

    // A different window is a different set of activities: re-frame on them.
    api.getHeatmap.mockResolvedValue([[[10, 20], [10.5, 20.5]]]);
    rerender(<ActivityHeatmap after="2025-10-07" />);
    await waitFor(() => expect(fits).toHaveLength(2));
    expect(fits[1]).toEqual([[20, 10], [20.5, 10.5]]);
  });

  it("leaves the camera alone when the window has no GPS", async () => {
    api.getHeatmap.mockResolvedValue([]);
    render(<ActivityHeatmap after="2026-09-30" />);
    await screen.findByText(/no gps data/i);
    expect(fits).toHaveLength(0);
  });
});
