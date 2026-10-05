// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// MapLibre, with its tile worker wired up. Import maplibregl from here, never
// from "maplibre-gl" directly.
//
// MapLibre 6 finds its worker as a sibling of its own file
// (`new URL("./maplibre-gl-worker.mjs", import.meta.url)`). Vite pre-bundles
// and fingerprints that file in dev and in production alike, so the sibling
// is never there, every worker fails to start, and the map draws no tiles at
// all — while the page itself, and every unit test, looks fine. The worker
// also imports a shared chunk by relative path, so it cannot simply be copied
// as an asset either. `?worker&url` has Vite bundle it, imports included,
// and hands back the URL it ends up at.
import * as maplibregl from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

maplibregl.setWorkerUrl(workerUrl);

export default maplibregl;
