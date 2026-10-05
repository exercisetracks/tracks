// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Relief shading, in two layers that answer different questions.
//
// `hillshade_overview` reads the global z0-7 DEM and `hillshade` the sharp
// regional z8-12 archive. They **hand over**; they do not stack.
//
// That is the whole subtlety here, and getting it wrong is visible. Both layers
// are semi-transparent, so wherever both draw, the shading composites twice and
// the terrain goes muddy — and because the overview is being overzoomed by then,
// what composites on top of the sharp relief is a blurry copy of the same hills,
// half a zoom level out of step. On screen it reads as two hillshades at once,
// which is exactly what it is.
//
// So the two crossfade across z8-z10 on *complementary* ramps: as the overview
// falls from 0.5 to 0, the regional layer rises from 0 to 0.5, and their sum is
// 0.5 at every zoom in between. Fading only the overview is not enough and was
// the first attempt — the regional archive starts at z8, so at exactly z8 both
// layers sat at full strength and there was a narrow, very dark band before the
// fade did anything.
//
// Above z10 the sharp DEM is the only shading, and where there is no downloaded
// region there is none — which is honest. The alternative, keeping a floor of
// overzoomed relief everywhere, buys soft shape outside regions at the cost of
// muddying every region you actually downloaded.
//
// Both are drawn above the area fills and below the line work. Under the fills
// — where the runtime insert used to put them, anchored on the lakes — the
// shading is buried: `landcover_overview` alone is a 55% wash from z8.5 to z13,
// and vegetation and the public-land tint pile on above that. It reads as "the
// DEM isn't loading" rather than as subtle relief. Over water this costs
// nothing, because flat water has no slope for a hillshade to shade.
// How strong the relief is, once one layer owns it. The crossfade below splits
// this between the two layers rather than adding a second helping of it.
const RELIEF = 0.5;

// z8 is where the regional DEM's first tiles exist; z10 is where it is
// unambiguously in charge and the overzoomed overview would only blur it.
const HANDOVER_START = 8;
const HANDOVER_END = 10;

export function buildHillshade() {
  return [
    {
      id: "hillshade_overview",
      type: "hillshade",
      source: "dem_overview",
      paint: {
        // Full strength while it is the only relief there is, then out of the
        // way. Mirrored by `hillshade` below — the two must sum to RELIEF at
        // every zoom or the handover shows as a dark or a pale band.
        "hillshade-exaggeration": [
          "interpolate", ["linear"], ["zoom"],
          HANDOVER_START, RELIEF, HANDOVER_END, 0,
        ],
        "hillshade-illumination-direction": 315,
        "hillshade-illumination-anchor": "map",
        "hillshade-shadow-color": "#000000",
        "hillshade-highlight-color": "#FFFFFF",
        "hillshade-accent-color": "#000000",
      },
    },
    {
      id: "hillshade",
      type: "hillshade",
      source: "dem",
      paint: {
        // The mirror image of the overview's ramp above. Rising rather than
        // constant: the regional archive's first tiles are at z8, and coming in
        // at full strength there — while the overview was also still at full
        // strength — is what produced the dark band.
        "hillshade-exaggeration": [
          "interpolate", ["linear"], ["zoom"],
          HANDOVER_START, 0, HANDOVER_END, RELIEF,
        ],
        "hillshade-illumination-direction": 315,
        "hillshade-illumination-anchor": "map",
        "hillshade-shadow-color": "#000000",
        "hillshade-highlight-color": "#FFFFFF",
        "hillshade-accent-color": "#000000",
      },
    },
  ];
}

/**
 * Where a runtime insert has to put them to land where `buildStyle` does.
 *
 * The first road layer — the start of the line work, and so the end of the area
 * fills. Found rather than named: the pair is inserted from `ensureDemSource`
 * into a style this same module builds, and an id spelled out in both places is
 * an id that drifts in one of them.
 */
export function hillshadeBeforeId(layers) {
  return layers.find((l) => l.id.startsWith("roads"))?.id;
}
