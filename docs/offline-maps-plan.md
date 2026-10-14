<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Offline maps: the plan

The plan of record for rebuilding offline maps, from the independent audit in
[offline-maps-audit.md](offline-maps-audit.md) and the maintainer's decisions on
2026-10-14. Finding numbers (F1–F24) refer to the audit.

## Goals

- **A phone holds as much map as its owner wants** (tens of GB, whole states or
  countries), on internal storage or an SD card, within a budget the user sets.
- **A download never fails silently, never restarts from zero, and never runs
  twice.** This holds through app death, phone sleep, network loss, server
  restarts, and two clients downloading at once.
- **The server stores each map once** and serves it from one file per layer.
- **A small area costs about a minute on the server**, however much is already
  installed.

## Decisions

| # | Decision | Chosen |
|---|---|---|
| 1 | Phone storage | **PMTiles packs, one per z8 cell and layer, downloaded whole** and served to MapLibre from the phone. They replace MapLibre's offline regions. |
| 2 | Server storage | **Masters only.** A download builds its parts, folds them into the master files, and deletes the parts. Overlaps are never stored twice, and nothing is physically merged: an area is an outline in the registry. |
| 3 | Deleting from the phone | **The phone removes only its own copy.** Deleting a server area is the web app's job, with a confirmation. |
| 4 | Version-stamp quick fix (F1) | **Shipped first** (done, uncommitted). The phone's style carries no `?v=`, and Caddy revalidates unversioned tiles. |
| 5 | Pack cell size | z8 (about 60–90 MB per cell and layer set). *Default; open to change.* |
| 6 | Pack downloads authenticated | Yes, by Caddy `forward_auth` with the phone's token. *Default.* |
| 7 | Pack updates | A prompt ("Updates available: 1.2 GB"), plus a setting for automatic updates on Wi-Fi. *Default.* |
| 8 | Phone transfer API | User-initiated data transfer jobs on Android 14+, a WorkManager foreground worker below. Adds `RUN_USER_INITIATED_JOBS`. *Default.* |
| 9 | Refreshing the OSM data cache | Monthly, or on demand. *Default.* |

## Packs are cached on the server, not stored

A resumable multi-GB download needs a stable file behind it, so a pack cannot
be generated on the fly for every request. Packs are cut from the masters
(`pmtiles extract`, seconds per cell), deterministically. The same masters
always give the same bytes and the same sha256. They are kept in a
**size-capped cache** (`/map-data/packs`; default cap 10% of the masters, set
by the admin), evicted least-recently-used, and rebuilt on demand. So the
server holds one permanent copy of the map plus a bounded scratch area, never
a full second copy.

## Masters only: what changes on the server

- **Build.** Build the new area's parts in a work directory, publish them into
  each master (a k-way merge by tile id; the overlay tile-joins only shared
  tiles), then delete the parts. A master is replaced atomically (written
  beside it, then renamed), and only after every part built. A failed build
  leaves the masters untouched (F13, F16, F20).
- **Delete.** Rebuild each master without the area's tiles:
  - Tiles that no remaining area touches are dropped.
  - Tiles that a remaining area also covers stay as they are for the basemap,
    DEM and contours.
  - Overlay tiles on the shared border are re-clipped to the remaining areas.
    This is the one involved piece: those tiles are rebuilt from the OSM cache
    for the border band only.
- **Recovery.** With no parts on disk, a damaged master is rebuilt by
  re-running the registry's areas from source. Before replacing a master, the
  previous one is kept until the new one opens and validates.
- **One build at a time.** A database-backed queue with an owner and a
  heartbeat (F12, F14, F15, F23) replaces per-process threads and locks, so
  two uvicorn workers can never build at once, and a dead worker's job is
  picked up rather than duplicated.

## Stages

Each stage is shippable on its own and leaves the app working.

### Stage 1: stop the bleeding (1.6.1)

- [x] F1: no tile version for native clients, and Caddy revalidates unversioned tiles.
- [x] F4: phone Cancel and Remove touch only the phone's copy.
- [x] Merged-away areas reach the phone (404 → covering area).
- [x] Overlay master tile-joins only shared tiles (6.4 min → 12 s); contours 2.7× faster.
- [x] F19: the startup orphan check must not mark resuming builds as installed.
- [x] F18: contours rebuild into a temp file, then replace.
- [x] Web app: deleting a server area asks for confirmation, admin only (the server refuses non-admins).

### Stage 2: server robustness — done (74c0143)

- The DB-backed build queue with heartbeat (F12, F14, F15, F23). "Stale"
  becomes a dead heartbeat, not 20 quiet minutes.
- Atomic phase outputs. A build resumes from its last finished phase (F16) and
  records per-layer status, so "installed with no trails" cannot happen
  silently (F20).
- The Geofabrik fetch gets timeouts, Range resume, progress, a multi-extract
  cover for boxes crossing borders, and a monthly refresh (F21). Disk-space
  preflight (F22).

### Stage 3: masters only — done (74c0143)

One deviation, for a reason found while building it: the overlay keeps one
part per area (about 15% of a map's size). Its tiles mix features from
neighbouring areas, so removing an area needs to know which features were its.
Each part is cut to the ground its area *owns* (its box minus what installed
areas covered), so parts never overlap and nothing is drawn twice. The other
85% — basemap detail, terrain, contours — is masters only.

- Build → publish → delete parts, delete by rebuild-without, and the k-way
  master merge. Tile counts come from headers, with no `pmtiles cluster` pass
  (audit §3.2).
- Physical merging is removed. Areas are registry outlines, grouped for display.
- A one-time migration publishes existing regions' parts and deletes them.

### Stage 4: packs on the server — done (8cce8bf)

- `GET /api/maps/packs/manifest?bbox=…` with the cell, layers, bytes, sha256 and version.
- The pack builder, the size-capped LRU cache, and Caddy `file_server` with
  Range, `If-Range`, ETag and `forward_auth`.
- The shared packs: world basemap z0–7 (189 MB), world DEM z0–5 (82 MB), and
  overlay z6–7.
- A style-assets bundle (glyphs and sprite).

### Stage 5: packs on the phone — built, unverified on a device

The registry is an atomic JSON file rather than SQLDelight: it is Android-only
bookkeeping, and a file written whole and renamed needs no schema migration.

- **Registry and areas** in SQLDelight, with a schema bump, migration and
  snapshot. The sheet lists the user's areas; coverage outlines come from the
  cells the phone holds (F2, F3).
- **Downloader:**
  - user-initiated jobs, or WorkManager below Android 14;
  - Range resume, sha256, rename into place;
  - 2–4 packs in parallel;
  - Wi-Fi-only by default;
  - storage budget, volume picker and free-space reserve.
- **An OkHttp interceptor** serves `/api/tiles/...` from local packs and falls
  through to the network. To verify on a device: MapLibre's offline gating
  (`setConnected`), and the basemap z13–15 blank (F24).
- **Readers.** Offline DEM and POI search read the packs. Routing keeps `rd5`.
- **Migration.** Existing MapLibre regions become areas and are re-saved as
  packs. Once nothing uses them, the old offline database is reset and
  `OfflineTiles`, `PendingRegions`, `MapDownloadService` and `BeyondPhone`
  are removed.

### Stage 6: speed

Measured after stages 2-5: reading a small area out of a cached state
extract takes about ten seconds, so pre-splitting the OSM extracts would save
little and is not done. Contours are retraced only along the border (the
ring in map_builds), since areas are no longer merged.

- The OSM extract is pre-split into a grid, so a download reads only its cells.
- Contours are retraced only along the border.
- Later, if needed: a small tile router, removing the go-pmtiles restart gap.

## Verification for every stage

Unit and integration tests for each finding fixed (each test names the
failure it prevents), the backend map suites, and the phone suites. On top of
those:

**Stages 3 and 5, on a device:**
- area sizes from 20 km to a whole state;
- killing the app mid-pull;
- airplane mode mid-pack;
- restarting the server mid-build;
- a browser and the phone downloading the same ground at once;
- filling the phone to its budget;
- removing the SD card;
- rebuilding an area while the phone holds it.
