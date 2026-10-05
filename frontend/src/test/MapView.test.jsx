// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { buildStyle } from '../pages/maps/style';

const mockMapInstance = {
  on: vi.fn(function () { return this; }),
  remove: vi.fn(),
  addControl: vi.fn(),
};

const mockMaplibreGL = {
  Map: vi.fn(() => mockMapInstance),
  NavigationControl: vi.fn(),
  addProtocol: vi.fn(),
};

vi.mock('maplibre-gl', () => ({
  default: mockMaplibreGL,
  Map: mockMaplibreGL.Map,
  NavigationControl: mockMaplibreGL.NavigationControl,
  addProtocol: mockMaplibreGL.addProtocol,
}));

let _protocolFlag = false;

vi.mock('../pages/maps/hooks/useMapInit', async () => {
  const actual = await vi.importActual('../pages/maps/hooks/useMapInit');
  return actual;
});

describe('map initialization config', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('buildStyle produces .mvt extension vector tile URLs (not .pbf)', () => {
    const style = buildStyle();
    const { basemap } = style.sources;

    expect(basemap.tiles).toBeDefined();
    expect(basemap.url).toBeUndefined();
    expect(basemap.tiles[0]).toBe('/api/tiles/basemap/{z}/{x}/{y}.mvt');
  });

  it('buildStyle does not use pmtiles:// protocol anywhere', () => {
    const style = buildStyle();
    const styleStr = JSON.stringify(style);
    expect(styleStr).not.toContain('pmtiles://');
  });

  it('all tile URLs use /api/tiles/ path prefix for Caddy routing', () => {
    const style = buildStyle();
    expect(style.sources.basemap.tiles[0]).toMatch(/^\/api\/tiles\//);
  });

  it('every layer references a valid source defined in buildSources', () => {
    const style = buildStyle();
    const sourceNames = new Set(Object.keys(style.sources));

    style.layers.forEach((layer) => {
      if (layer.source) {
        expect(
          sourceNames.has(layer.source),
          `Layer "${layer.id}" references unknown source "${layer.source}"`
        ).toBe(true);
      }
    });
  });

  it('layers with source "basemap" have matching source-layer names', () => {
    const style = buildStyle();
    const basemapLayers = style.layers.filter((l) => l.source === 'basemap');
    expect(basemapLayers.length).toBeGreaterThan(10);
    basemapLayers.forEach((layer) => {
      expect(layer['source-layer']).toBeTruthy();
      expect(typeof layer['source-layer']).toBe('string');
    });
  });

  it('style version is 8 (MapLibre/Mapbox style spec)', () => {
    const style = buildStyle();
    expect(style.version).toBe(8);
  });

  it('background layer renders the USGS paper color #F8F6F0', () => {
    const style = buildStyle();
    const bg = style.layers.find((l) => l.id === 'background');
    expect(bg.paint['background-color']).toBe('#F8F6F0');
  });
});
