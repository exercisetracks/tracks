# Watch sync (one button, three connection methods)

The **Sync watch** button in the sidebar (above Settings) runs a full
two-way sync — import new activities/wellness data, push pending workouts,
race plans, courses and the training calendar, and delete watch files marked
for cleanup — over whichever connection is available, tried in this order:

1. **Server USB** — if the watch is plugged into the host, the button fires
   the `garmin-sync` container's trigger and the cable path does everything.
2. **Browser USB (WebUSB/MTP)** — for MTP watches plugged into the computer
   you're browsing from. Skipped automatically on Windows (its MTP driver
   blocks browser access). A previously-authorised watch connects without
   any picker.
3. **Mounted folder** — mass-storage watches (e.g. Instinct 2): pick the
   mounted GARMIN volume. The handle is remembered, so later syncs are one
   click (zero if the browser still holds the permission).
4. **Manual upload** — a plain .fit file picker for anything else, replacing
   the old Settings upload section.

The last successful method is remembered and tried first next time; the
server probe runs silently in the background either way (with a skip button
when it's in the foreground). If the app is open when you plug a
previously-authorised watch into your computer, a sync prompt pops up on
its own.

Only the data folders are imported — `GARMIN/Activity`, `GARMIN/Sleep`,
`GARMIN/HRVStatus`, `GARMIN/Monitor` (the same list as the cable sync) — so
pushed workouts/courses are never re-imported as clutter. Both directions
share backend state with the cable path: whichever syncs first wins, the
other finds nothing left to do.

## The double-enumeration quirk

When a Garmin watch is plugged in it first enumerates as a **generic USB GPS
device**, then after ~10 seconds disconnects and re-appears as the properly
named MTP device (e.g. "fenix 6X Pro Solar"). Anything automating against
the watch must wait for the second appearance. The browser sync handles this
itself: the auto-popup only fires for the (authorised) MTP identity, and the
connect step retries for ~20 s, re-querying devices between attempts because
re-enumeration invalidates stale USB handles.

## Requirements

- **Chromium-based browser** (Chrome, Edge, Brave, Vivaldi…). Firefox and
  Safari do not implement WebUSB or the File System Access API.
- **HTTPS** (or `localhost`). On plain `http://` origins the browser removes
  both APIs entirely and the sync buttons explain why. A reverse proxy with a
  certificate (Caddy, Traefik, Tailscale HTTPS) is the usual homelab answer.

## Two device modes, two paths

Garmin devices talk USB in one of two modes; the Settings section offers a
button for each:

| Mode | Devices | Path |
|---|---|---|
| **MTP** | Music-capable/newer: Fenix 6 Pro/7/8, Epix, FR 245M/645M/745/945/955/965, Venu, Edge 540/840/1040… | **Connect watch over USB** (WebUSB) |
| **Mass storage** | Non-music/older: Instinct 2, FR 235, older Edge… | **Open mounted device folder** (pick the mounted `GARMIN` volume) |

If unsure: if the watch shows up as a normal drive in your file manager, use
the folder button; if it shows up as a "media device"/portable device, use the
USB button.

Every `.fit` file on the device is collected (Activity, Monitor, Sleep,
Metrics… — the Music folder is skipped on import; it is written to by the
separate music push below). The server tells the browser which
files it already has (`/fit/precheck`, matched on filename+size) so re-syncs
only transfer new files, and everything uploaded is still deduplicated by
content hash — syncing twice is always safe.

## Platform notes

### One watch, one owner at a time

After a successful USB sync the browser **keeps the MTP connection open**
(interface claimed) until the watch is unplugged or the tab closes. This is
deliberate: releasing it would let whatever else wants the watch — a
desktop's GVFS, or the Tracks server's own cable sync when the server runs
on the same computer — claim the interface, after which every further
browser sync fails with a claim error. Holding it also makes re-syncs
instant. Corollary: while the tab holds the watch, file managers (and the
server's cable sync) can't open it; unplugging always frees it.

For the same reason, opening the sync modal with a remembered browser
method does **not** fire the server's sync trigger — on a same-machine
setup that would send the cable sync chasing the very watch the browser is
about to claim. The server probe still runs whenever the modal lands on
the method-chooser.

### Linux (primary)

- If **"Could not claim the device's USB interface"**: your desktop's GVFS has
  the watch open — eject/unmount it in the file manager and retry. If the
  Tracks server runs on this same computer, its cable sync may have the
  watch (sidebar shows "Syncing watch…"); wait for it to finish and retry.
- If the device never appears in the browser's picker or opening fails with a
  permission error, add a udev rule:

  ```
  # /etc/udev/rules.d/51-garmin.rules
  SUBSYSTEM=="usb", ATTR{idVendor}=="091e", MODE="0664", TAG+="uaccess"
  ```

  then `sudo udevadm control --reload && sudo udevadm trigger` and replug.
  (Most desktop distros ship libmtp rules that already cover Garmin, so try
  without this first.)

### macOS

Works out of the box for MTP watches — macOS has no native MTP support, so
nothing else claims the device. (This makes the browser sync *more* capable
than Finder here.)

### Windows

- **MTP watches:** Windows binds its built-in MTP driver, which blocks the
  browser from claiming the interface — the UI detects this and says so.
  Swapping drivers with Zadig works but breaks Explorer file transfer; not
  recommended. Use the mounted-folder path, another OS, or manual upload.
- **Mass-storage watches:** the mounted-folder path works normally.

### On the watch

**Set the watch to MTP mode before connecting** — most watches (e.g. Fenix 6)
do not prompt on their own. On the watch: **Settings → System → USB Mode →
MTP**. In "Garmin" USB mode the device exposes a proprietary protocol the
browser can't speak, and connecting times out with a hint.

## Pushing files to the watch

Pushing happens automatically as part of every sync. Destinations follow
Garmin's conventions (same as the cable sync):

| Content | Device folder |
|---|---|
| Workouts, race plans, `SCHEDULE.fit` (training calendar) | `GARMIN/NewFiles/` |
| Courses | `GARMIN/Courses/` |
| AGPS (`CPE.bin`, when enabled and stale) | `GARMIN/REMOTESW/` (configurable) |
| Music tracks + `.m3u` playlists (opt-in, see below) | `Music/` — storage **root**, not under `GARMIN/` |
| Completed-workout cleanup | deleted from `GARMIN/Workouts/` |

The watch ingests NewFiles on reboot or next activity start. On the
mounted-folder path the browser asks to upgrade to write access before the
push phase. AGPS follows the same rules as the cable sync (the toggle and
config live under Settings → Privacy & Connectivity → AGPS Sync); the
server downloads the CPE data — Garmin's EPO server has no CORS headers —
and the browser writes it to the watch. Still cable-only: ingesting
external courses found on the watch.

## Implementation map

- `frontend/src/lib/mtp.js` — minimal MTP/PTP client over WebUSB (read +
  write: GetObject, SendObjectInfo/SendObject, DeleteObject, folders).
- `frontend/src/lib/deviceSync.js` — selective folder scan, precheck,
  batched import upload, push targets, `runFullSync` pipeline.
- `frontend/src/components/sync/WatchSyncModal.jsx` — the one-button flow
  (host trigger → WebUSB → folder) + plug-in auto-prompt in `Layout.jsx`.
- `backend/app/api/fit_upload.py` — `/fit/precheck`; upload records file
  size in `Import.extra` to power the precheck.
- `backend/app/api/device_sync.py` — user-authed push endpoints wrapping the
  per-user helpers shared with the garmin-sync container's sync-agent-token-
  guarded routes (see `app.services.sync_agent_auth`).

## Music

Music does **not** ride along with every sync: everything else this push moves
is kilobytes, while a music library is megabytes per track. It is opt-in, driven
from its own button with its own progress (`pushMusic` in `deviceSync.js`,
separate from `runFullSync`), and writes to `Music/` at the storage **root** —
a sibling of `GARMIN/`, not a child.

There is also a cable-free path (a Connect IQ app downloading over the watch's
own Wi-Fi), and a Navidrome integration feeding both.

**See [music.md](music.md)** for all of it: why Bluetooth cannot carry audio,
why uploads go through ffmpeg, and the two gotchas that make a successful push
look like it did nothing.
