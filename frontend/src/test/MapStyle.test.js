// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { buildSources } from '../pages/maps/style/sources';
import { buildStyle } from '../pages/maps/style';

describe('buildSources', () => {
  it('produces standard ZXY tile URLs, not pmtiles:// protocol', () => {
    const sources = buildSources();

    expect(sources.basemap.type).toBe('vector');
    expect(sources.basemap.tiles).toBeDefined();
    expect(sources.basemap.url).toBeUndefined();
    expect(sources.basemap.tiles[0]).toMatch(/^\/api\/tiles\/basemap\/\{z\}\/\{x\}\/\{y\}\.mvt$/);
    expect(sources.basemap.attribution).toContain('OpenStreetMap');
  });

  it('defines the basemap and the single OSM overlay vector sources', () => {
    const sources = buildSources();
    expect(sources.basemap).toBeDefined();
    expect(sources.contours).toBeDefined();
    // The five former per-theme overlays are now one multi-layer `overlay` source.
    expect(sources.overlay).toBeDefined();
    expect(sources.overlay.type).toBe('vector');
    expect(sources.overlay.tiles[0]).toMatch(/^\/api\/tiles\/master_overlay\/\{z\}\/\{x\}\/\{y\}\.mvt/);
    expect(sources.overlay.minzoom).toBe(6);
    expect(sources.overlay.maxzoom).toBe(15);
    // The per-theme sources are gone — their layers now read `overlay`.
    for (const k of ['trails_osm', 'water_osm', 'areas_osm', 'infra_osm', 'landuse_osm']) {
      expect(sources[k]).toBeUndefined();
    }
    // dem is added dynamically by useRegionDownload (not in buildSources).
    expect(sources.dem).toBeUndefined();
  });

  it('uses .mvt extension for vector sources (matching go-pmtiles MVT archive type)', () => {
    const sources = buildSources();
    expect(sources.basemap.tiles[0]).toContain('.mvt');
  });

  it('has correct zoom ranges for basemap source', () => {
    const sources = buildSources();
    expect(sources.basemap.minzoom).toBe(0);
    expect(sources.basemap.maxzoom).toBe(15);
  });

  // The DEM source is NOT in buildSources — useRegionDownload adds it (and the
  // hillshade layer above) only once master_dem.pmtiles is confirmed present,
  // so a deployment without terrain data doesn't request tiles that 204.
  it('does not ship a DEM source in the base style', () => {
    const sources = buildSources();
    expect(sources.dem).toBeUndefined();
  });

});

describe('buildStyle', () => {
  it('returns a valid MapLibre style spec (version 8)', () => {
    const style = buildStyle();
    expect(style.version).toBe(8);
  });

  it('has valid glyphs and sprite endpoints', () => {
    const style = buildStyle();
    expect(style.glyphs).toBe('/api/fonts/{fontstack}/{range}.pbf');
    expect(style.sprite).toBe('/api/sprite/usgs');
  });

  it('has sources with required tile URLs', () => {
    const style = buildStyle();
    expect(style.sources.basemap.tiles).toBeDefined();
    expect(style.sources.basemap.tiles[0]).toContain('/api/tiles/basemap/');
  });

  it('has layers array with all layer groups', () => {
    const style = buildStyle();
    expect(Array.isArray(style.layers)).toBe(true);
    expect(style.layers.length).toBeGreaterThan(15);

    const layerIds = style.layers.map(l => l.id);
    expect(layerIds).toContain('background');
    // hillshade is deliberately absent — added at runtime with the DEM source.
    expect(layerIds).not.toContain('hillshade');
    expect(layerIds).toContain('earth');
    expect(layerIds).toContain('water_polygons');
  });

  it('hillshade is added at runtime, not baked into the style', () => {
    // buildHillshade exists but index.js deliberately leaves it out; see the
    // comment at the top of pages/maps/style/index.js.
    const style = buildStyle();
    const hs = style.layers.find(l => l.id === 'hillshade');
    expect(hs).toBeUndefined();
  });

  // The served style takes the other branch: a native client cannot add a source
  // to its own style after load, so relief is in the document or it never
  // arrives. Naming an archive that may be absent is safe only because the
  // backend drops unservable sources and their layers before anyone sees it.
  describe('includeDem (the document the backend serves)', () => {
    it('carries both elevation sources', () => {
      const style = buildStyle('123', { includeDem: true });
      expect(style.sources.dem.type).toBe('raster-dem');
      expect(style.sources.dem.tiles[0]).toContain('/api/tiles/master_dem/');
      expect(style.sources.dem_overview.maxzoom).toBe(7);
    });

    it('cache-busts the regional DEM like every other archive', () => {
      const style = buildStyle('123', { includeDem: true });
      expect(style.sources.dem.tiles[0]).toContain('?v=123');
    });

    it('draws relief above the area fills and below the line work', () => {
      // Under the fills the shading is buried — landcover_overview alone is a
      // 55% wash from z8.5 to z13 — which reads as a DEM that never loaded.
      const ids = buildStyle('', { includeDem: true }).layers.map(l => l.id);
      const firstRoad = ids.findIndex(id => id.startsWith('roads'));
      for (const fill of ['landcover_overview', 'veg_fill', 'publiclands_fill']) {
        expect(ids.indexOf('hillshade')).toBeGreaterThan(ids.indexOf(fill));
        expect(ids.indexOf('hillshade_overview')).toBeGreaterThan(ids.indexOf(fill));
      }
      expect(ids.indexOf('hillshade')).toBeLessThan(firstRoad);
      // Sharp regional shading above the overzoomed global relief.
      expect(ids.indexOf('hillshade')).toBeGreaterThan(ids.indexOf('hillshade_overview'));
    });

    it('crossfades the two relief layers instead of stacking them', () => {
      // Both layers are semi-transparent, so any zoom where both draw at
      // strength shades the same hills twice — and by then the overview is
      // overzoomed, so what lands on top of the sharp relief is a blurry copy
      // of it. Their exaggerations must therefore be complementary: as one
      // rises the other falls, and the total is flat across the handover.
      const layers = buildStyle('', { includeDem: true }).layers;

      const rampAt = (id) => {
        const stops = layers.find(l => l.id === id).paint['hillshade-exaggeration'];
        // ["interpolate", ["linear"], ["zoom"], z0, v0, z1, v1, ...]
        const pairs = [];
        for (let i = 3; i < stops.length; i += 2) pairs.push([stops[i], stops[i + 1]]);
        return (zoom) => {
          if (zoom <= pairs[0][0]) return pairs[0][1];
          if (zoom >= pairs[pairs.length - 1][0]) return pairs[pairs.length - 1][1];
          for (let i = 1; i < pairs.length; i++) {
            const [z0, v0] = pairs[i - 1];
            const [z1, v1] = pairs[i];
            if (zoom <= z1) return v0 + ((v1 - v0) * (zoom - z0)) / (z1 - z0);
          }
          return 0;
        };
      };

      const overview = rampAt('hillshade_overview');
      const regional = rampAt('hillshade');
      const total = overview(8);

      expect(total).toBeGreaterThan(0);
      for (const zoom of [8, 8.5, 9, 9.5, 10, 12]) {
        expect(overview(zoom) + regional(zoom)).toBeCloseTo(total, 5);
      }
      // And they really do swap ends, rather than both being flat.
      expect(overview(8)).toBeGreaterThan(overview(10));
      expect(regional(10)).toBeGreaterThan(regional(8));
      expect(overview(10)).toBe(0);
    });

    it('still references only defined sources', () => {
      const style = buildStyle('', { includeDem: true });
      const names = Object.keys(style.sources);
      style.layers.forEach(l => {
        if (l.source) expect(names).toContain(l.source);
      });
    });

    it('is off by default, so the browser is unaffected', () => {
      const ids = buildStyle().layers.map(l => l.id);
      expect(ids).not.toContain('hillshade');
      expect(ids).not.toContain('hillshade_overview');
      expect(buildStyle().sources.dem).toBeUndefined();
    });
  });


  it('references only defined source names in layers', () => {
    const style = buildStyle();
    const sourceNames = Object.keys(style.sources);
    style.layers.forEach(layer => {
      if (layer.source) {
        expect(sourceNames).toContain(layer.source);
      }
    });
  });

  it('background color is #F8F6F0 (USGS paper)', () => {
    const style = buildStyle();
    const bg = style.layers.find(l => l.id === 'background');
    expect(bg).toBeDefined();
    expect(bg.paint['background-color']).toBe('#F8F6F0');
  });

  it('contours source is vector with correct URL and zoom range', () => {
    const sources = buildSources();
    expect(sources.contours.type).toBe('vector');
    expect(sources.contours.tiles[0]).toMatch(/^\/api\/tiles\/master_contours\/\{z\}\/\{x\}\/\{y\}\.mvt/);
    expect(sources.contours.minzoom).toBe(9);
    expect(sources.contours.maxzoom).toBe(12);
  });

  it('contours layer group exists with index and intermediate layers', () => {
    const style = buildStyle();
    const ids = style.layers.map(l => l.id);
    expect(ids).toContain('contours_index');
    expect(ids).toContain('contours_intermediate');
    expect(ids).toContain('contours_labels');
  });
});
