<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

> Independent audit written 2026-10-14. The plan of record, with the maintainer's decisions (which override §3.1 and §6.3 here: the server keeps masters only), is [offline-maps-plan.md](offline-maps-plan.md).

# Offline maps in Tracks: audit and redesign

*Second-pair-of-eyes review, 2026-10-14. Read-only: no repo files were changed and no region was started, deleted or reset. The only things run were the existing tests (`test_tippecanoe_writer`, `test_contour_mvt`, `test_contour_workers`: 16 passed), `pmtiles extract --dry-run` against the local planet files to measure sizes, and header reads of the archives in `map-data/`.*

---

## 0. Summary

### The biggest finding, and probably most of the "months of problems"

**When anything on the server changes, every area already saved on the phone quietly loses its trails, contours and terrain.**

The served style versions four of its tile URLs with the newest archive mtime: overlay, contours, DEM and routes, as `…/{z}/{x}/{y}.mvt?v=<mtime>`. MapLibre Native's offline database keys tiles by URL template, `?v=` included. So the version changes after:

- any later region download anywhere (browser or phone),
- any delete or merge,
- the startup contour regeneration (which runs again after every restart that follows a merge),
- the long-route build.

Once it has changed, the live map asks for `?v=NEW`, and the stored region holds `?v=OLD`. With no signal the phone shows only the basemap, while the sheet still says **Ready** with the green "on phone" outline. Details are in F1.

### Top findings, by priority

| # | Sev | Finding |
|---|---|---|
| F1 | **Critical** | `?v=` tile versioning makes stored phone regions useless for overlay, contours and DEM after any server change. |
| F2 | **High** | Framing an area inside an existing server region marks the whole server region as "on phone". A second area inside the same server region is never downloaded: the old region is resumed instead. |
| F3 | **High** | A merged survivor's outline is drawn as phone coverage over ground the phone never stored. |
| F4 | **High** | Pressing "Cancel" on a row that is still saving to the phone deletes the server region, for every account on the instance, with no confirmation. |
| F5 | **High** | Nothing locks across processes. `_merge_lock`, `_contour_lock` and the OSM source locks are per-process, and temp paths are fixed names. Two builds running in two uvicorn workers (2 in production, 4 in dev) can corrupt the masters or fail each other. |
| F6 | **High** | Masters are assembled from every active region's files whatever its status. That includes half-written `source.pmtiles` from builds still in progress (go-pmtiles writes straight to the final path) and errored regions. |
| F7 | **High** | "Stale" means 20 minutes with no status update, not "the worker is dead". Several long phases report no progress. The phone then gives up on a build that is still alive, and DELETE on a "stale" build purges files underneath a live pipeline. |
| F8 | **High** | If a uvicorn worker dies, its replacement can become primary and `resume_interrupted()` restarts builds that are still running in other workers, so the same region is built twice at once. |
| F9 | Medium-High | The Geofabrik download uses `timeout=None`, reports no progress, cannot be cancelled while stalled, is not resumable, and picks the smallest *single* extract. A box that crosses a state line pulls a whole country or continent (`us-west.filtered.osm.pbf` is 1.6 GB in `map-data/osm`). |
| F10 | Medium-High | Partial builds are silent. An overlay, contour or OSM failure still ends in "installed", without trails or contours, and says nothing. |

Medium findings (§1.3): the phone hand-off has gaps (process death during a merge, extras skipped after a resume, the foreground service started from the background, Android 15's 6-hour `dataSync` limit); restarting a build starts it from zero; a contour retrace deletes the old archive before building the new one; cancelling during "combining" is lost; the startup orphan check marks resuming builds "installed"; and the phone's basemap goes blank at z13+ outside downloaded areas.

### Recommended architecture (short version)

Keep the server as the place maps are built. Stop pulling tiles one by one into MapLibre's offline database. Instead:

1. **The server publishes map *packs*: plain PMTiles files on a fixed tile grid.** Each pack is one z8 tile's worth of ground, and there is one per layer (basemap z8–15, overlay, contours, DEM). Packs are cut from the existing masters with a local `pmtiles extract`, which takes seconds. Each has a manifest entry (bytes, sha256, version) and is served with HTTP Range and ETag.
   - Separately: a world overview pack. Measured today it is 189 MB for basemap z0–7 plus 82 MB for DEM z0–5, about 270 MB.
   - Separately: a small pack of overlay tiles at z6–7.
2. **The phone downloads whole files.** Downloads are resumable over Range, verified against sha256 and renamed atomically into place. They run in a User-Initiated Data Transfer job on Android 14+, or a WorkManager foreground worker on older versions. Wi-Fi only by default. Files go to internal or SD storage, chosen by the user, up to a budget the user sets.
3. **The phone serves those packs to MapLibre through an OkHttp interceptor.** The app already installs its own `OkHttpClient` into MapLibre (`TracksApplication.raiseMapTileConcurrency`). The interceptor catches `/api/tiles/{set}/{z}/{x}/{y}` whatever the host or `?v=`, and answers from the local pack. Anything not held falls through to the network.
   - The style is unchanged and not duplicated.
   - There is no limit on the number of packs.
   - It also fixes F1 and the problem of the server URL changing.
   - The vendored MapLibre AAR does contain `PMTilesFileSource` and `MBTilesFileSource` (`pmtiles://`, `mbtiles://`). They suit a single file per source, but not many packs (see §2.2).

At the measured density (one installed region: 1.6 GB for about 245,000 km² of mountain terrain), **50 GB is on the order of the contiguous United States at full detail**. As whole-file downloads, it takes minutes per state on Wi-Fi, not hours of tile requests.

### Decisions for the maintainer (detail in §6)

1. Grid cell zoom for packs: z8 (recommended) or z7.
2. Authentication on pack downloads (recommended: yes, through Caddy `forward_auth`).
3. Whether to stop merging regions physically on the server (recommended: keep archives per download and group only in the registry).
4. Whether region deletes should need an admin, and a confirmation.
5. Pack updates: automatic on Wi-Fi, or an "update available" prompt.
6. Whether the quick fix for F1 ships before the pack work.
7. Whether to adopt UIDT jobs (adds the `RUN_USER_INITIATED_JOBS` permission).

---

## 1. Download reliability, end to end

The format for each finding is: **Where**, then the **Scenario**, then the **Fix**. Line numbers are against the working tree, uncommitted changes included.

### 1.1 Phone correctness

#### F1: Critical. Tile URL versioning silently breaks stored regions

**Where**

- `frontend/src/pages/maps/style/sources.js:33` (`buildSourceUrl` adds `?v=`) and the `overlay`, `routes_osm` and DEM sources.
- `backend/app/routes/map_style.py:69-80` (`_tile_version()`: the newest mtime across `TILESETS`), applied at `:180`.
- `backend/app/routes/tiles.py` (`TILESETS = master_dem, master_contours, master_overlay, master_routes`).
- `mobile/.../map/OfflineTiles.kt:181-218`: the offline definition stores `styleUrl`, and MapLibre keys tiles by template URL.
- `mobile/.../ui/map/MapViewModel.kt:80-116`: the live map uses the newest fetched style and caches it for offline use.

**Scenario.** The phone saves area A, and its offline DB holds `master_overlay/…?v=100`. A week later someone downloads area B in the browser, and `master_overlay` is rewritten with mtime 200. Next time the phone is online, `MapViewModel.load()` fetches and caches the style with `?v=200`. In the valley the phone is offline, and MapLibre looks up `…?v=200` in its offline DB and misses. The phone shows the basemap only: no trails, contours or hillshade. Meanwhile `OfflineTiles.stored()` still reports A complete, so the sheet says Ready and the outline is green.

Any master rewrite bumps the version:

- `region_merger.*`, i.e. every download, delete and merge.
- `main.py:464`: the startup regeneration fires whenever `master_contours` is older than `master_dem`. `merge_regions` rebuilds `master_dem` *after* the contour retrace (`region_registry.py:284-287`), so every restart after a merge regenerates `master_contours` and bumps the version again.
- `main.py:322`: the orphan rebuild.
- The long-route build.

**Fix, quick (days).**

1. Stop versioning tile URLs in the native style. Drop `?v=` from tiles when `base_url` is set, or entirely.
2. In Caddy, mark unversioned `/api/tiles/master_*` responses `Cache-Control: no-cache` so they revalidate by ETag, and keep `immutable` only for requests that carry a `v` query. Today `@other_tiles` (`caddy/Caddyfile:184-206`) makes everything there immutable for a year, which would be wrong without the version.
3. After shipping, re-walk existing phone regions once (`resumeIncomplete` after `invalidateAmbientCache` re-fetches the new URLs).
4. Add a test that the native style has no `?v=`.

**Fix, structural.** The interceptor in §2 ignores host and query, so the problem cannot come back.

#### F2: High. "Exists" and "already building" answers attach a small framed area to a large server region

**Where.** `RegionsViewModel.kt:345-401` (`download`), `storeOnPhone(ready, bounds)` at `:400`, `OfflineTiles.existing()` at `OfflineTiles.kt:343-352`, `reload()` at `:672-688`, and the server's `_already_have` at `crud.py:116-141`.

**Scenario 1.** The server holds "Montana" (id 7). On the phone the user frames a 20 km box inside it. The server answers `{"status":"exists","region":{id:7}}`, the phone stores the framed box under metadata `serverRegionId=7`, and `reload()` joins phone and server rows on that id. The **whole state** then draws as Ready with the green "on phone" outline, which is exactly the false offline promise the code comments warn against.

**Scenario 2.** The user then frames a second box elsewhere in Montana. `existing(7, bounds2)` matches on the server id first and returns the *first* box's region. `adopt()` runs, then `setDownloadState(ACTIVE)` on the old region, which re-verifies box 1 and reports Done. **Box 2 is never downloaded.** The same happens when the server answers "downloading" with a larger region that is still building and covers the box.

**Fix.** Apply the uncommitted "absorbed" rule to every case where the server region is not exactly the framed box: store the framed area as its own phone row (`serverRegionId = null`, or a separate `containedIn` field so it can still be linked). In `existing()`, match on bounds only for such rows. Better still, retire this logic with the pack design, where phone holdings are defined by cells rather than by server region ids.

#### F3: High. Merged survivors claim phone coverage they don't have

**Where.** `RegionsViewModel.kt:676-688`. The row takes `bounds = region.bbox`, `shape = region.shapeGeoJson`, `coverage = coverageOf(Ready)`.

**Scenario.** The phone stored region 5. Later area 9 is downloaded from the browser, overlaps it, and is folded into 5 (`merge_regions` keeps the oldest id). Phone row 5 is still `complete`, so the outline drawn is the *union* polygon, in "Phone" colour. Ground from area 9 is promised offline but was never stored.

**Fix.** A phone row's outline must come from the phone's own stored bounds (`phone.bounds`), never from the server's geometry. Show server coverage as a separate outline. With packs, phone coverage is simply the set of cells held.

#### F4: High. "Cancel" on a row saving to the phone deletes the server's area

**Where.** `RegionsSheet.kt:352`, where the label reads "Cancel" for every phase except Ready, and `RegionsViewModel.delete()` at `:444-457`, which always calls `deleteRegion(serverId)`. The server side is `crud.py:209-246`: any authenticated user, no admin check.

**Scenario.** The server finished building "Oregon" an hour ago, and the phone is 40% through storing it. The user taps "Cancel" to stop the phone pull. The phone deletes its copy and also DELETEs the server region. Oregon is purged for every account on the instance, and every master is rebuilt.

**Fix.**

- For a row whose server half is installed, "Cancel" must only stop or remove the phone copy.
- Deleting a server area should take a confirmation that names its size, and arguably admin rights on a multi-account instance (decision §6.4).

#### F5: Medium-High. A request that outlives its process is lost when the area is merged

**Where.** `PendingRegions.kt:46-56` stores only server ids. `pickUpUnfinished()` at `RegionsViewModel.kt:734-753` iterates only over the server listing.

**Scenario.** The user taps download and pockets the phone, and the process is killed during the build. The new area overlaps an existing one, so the server builds it and folds it in, deleting the new id. Next launch, `wanted = {newId}`, but `newId` is not in the listing. The request is never fulfilled and never cleared: the same "silent no-op" `PendingRegions` was written to prevent. The uncommitted 404 handling only covers a coroutine that is still alive.

**Fix.** Persist the *framed bounds and name* with each request, not just the id. When the id is gone, resolve it the same way `absorbedInto` does. With packs, a request is "these cells", which survives anything.

#### F6: Medium. Routing, POI and DEM extras only run when this view model sees Done

**Where.** `RegionsViewModel.kt:587-613`. `pickUpUnfinished` skips regions that are already `downloading` (`:747-749`) and settles complete ones (`:743-746`).

**Scenario.** The tile pull is interrupted, `MapDownloadService` or `refresh()` resumes it, and it completes without `storeOnPhone` collecting. Offline routing, offline search and point elevation are never fetched for that area, and nothing reports it.

**Fix.** Make the extras part of the persisted job. With packs, they are simply more layers in the cell manifest.

#### F7: Medium. Foreground service lifecycle

**Where.** `MapDownloadService.kt`.

1. It is started from `storeOnPhone` (`RegionsViewModel.kt:551`) *after* `awaitServerReady`. That moment usually comes minutes after the tap, with the phone in a pocket. From the background, `startForegroundService` throws on Android 12+. The app declares `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, but that exemption only holds while a companion association exists. A user with no paired watch gets an unprotected download, which is the failure the service exists to prevent. The comment at `:421-427` assumes this "should not happen".
2. Race: `watch()` lists regions and stands down if none are unfinished (`:337-341`). It is started *before* `offline.download` creates the MapLibre region, so it can stand down at once.
3. `targetSdk 35`: Android 15 caps `dataSync` foreground services at 6 hours per 24. The service does not override `onTimeout`, so the system crashes it when the cap is hit (RemoteServiceException). Hours-long tile pulls are exactly what reaches the cap.
4. The server build phase (`awaitServerReady`) has no foreground protection at all. On Android 14+ a cached process is frozen, so the hand-off only happens when the user reopens the map.

**Fix.** Start the long-running job at the tap, while the app is visible, and let it own *both* the server wait and the transfer. Use a UIDT job on API 34+ (no 6-hour cap; system retries on stop) and WorkManager `setForeground` below that. Implement `onTimeout` and `onStopJob` so they persist progress and stop. See §2.5.

#### F8: Medium. Zoomed-out offline maps depend on the ambient cache

**Where.** `OfflineTiles.MIN_ZOOM = 8` (`OfflineTiles.kt:515`).

**Scenario.** z0–7 is "already in the ambient cache", but that cache is an LRU of about 50 MB. After a few days of browsing elsewhere, zooming out in the field shows nothing.

**Fix.** Use a world overview pack (§2.3).

#### F9: Medium. A change of server URL kills all offline data

**Where.** `TracksClient.kt:439`: `?base_url=origin()` makes tile URLs absolute, with the phone's current host.

**Scenario.** A LAN IP changes to a domain, or a Tailscale name is added. Every stored tile key holds the old host.

**Fix.** The interceptor matches on path only.

#### F10: Low-Medium. "Resume" on a phone-only paused row always errors

**Where.** `RegionsSheet.kt:337-338` shows Resume for any `Paused` row. `storeExisting()` (`RegionsViewModel.kt:416-425`) requires `serverId`. The new absorbed rows have none.

**Fix.** Resume phone-only rows through `OfflineTiles.download(..., serverRegionId = null, bounds)` (i.e. `resumeIncomplete` for that id).

#### F11: Low-Medium. Storage accounting and leaks

**Where.** `storedBytes` sums `completedResourceSize` per region, so shared resources are counted twice. DEM tiles (`filesDir/dem/…`, one file per tile), the POI JSON and routing segments are never removed with an area.

`OfflinePoiData` keeps one ever-growing JSON file, reads all of it into memory and rewrites it whole on each add. The server refuses POI exports more than a few degrees wide, so large areas silently get no offline search.

**Fix.** Track everything per cell in a pack registry.

### 1.2 Server: concurrency, durability, cancellation

#### F12: High. Nothing locks across processes

**Where**

- `region_merger.py:49` (`_merge_lock = threading.Lock()`).
- `contour_generator/constants.py:89`.
- `osm_source/osmium_runner.py:34-43`.
- Fixed temp names:
  - `custom_merge.py:333` (`<master>.new`),
  - `tippecanoe_writer.merge_overlays` (`.building.pmtiles`, and in the uncommitted code the `…building.shared/` work directory, which is `rmtree`'d at start),
  - `osmium_runner.py:108/129` (`<extract>.raw.osm.pbf`, `.tmp.pbf`).

Downloads run in whichever worker takes the POST (`crud.py:188-191`). There are 2 workers in production (`deploy/rootfs/usr/local/bin/tracks-run:47`) and 4 in dev.

**Scenario.** The browser starts area A on worker 1, and the phone starts B on worker 2. Both reach `rebuild_master_overlay` together:

- both write `master_overlay.building.pmtiles`;
- B's `_join_shared_only` deletes A's `.shared` directory mid-join;
- both `os.replace` onto the master.

The result is a corrupt master, a failed build, or a master missing one region's tiles until the next rebuild. The same applies to two first-time downloads in one Geofabrik extract (both write the same `.raw.osm.pbf`) and to contour pools sized independently against the same free memory (an OOM risk).

**Fix.**

1. **Run one build queue in one process.** Builds become rows in a DB job table, claimed with `SELECT … FOR UPDATE SKIP LOCKED` or a Postgres advisory lock, and run by the primary worker or a separate worker process. Requests only enqueue. This also fixes F8 and F13, caps memory, and makes "two builds of the same bbox" impossible to start.
2. Short of that: put an `fcntl.flock` on `map_data_dir/.merge.lock` around every master or contour assembly, and use `tempfile.mkstemp(dir=…)` for every temp output.

#### F13: High. Masters include in-progress and errored regions' files

**Where.** `region_merger._source_paths` (`:269-275`) takes every active region whose file exists, whatever its status. `region_downloader.py:131` extracts straight to `src_dir/source.pmtiles`, with no temp file and rename.

**Scenario.**

- A is mid-extract. B finishes and calls `rebuild_master`, which reads A's partial `source.pmtiles`: the pmtiles reader throws and *B* fails, or it reads a truncated directory.
- Separately, an `error` region's leftover parts stay in the served masters forever.

**Fix.**

- Every phase writes `*.building.pmtiles` and renames on success, so a file that exists is a complete file.
- Masters include only regions in a published state (`installed`, or a new `ready_parts` flag).
- Regions in `error` keep their files out of the masters until retried or deleted.

#### F14: High. "Stale" is inferred from silence

**Where**

- `region_registry.py:52-56,306-312` (1200 s without `updated_at` moving).
- `crud.py:226-229`: DELETE on a stale build purges at once.
- `RegionsViewModel.awaitServerReady` (`:505`) gives up on `stale`.

**Phases that update nothing for long stretches**

- The Geofabrik download (`osmium_runner.py:111-118`).
- `osmium tags-filter` of a large extract.
- `osmium export`.
- `rebuild_master*` (an O(total) copy plus `pmtiles cluster`).
- Each `custom_merge` inside `merge_regions`.

Repeated identical `update_status` values also don't bump `updated_at` (SQLAlchemy skips no-op updates).

**Scenario.** A continent-sized first OSM fetch takes 25 minutes. The row turns stale and the phone shows "The server could not finish". The user deletes it, and `purge_region` removes the region directory and rebuilds every master *while the pipeline thread is still writing into it*.

**Fix.**

- Add a heartbeat: the pipeline owns a thread that touches `updated_at` (or a `heartbeat_at` column) every 30 s while the build thread lives.
- Record the owner (`worker_pid` plus a boot id). "Stale" then means "owner process gone", which the job-queue design gives for free.

#### F15: High. A respawned worker resumes live builds

**Where.** `main.py:378-392` (`_is_primary_worker` uses a flock held for the process's life) and `resume_interrupted()` (`download.py:168-247`), which restarts every row in a "working" status.

**Scenario.** Uvicorn's supervisor (0.30 and later) respawns a worker that died, for example from OOM during contours. If the dead worker was primary, the new one takes the primary flock, runs the lifespan and calls `resume_interrupted()`. That restarts *every* in-progress region, including builds still alive in the other worker. Two pipelines then write the same region directory.

**Fix.** Record the owner per build and resume only builds whose owner pid or boot id is gone. Or use the job queue (F12).

#### F16: Medium. Resume restarts from zero

**Where.** `download.py:226-238` (progress back to 0) and `pmtiles_extract.run_pmtiles_with_progress` ("not resumable — start fresh").

**Scenario.** A state extract that ran for an hour is 95% done when the container restarts, and the whole thing starts again.

**Fix.** Checkpoint each phase, as described under F13. On resume, skip any phase whose output is complete (`is_complete_archive()` already exists in `pmtiles_extract.py:319`).

#### F17: Medium. Cancel during "combining" is lost

**Where.** `crud.py:37-39` lists `combining` as in-progress, so a DELETE writes a marker and sets `cancelling`. Nothing in `merge_regions` checks the marker. Its `progress_cb` sets `combining` again (`download.py:131-134`), and the `finally` (`:137-145`) only settles a row that is still `combining`.

**Scenario.** The user cancels the new area during combining. The merge goes ahead and absorbs it into the survivor (the opposite of what was asked), or, if the merge fails, the row stays `cancelling` until it goes stale.

**Fix.** Either honour cancel before the merge commits (check before `mark_deleted`), or don't offer cancel once the download is installed (treat "combining" as installed).

#### F18: Medium. A contour retrace deletes the previous archive first

**Where.** `contour_generator/builder.py:245-246` unlinks `output_path` before building. That makes the bbox-copy branch at `:326` dead code, since `output_path.exists()` is always false there. `region_registry.py:239-242` says the merged contours are "a fallback" if the retrace fails.

**Scenario.** The retrace after a merge fails or is killed. The survivor has *no* `contours.pmtiles`, and the next master rebuild drops contours for the whole survivor.

**Fix.** Build into the temp file and `os.replace` it; never unlink the output first. Then the bbox-scoped regeneration works, which is also the speed fix in §3.

#### F19: Medium. The startup orphan check marks resuming builds "installed"

**Where.** `main.py:322` runs `region_merger.rebuild_master` with its default `mark_installed=True`, right after `resume_interrupted()` at `:300` has queued builds.

**Scenario.** Every non-error region is marked `installed`, including those that are re-extracting. Phones then start pulling a half-built area, and its holes are recorded as "complete".

**Fix.** Pass `mark_installed=False`, and remove `mark_installed` altogether. Only the pipeline should mark its own region installed.

#### F20: Medium. Silent partial builds

**Where**

- `thematic.py:204-215`: an overlay tippecanoe failure or a master overlay rebuild failure is non-fatal.
- `download.py:104-107`: contours are non-fatal.
- The OSM path falls back to `_build_thematic_legacy`, which falls back to public Overpass when `ensure_source` returns None (any download or osmium failure, or a full disk).

**Scenario.** The region reads "installed", but there are no trails, or no contours, or the build quietly spent hours against Overpass. Nobody is told.

**Fix.**

- Record per-part outcomes (`parts: {overlay: ok|failed, contours: …}`) on the region.
- Show "Installed. Trails failed, retry?".
- Offer retry for a single part.
- Never fall back to Overpass silently. Make it explicit, or remove it.

#### F21: Medium. The Geofabrik fetch

**Where.** `osmium_runner.py:111-125`: `httpx.stream(..., timeout=None)`, no Range, no progress, and a cancel check only between chunks.

**Problems**

- A stalled connection hangs the thread forever, cancel included, since the watcher can only kill subprocesses.
- A failure throws away gigabytes.
- `find_extract` (`geofabrik.py:147-179`) picks the single smallest extract covering the whole box. A box straddling a border picks the parent country or continent. `us-west` sits in your cache at 1.6 GB slim, several GB raw.
- The slim cache is never refreshed (`osmium_runner.py:106`), so trail data is frozen at first download.

**Fix**

- Set connect and read timeouts.
- Resume with Range into a `.part` file, and report MB/total.
- Cover a box with the *set* of smallest extracts that intersect it, then `osmium merge`.
- Refresh slim extracts on age, for example monthly, or on an admin "refresh OSM data" action.

#### F22: Low-Medium. Disk full

Nothing checks free space before a build, though the estimate is known. Failed `custom_merge` leaves `<master>.new` behind, since there is no `finally` unlink, and contour builds that fail for reasons other than a broken pool leave `.tmp` behind.

**Fix**

- Before starting, require at least 3 × the estimate (extract, then merge copy, then master copy).
- Clean temp files in `finally`.
- Fail with a message that says what is needed.

#### F23: Low. Double-queue at the HTTP layer

`_already_have` then `create` is a check-then-act (`crud.py:173-191`). Two simultaneous identical POSTs on different workers both create a region. This is rare, and closed by the queue (F12) or a unique key on a normalised bbox for active rows.

### 1.3 Edge cases checked, briefly

| Case | Today |
|---|---|
| Server restart mid-build | Resumed, but from zero (F16), and possibly twice (F15). |
| App killed during the server build | Recovered by `PendingRegions`, except when the area is merged (F5). |
| App killed during the tile pull | `resumeIncomplete()` restarts it, but the extras are lost (F6). |
| Phone sleep or Doze | No foreground protection during the server wait; the foreground service may fail to start (F7). |
| Network loss mid-pull | MapLibre retries, which is good. The service stands down after 15 minutes of no bytes and resumes on the next map open. |
| Server merges or deletes a region mid-pull | Tiles stay valid until the version bump (F1). A deleted region leaves an orphan row. |
| Browser and phone at once | Collisions across workers (F12, F13). |
| Cancel during each stage | Works for extracts and tippecanoe (killable). Not during `custom_merge` (pure Python) or combining (F17). |
| Corrupt or partial archive | Extract outputs aren't atomic (F13). Masters are atomic (`.new` then replace). |
| Style changes mid-pull | MapLibre fetches the style once per pull, then F1 applies. |
| Stale ambient cache | `invalidateAmbientCache` on coverage change, which is fine. |

### 1.4 Review of the uncommitted work

#### `RegionsViewModel.awaitServerReady` 404 → `absorbedInto` (direction right, incomplete)

1. **Timing.** `merge_regions` deletes the new id (`region_registry.py:264-266`) *before* the survivor's contours are retraced and the masters reassembled. That takes minutes for a large survivor. The phone sees 404 at once, starts its pull at version V, and the merge then bumps to V+1. Because of F1, the overlay, contours and DEM it just stored are dead within minutes. Fix F1 first, or wait for the survivor's version to settle.
2. The recovery path after process death is not covered (F5).
3. `absorbedInto` returns null on a *network* error during `mapRegions()`, and the caller reports "The server could not finish …". It should keep waiting on transport errors, as `awaitServerReady` does.
4. Center-in-envelope matching is fine, because `ready` only supplies the name and key, but the same rule should apply to `exists` and covering in-progress answers (F2).
5. Phone-only rows can't be resumed from the UI (F10).

#### `tippecanoe_writer._join_shared_only` and `custom_merge(prototype=, union extent)`

**Correct.** The feature union is right. Single-owner tiles are byte-identical to what tile-join produced. The union of bounds and zooms fixes the narrowing that the shared-tiles-last order would otherwise cause. Tests pass.

**Still O(total installed), and heavier than it needs to be**

- `all_tiles(reader.get_bytes)` *reads every tile's bytes* just to count ids, then again to cut the parts, then `custom_merge` reads everything a third time, then `_count_tiles` (`region_merger.py:230-242`) reads the master a fourth time only to count tiles.
- Ids can be taken from directories without touching data (walk root and leaf entries, expanding `run_length`), and the header already carries `addressed_tiles_count`.
- `owners: dict[int,int]` holds every tile of every region as Python objects, about 100 B or more per entry. Forty million overlay tiles (a few states) is roughly 4 GB or more of RAM. Use per-source sorted `numpy.uint64` id arrays and `np.intersect1d` / `np.unique(..., return_counts=True)`.

**Metadata.** `vector_layers` and other metadata now come from `srcs[-1]` only, where tile-join produced their union. Nothing reads them today (grep), but a `pmtiles://` client or an inspector will see an incomplete layer list. Union the `vector_layers` arrays in `_union_extent`.

**Work directory.** The work dir name is fixed, so it can collide across workers (F12).

#### Contours: `CachedReader`, worker decode cache, `mvt.encode_contours`

- The hand-written encoder follows MVT 2.1: packed tags, MoveTo/LineTo command integers, a per-feature cursor from (0,0), zigzag, `sint` for negative elevations, `bool` values, extent 4096, version 2. The test pins it against `mapbox_vector_tile`'s decoder.
- `CachedReader` walks at most 4 directory levels; the PMTiles spec allows 3. Fine.
- `_WORKER_DEM.clear()` drops a file handle without closing it. Harmless, since the pool is per build.
- Contour archives carry world bounds (`builder.py:305-308`). That is harmless for serving, but a pack or `pmtiles://` consumer should get the real bounds: compute them from the job list.

---

## 2. Phone storage at scale

### 2.1 Why MapLibre's offline regions cannot reach "50 GB, whole states"

- **One HTTP request per resource.** `OfflineEstimate` already counts about 1.2 M requests for one state across three full-band sources. 50 GB is tens of millions of requests. Even on a LAN at 100 requests/s that is days. This is why `BeyondPhone` exists.
- **URL-keyed storage.** That keying is the root of F1 and F9. Any change to a URL, version or host orphans the data.
- **One SQLite database in `filesDir`.** It moves only as a whole (`setResourcesCachePath`), there is no per-area file to verify, update or delete, and status queries scale with the resource count.
- **Downloads live in-process.** They need a foreground service (6-hour cap on Android 15, background-start rules) and depend on `resumeIncomplete` heuristics.
- **No integrity or versioning per area.** Updating means re-walking millions of URLs.

### 2.2 Options

| Option | How | Many packs? | No-signal behaviour | Cost |
|---|---|---|---|---|
| A. `pmtiles://file://` sources in a rewritten style | Phone edits the style so each source points at a local file. Supported since MapLibre Android 11.7.0, and present in the vendored AAR (`PMTilesFileSource`, `pmtiles://`, `file://` in `libmaplibre.so`). | **No.** One URL per source means either merging packs on the phone into one file per layer (rewriting GBs on every add or delete), or N sources × ~120 layers. | Good. No network involved. | Style rewriting on every style fetch, and no fallback to the network for ground not held. |
| B. **OkHttp interceptor** in front of MapLibre (recommended) | `HttpRequestUtil.setOkHttpClient` is already used. The interceptor matches `/api/tiles/{set}/{z}/{x}/{y}.{ext}` on any host, finds the cell's pack, and does a positional read with a cached PMTiles directory. If the pack lacks the tile, the request goes to the network. | **Yes.** O(1) cell lookup and any number of files. | Good, but MapLibre must not gate requests on connectivity (see below). | About 300 lines of Kotlin for the PMTiles reader and interceptor, unit-testable on the JVM against `spec/` fixtures. |
| C. Local HTTP server on 127.0.0.1 | NanoHTTPD-style; the style is rewritten to localhost. | Yes. | Same as B. | B plus a port, a lifecycle, a rewrite and a cleartext socket. B is strictly better here. |
| D. `mbtiles://` per layer, packs imported into SQLite | Download PMTiles, `INSERT` the tiles into one MBTiles per layer. Supported by the AAR's `MBTilesFileSource`. | Yes. The database is mutable. | Good. | Import time (minutes per GB), double disk during import, row-range deletes, and still a style rewrite. |

**Recommendation: B.**

- The style stays the server's, unchanged, with no duplicated layers.
- Ground not held still streams over the network when there is signal.
- `?v=` and the host become irrelevant.
- Packs stay byte-for-byte what the server published: verifiable, replaceable with one rename, deletable with one unlink.

**Things to verify on a device**

1. MapLibre's online file source pauses requests while it believes the device is offline (`ConnectivityReceiver`). The interceptor is only reached if MapLibre sends the request. Call `MapLibre.setConnected(true)` while packs are installed, and have the interceptor fail fast (synthetic error) for tiles not held when the OS reports no network.
2. Return raw gzipped MVT bodies. MapLibre inflates gzip payloads itself. Set `Cache-Control: no-store`, and check whether the ambient cache honours it (if not, the duplication is capped by the ambient cache size, about 50 MB).
3. For a basemap tile missing at z13–15, answer with an *error* (e.g. 503) rather than 404. Native then keeps the overzoomed parent on screen instead of drawing an empty tile. See F24.

### 2.3 Pack layout

**Cells.** A pack covers one **z8 tile** (about 156 × 156 km at the equator, about 110 × 156 km at 45°N). Each pack holds the tiles at zoom ≥ 8 that lie within the cell, so cells never share a tile. Per layer, per cell:

| Layer | Zooms | Source on the server |
|---|---|---|
| `basemap` | z8–15 | z8–12 from `planet_basemap`, z13–15 from `master_basemap_detail` |
| `overlay` | z8–15 | `master_overlay`, already seam-joined, so cells need no join |
| `contours` | z9–12 | `master_contours` |
| `dem` | z8–12 | `master_dem` |
| `poi` | — | `poi_search` rows for the cell, as SQLite with FTS: small, searchable offline, no growing JSON |
| `routing` | — | Keep BRouter's 5° `rd5` files as their own layer, referenced by the cells that need them |

**Shared packs**

| Pack | Contents | Size |
|---|---|---|
| `world` | `planet_basemap` z0–7 | **189 MB measured** (z0–6: 45 MB) |
| `world-dem` | `planet_dem_z7` z0–5 | **82 MB measured** (z0–6: 278 MB) |
| `overlay-low` | `master_overlay` z6–7, the only overlay tiles that span cells | Tens of MB at most |

**Producing a pack.** Run `pmtiles extract <master> <out> --bbox=<cell bounds shrunk by ε> --minzoom 8` locally. It takes seconds and needs no new data pipeline. Shrinking by ε keeps tiles from neighbouring cells out.

- Build packs lazily on first request and cache them at `/map-data/packs/8/x/y/<layer>.<sha8>.pmtiles`.
- A cell's version is a hash of the versions of the regions that touch it, so a rebuild in one place does not invalidate the whole country.
- Expect packs to be partial where regions only partly cover a cell. The interceptor falls through for tiles that are absent, and an update adds them when the server covers more.

**Sizes.** Region 13 in `map-data` covers about 7.6° × 3.8° (about 245,000 km²) and holds 1.6 GB across its four parts: DEM 775 MB, basemap 350 MB (z8–15), contours 265 MB, overlay 234 MB.

- That is about **55 MB per square degree**, or roughly 60–90 MB per z8 cell.
- A western state is about 3–8 GB.
- 50 GB is several million km².

### 2.4 What the server must expose

| Endpoint | Purpose |
|---|---|
| `GET /api/maps/packs/manifest?bbox=…` (or `?cells=`) | `[{cell:"8/45/92", layers:{basemap:{bytes,sha256,version,url}, overlay:…, contours:…, dem:…, poi:…}, routing:[…], built_at}]`, plus `world`, `world-dem` and `overlay-low`. |
| `POST /api/maps/packs/build {cells or bbox}` | Enqueue the server build if it's missing, using the existing region pipeline. Then build the packs. Returns job ids to poll, or the phone polls the manifest. |
| `GET /api/maps/packs/8/45/92/overlay.<sha8>.pmtiles` | Static files through Caddy `file_server`: Range, `If-Range`, and ETag. The content-hashed name makes every URL immutable, so `Cache-Control: immutable` is correct and an update is a new URL. Use `forward_auth` to the backend (decision §6.2): these are gigabytes, and the tile endpoints are already public. |
| `GET /api/maps/style-assets` | The glyph ranges for the style's fontstacks and sprite @1x/@2x, as one zip with a version. The interceptor serves `/api/fonts/…` and `/api/sprite…` from it. Today these come from the MapLibre region. |

The style (`/maps/style.json`) is already cached on the phone (`MapViewModel`). With the interceptor, the cached copy just works.

### 2.5 Phone side

#### Registry

A SQLDelight table (`TracksSchema` bump plus migration and snapshot, per AGENTS.md) with columns:

`cell, layer, version, sha256, bytes, path, volume_uuid, state(queued|downloading|verifying|ready|stale|missing), bytes_done, last_used_at, requested_by(area id)`

A second table of user *areas* (name, polygon or bounds, cells, created) is what the sheet lists. An area is ready when all its cells' layers are `ready`. Phone coverage outlines are drawn from cells held, never from server geometry (F3).

#### Transfer

- One job type:
  - **API 34+:** a `JobScheduler` UIDT job (`setUserInitiated(true)`, `setRequiredNetwork`, `setEstimatedNetworkBytes`, notification required, `RUN_USER_INITIATED_JOBS` permission). No 6-hour `dataSync` cap. The system reschedules on stop. App Standby quotas don't apply.
  - **API 26–33:** a WorkManager `CoroutineWorker` with `setForeground(dataSync)`.
  - Schedule it at the tap, while the app is visible, as UIDT requires.
- OkHttp with `Range: bytes=<len(.part)>-` and `If-Range: <etag>`:
  - 206: append.
  - 200: restart.
  - 416: treat as complete and verify.
  - ETag mismatch: discard the partial.
- `fsync`, stream sha256 (rehash the whole file after a resumed download; about 1 s per 300 MB), then `rename(.part → final)`, update the registry, delete the old version, and signal the interceptor to reopen.
- Several packs download in parallel (2–4). Throughput is the link's, not 24 tile requests at a time.

#### Policy

- Wi-Fi only (unmetered) by default, with a per-download override.
- "Battery not low" as an optional constraint.
- Pause when Data Saver is on.

#### Storage

- Volumes come from `context.getExternalFilesDirs(null)`: internal, plus SD cards. No permissions are needed, and the files go away on uninstall. Let the user pick a volume, and store its `StorageVolume.uuid`.
- An unmounted volume marks its packs `missing` without deleting them.
- Before each pack, check free space with `StatFs`, with a reserve of max(1 GB, 5%).
- Add a user **budget** (default: half of free space; editable). Areas are refused or queued past it, with the exact bytes taken from the manifest.

#### Integrity

- On start: each ready file exists, its size matches, and its magic is `PMTiles`.
- Full sha256 re-verify while idle and charging.
- A failed verify re-queues that pack only.

#### Updates

- Compare the manifest with the registry on each refresh.
- "Updates available: 1.2 GB": automatic on Wi-Fi, or a prompt (decision §6.5).
- The old file stays in service until the new one verifies. A file renamed over an open one keeps the old inode alive until it is closed, so the swap is safe.

#### Delete

Unlink the files; space comes back at once. Packs shared by overlapping areas are reference-counted by `requested_by`.

#### DEM, search, routing

- `OfflineDem` reads the `dem` pack through the same PMTiles reader. This removes per-tile files and the duplicate DEM copy.
- Offline search reads the per-cell POI SQLite files.
- Routing keeps `rd5` files.

### 2.6 Migrating from the current MapLibre offline regions

1. Ship the quick fix for F1 (no `?v=` for native) first, so current users' regions work in the meantime.
2. Ship the pack registry, the interceptor and the downloader behind a flag. The server side is manifest, pack builder and Caddy file routes.
3. On upgrade, list the MapLibre regions. Convert each region's bounds to an area of cells and show "Re-save 3 areas in the new format (2.1 GB, Wi-Fi)?". MapLibre regions keep working until their cells are ready: `DatabaseFileSource` is checked before the network, and the interceptor sits on the network path.
4. When an area's cells are ready, delete the MapLibre region. Once none are left, call `OfflineManager.resetDatabase()`, then remove `OfflineTiles`, `PendingRegions`, `MapDownloadService`, `OfflineEstimate`'s `BeyondPhone` and the per-tile DEM directory.
5. Keep MapLibre's ambient cache for online browsing.

**Offline-first (`docs/offline-first.md`).** This keeps the settled decision that maps come from a Tracks server ("Maps: no server → blank basemap; public tiles deferred"). The pack format is also what a future standalone mode would use from a public source, so nothing is closed off.

---

## 3. Server pipeline speed

Here is where each download still pays O(total installed) or O(survivor) cost, biggest first.

### 3.1 Auto-merge rewrites the survivor

**Where.** `region_registry.merge_regions`, `:243-291`.

Each part file of the survivor is re-merged (`custom_merge` / overlay join), and `build_region_contours(primary)` **retraces every contour tile of the survivor**. Adding a 20 km box beside a state-sized area therefore rewrites roughly 1.6 GB and retraces the state's contours.

**Fix.** Stop physically merging (decision §6.3).

- Keep the archives per download and use the union geometry only for display and grouping in the registry. Masters already join seams.
- For the contour seam, trace the new region's contours reading neighbours from `master_dem`, which holds both sides.
- Retrace only the *old* region's tiles within one tile of the new boundary, using the bbox-scoped path once F18 is fixed.
- That makes the cost O(new + border).

### 3.2 Each master is reassembled in full on every download and delete

There are four masters, each a byte-copy of all regions' parts, plus `pmtiles cluster` (a full rewrite) when under 1.5 GB, plus `_count_tiles` (a full read).

Cheap wins:

1. **k-way merge by tile id** (`heapq.merge` over each source's sorted directory entries). It is clustered by construction, so no `pmtiles cluster` pass is needed, and newest-wins is decided by source priority inside the heap.
2. Take tile counts from the header (`addressed_tiles_count`), not from `_count_tiles`.
3. In `_join_shared_only`, take ids from directories, not tile bytes, and use numpy arrays for the intersection (§1.4).
4. Rebuild only the masters a phase changed. `merge_regions` rebuilds three, and purge rebuilds four.

**Long term: no masters at all.** Write a small tile router (Go, about 150 lines, a sibling of go-pmtiles) that keeps an in-memory index of region or cell archives and their bounds.

- A tile owned by one archive is a byte read.
- Seam tiles come from a small `seams.pmtiles` that is updated incrementally (join only the new region's border tiles).
- Each download then costs O(new region), new archives open lazily, and the go-pmtiles restart trigger (`go-pmtiles/reload-wrapper.sh`: a gap of about 1–3 s in which tiles return 502 on each rebuild) goes away.

Packs (§2.3) can be cut from either design.

### 3.3 OSM source

- Cross-border boxes pull a parent extract (F21). Cover with several extracts and `osmium merge`.
- Each download runs `osmium extract -s smart` over the whole slim extract (1.6 GB for `us-west`). Pre-split each slim extract into a grid once (`osmium extract --config` with many boxes in one pass), so a download reads only the cells it touches.
- Make the first download resumable and report its progress (F21).

### 3.4 Run one build at a time, on purpose

Parallel builds today each size their contour pools and `tippecanoe -P` to the whole machine. A queue (F12) runs one build at full parallelism, which is faster in total and does not OOM.

### 3.5 Already good

Contours are now about 2.7× faster. DEM and basemap extracts run in parallel. Tippecanoe uses `-P`. The shared-only overlay join brought 6.4 minutes down to 12 seconds.

---

## 4. Other things about the map

1. **F24: Medium-High (verify on a device). The basemap goes blank at z13–15 outside downloaded areas on the phone.**
   - Once any region exists, `tile_coverage` lets the style's `basemap` source declare maxzoom 15 everywhere.
   - Outside regions, Caddy turns go-pmtiles' 204 into a 404 (`Caddyfile:151-160`), and MapLibre Native draws that as an empty tile. The `tile_coverage.py` docstring describes the same failure for the no-region case.
   - Fix: have the interceptor turn a basemap 404 at z ≥ 13 into an error so the parent is overzoomed. Alternatively, overzoom on the server for missing detail tiles: serve the z12 ancestor clipped and rescaled.
2. **The basemap is unversioned on purpose**, but `@bm_over` caches z0–12 for a day with stale-while-revalidate for a week. After a basemap reset, phones and browsers keep old tiles for up to a week. Low.
3. **`planet_dem_z7` is cached `immutable` for a year without a version** (under `@other_tiles`). A reset of the DEM overview never reaches browsers. Low.
4. **Tile endpoints are public**, including a 17.7 GB basemap. Anyone who can reach the instance can mirror it. That is deliberate for MapLibre, but worth rate-limiting in Caddy. Packs should be authenticated (§2.4).
5. **Region delete is instance-wide, needs no admin, and has no confirmation, on web or phone** (F4). Decision §6.4.
6. **`region_registry.update_status` ignores `deleted`.** A late progress callback can rewrite a deleted row's status. That is invisible today, since `get()` filters deleted rows, but it is a trap for the job queue.
7. **Empty region directories** (`regions/10`, `regions/11`) are left behind: `request_cancel()` runs `mkdir` on the directory after a purge. Cosmetic.
8. **The browser polls** regions every 4 s while active and the tile version every 10 s. Fine, but once F1's fix removes versions from native, keep the browser's own versioning or move both to ETag revalidation. Decide once, document it, and test it.
9. **`OfflineEstimate` and `tooLargeToDownload`** (100 sq°) become storage-budget checks against the manifest. The phone should stop doing tile arithmetic.

---

## 5. Suggested order of work

1. **This week.** Fix F1 (no `?v=` for native, plus the Caddy cache rule), F4 ("Cancel" must not delete the server area; confirm deletes), F19 (`mark_installed=False`), F18 (contour temp file then replace). Each is a few lines with a test.
2. **Next.** Fix F2 and F3: phone rows are framed areas, and outlines come from phone bounds. Then F10 and F5 (persist bounds in requests), and F20 (per-part status).
3. **Server robustness.** A DB-backed single build queue with owner and heartbeat (F12, F14, F15, F23), atomic phase outputs and publish-only masters (F13, F16), and the Geofabrik timeouts, Range, progress and multi-extract cover (F21, F9), plus the disk preflight (F22).
4. **Packs.** Manifest and builder on the server, then the registry, downloader (UIDT/WorkManager), Kotlin PMTiles reader and interceptor on the phone. Then the migration (§2.6).
5. **Speed.** Logical merge plus border-only contour retrace (§3.1), the k-way master merge and directory-only id scans (§3.2), and later the tile router.

---

## 6. Decisions needed from the maintainer

1. **Cell zoom.** z8 (about 60–90 MB per cell at today's density; finer updates and less over-download for small areas) or z7 (4× larger, fewer files). Recommendation: **z8**.
2. **Authentication on pack downloads.** Recommendation: **yes**, Caddy `forward_auth` to the backend using the phone's existing token. Tiles stay public for MapLibre.
3. **Physical auto-merge.** Recommendation: **stop rewriting archives**; group in the registry only (§3.1). This changes what "one area" means in the browser list.
4. **Who may delete server areas** on a multi-account instance, and with what confirmation. Recommendation: admin only, with a size shown.
5. **Pack updates.** Automatic on Wi-Fi, or prompt. Recommendation: prompt, with an "always on Wi-Fi" setting.
6. **Ship the F1 quick fix now**, before the pack work. Recommendation: **yes**; it is small and fixes current users.
7. **UIDT jobs** (API 34+, new `RUN_USER_INITIATED_JOBS` permission, no Jetpack wrapper), with WorkManager below 34. Recommendation: yes. It is the platform's intended API for user-started large downloads.
8. **OSM refresh cadence** for cached Geofabrik extracts (today: never). Recommendation: monthly, or on demand.

---

## 7. Sources

- MapLibre Native Android, PMTiles (since 11.7.0; `pmtiles://file://`; "PMTiles sources do not support offline pack downloads or caching"): https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
- Android 15 foreground service timeouts (`dataSync`: 6 h per 24 h, `onTimeout`, then a crash): https://developer.android.com/develop/background-work/services/fg-service-timeout and https://developer.android.com/about/versions/15/behavior-changes-15
- User-initiated data transfer jobs (API 34; `RUN_USER_INITIATED_JOBS`; notification; no Jetpack wrapper): https://developer.android.com/develop/background-work/background-tasks/uidt
- Checked locally:
  - the vendored `mobile/app/libs/maplibre-terrain.aar` (`libmaplibre.so` contains `PMTilesFileSource`, `MBTilesFileSource`, and the `pmtiles://`, `mbtiles://` and `file://` schemes);
  - `pmtiles extract --dry-run` against `map-data/planet_basemap.pmtiles` (z0–5 15 MB, z0–6 45 MB, z0–7 189 MB) and `planet_dem_z7.pmtiles` (z0–5 82 MB, z0–6 278 MB);
  - the PMTiles headers of `map-data/regions/13/*`.
