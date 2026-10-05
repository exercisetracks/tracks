<!-- SPDX-FileCopyrightText: 2026 Hawk Fugagli -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Music on the watch

Two ways to get audio onto a Garmin watch, and one that is impossible.

## The impossible one, first

**Music cannot be pushed over Bluetooth.** Not "is hard" — cannot. Garmin's GFDI
file transfer stamps an enumerated file type on every upload, and the enum
(vendored at
`mobile/device-garmin/.../garmin/FileType.java`) holds ~120 FIT subtypes plus
exactly one non-FIT entry: `PRG(255, 17)`, a Connect IQ app. There is no
MUSIC/AUDIO/MEDIA type on any Garmin generation. The BLE protocol simply does
not address the music filesystem.

That single `PRG` entry is what the Connect IQ path below is built on.

## The two paths

|  | USB / MTP | Connect IQ app |
|---|---|---|
| Transport | Cable — browser, or a watch docked to the server | The watch's own Wi-Fi |
| Lands in | Native `Music/` library | The app's encrypted sandbox |
| Plays under | **My Music** | The Tracks Music app |
| Source | The Tracks library (uploads + any Subsonic server) | **Navidrome, directly** |
| Tracks server involved | Yes — normalises and serves the audio | **No** — only hands the phone the login |
| Needs | Chromium + cable | Navidrome on HTTPS with a real certificate |

They are **two separate libraries on the same watch**. A track pushed over USB
is not visible to the Connect IQ app and vice versa. The Tracks server only
tracks the USB one (`watch_uploaded_at`); the watch app keeps its own record of
what it holds and never reports back, because there is nothing for Tracks to
do with the answer.

Both cable routes exist: the browser pushes over WebUSB from the machine you
are browsing from, and `garmin-sync` pushes over libmtp when the watch is docked
to the server (`music_sync_cycle`). They share the reconciliation and differ
only in which endpoints they authenticate to — `/music/device-plan` for a
browser session, `/music/sync/*` for a paired agent token.

## Where music comes from

Either source feeds either path.

**Uploads** own their bytes. Everything is normalised on upload — see below.

**A Subsonic music server** (Navidrome, Gonic, Airsonic) is referenced, not
copied. A remote track is a row with a `source_ref` and no audio; the bytes are
fetched, normalised and cached the first time something asks
(`app.services.music_source.ensure_audio`). Only tracks actually going to a
watch are ever stored, and the cache is disposable.

All of the above is the **USB path**. The watch app does not use the Tracks
library at all — see "The watch app" below.

**Auto-rotation** reads what the server says you actually play (starred songs
first, then frequent and recent albums) and sets the carried set from it. It
never unloads a track you uploaded and ticked by hand: an explicit choice
outranks an inference from play counts.

**Smart playlists** are saved *queries*, not saved lists: `recent`, `frequent`,
`newest`, `starred`, `random`. Their membership is rebuilt from the server
whenever the carried set refreshes, so "recently played" keeps meaning recently
played rather than freezing on whatever was playing the day it was added.

Subsonic has no smart playlists of its own — it has ranked *album* lists plus a
starred set — so `recent` really means "songs from albums played recently".
Worth knowing when a half-listened album shows up.

Rotation is **demand-driven**: it runs when a device asks for its plan, not on a
timer. No scheduler container, and the answer is computed from listening history
as of moments ago rather than as of the last cron tick.

## Why ffmpeg is not optional

The watch fails **silently** on files it dislikes — no error, the track just
never appears. Two causes account for nearly all of it, and
`app.services.music_transcode` handles both:

1. **Embedded cover art.** Artwork rides as a second stream inside an mp3, and
   the scanner skips any file with more than one stream. Virtually every tagged
   library has art, so this is the common one. Stripping it needs no re-encode.
2. **Anything that is not mp3.** The other documented containers work for some
   people and not others.

Three paths, cheapest first: pass conforming bytes through **untouched**, remux
to drop art (lossless), or transcode. Only the last loses anything.

## Gotchas that look like bugs

- **Music Providers must be set to "My Music"** (watch: Settings → Music →
  Music Providers) for the USB path. Files are on the device and simply do not
  show otherwise. This is far and away the most common "the push did nothing".
- **500 files, device-wide**, sharing storage with maps. `/music/device-plan`
  returns `on_device_after` and `device_file_limit` so a push refuses to start
  rather than overrunning — an overrun does not error, the extra files just
  never appear.
- **The `.prg` vanishes after install.** Media apps are relocated into an
  encrypted store. That is success.
- **Connect IQ refuses self-signed certificates.** A CA-signed cert is a hard
  prerequisite for the Wi-Fi path; Caddy already does ACME.

## The watch app

Works like Garmin's own Spotify app: tick playlists, and the watch keeps them
current by itself — over its own Wi-Fi, whenever it is on the charger — with
no phone and no Tracks server in the loop. Everything it needs it gets from
Navidrome directly.

### Installed and configured from the phone, once

No Connect IQ Store, no Garmin account, no cable:

1. `watchapp/build.sh all` builds and signs one `.prg` per music-capable
   watch and bundles them into the Android app's assets; the phone picks the
   build by the product number the watch reports (`WatchAppBundle`).
2. The phone pushes it over BLE as `FILETYPE.PRG`
   (`GarminIntegration.installWatchApp`) — from the onboarding Music step, or
   later from the Music tab.
3. On first run the app asks the phone who it belongs to by requesting one
   reserved URL, `https://tracks.invalid/ciq/config`, and gets back the music
   server's URL, username and password (`GET /music/server/watch-config` is
   where the phone fetched them — the one place the password leaves Tracks).
   It asks again every time the app opens and before every sync, which is how
   a playlist selection made in the phone app, a changed password or a
   removed server reaches it without a reinstall.

### Revisions: when the phone's answer counts

Asking that often would be a problem if every answer were obeyed, because the
watch can also have an account of its own (see "The account menu" below): the
phone would put its account back every time the app opened. So the answer
carries a **revision** — a number that moves only when the phone's music
settings change: a server connected, changed (address, user or password) or
removed, or a selection sent. The watch applies an answer only when its
revision is newer than the last one it applied.

- The phone keeps the revision in its preferences, as the wall-clock time of
  the change (never less than one more than the last), so it keeps rising
  across a reinstall. A change is noticed by fingerprinting the login (salted
  SHA-256, per install), so one made in the web app counts too
  (`WatchConfigRevision`). A phone that never had a server stays at 0 and
  says nothing.
- The phone hands the password out only for ten minutes after the user did
  something on its music screen (`WatchAppConfig.ARMED_FOR_MS` — the request
  carries no proof of which watch app is asking). Outside that window it
  answers `{"revision", "server": true}` only, and the watch does not count
  that revision as applied: it takes the change the next time it asks inside
  a window. Every settings change re-arms the answer immediately.
- `{"revision", "server": false}` means the phone's server was removed. If
  that revision is newer, the watch **signs out and deletes its music**.
- Not polled in the background. A five-minute Connect IQ temporal event was
  built and dropped for battery: a Bluetooth round trip every five minutes,
  all day, to learn that nothing changed. Opening the app and syncing are
  when the answer matters.

Tracks' `HttpHandler` on the phone answers that one path from memory — no
socket, no name resolution; `.invalid` is reserved by RFC 2606 so the request
could not escape even if something tried — and proxies exactly one other
destination: the configured music server, same scheme, host and port. That is
what lets the watch list playlists over Bluetooth when it is not on Wi-Fi.
Every other URL gets an error reply.

### The account menu

Download menu › Account (or START on a watch that is not signed in) shows the
user and the server's host, and offers:

- **Sign in from phone** — take the phone's answer now, whatever its revision.
  If the phone is outside its ten-minute window, the watch says to open Music
  in the Tracks app and try again.
- **Sign in on watch** — server address, username and password on the system
  keyboard (`WatchUi.TextPicker`, which the fēnix 6X Pro has). The login is
  checked against Navidrome's `/auth/login` before anything is stored; a
  refused one leaves the old account as it was. Off Wi-Fi that check goes
  through the phone, which only relays to *its own* music server — so a
  different server cannot be reached that way by design, and the watch offers
  to check over its own Wi-Fi instead, in a sync that keeps the account only
  if the server accepts it. A typed account marks the phone's current revision
  as applied, so it lasts until the phone's settings next change.
- **Log out** — after a confirmation.

**Downloads belong to the account.** Logging out, or any change to a
different server or user (from the phone or on the watch), deletes the app's
downloaded songs and its playlist and selection state. A changed password is
the same account and keeps them. The song the player is on is the one
exception, as everywhere: the next sync removes it.

### Why Navidrome's own API, not just Subsonic

A Connect IQ web response is capped at roughly **16 KB** — measured on the
fēnix 6X Pro profile: 12 song entries pass, 14 fail with
`NETWORK_RESPONSE_TOO_LARGE`. Subsonic's `getPlaylist` and `getStarred2`
return the whole list in one body and cannot page, so any real playlist is
unreadable that way. Navidrome's native API pages everything (`_start`/`_end`),
so the watch reads lists from `/api/…` a few rows at a time — halving the page
size whenever a response does not fit, down to one row, and stepping over a
row that will not fit on its own. Streaming still goes through Subsonic
`stream.view` (mp3, 192 kb/s, transcoded server-side), using the Subsonic
token that Navidrome's login hands back beside the JWT — which is also why the
watch never needs to hash anything.

The price: the watch app is Navidrome-only. The USB path still takes any
Subsonic server.

### What it offers

Every server playlist — including Navidrome's smart playlists, which appear as
ordinary playlists — plus three of its own: **Liked songs** (`starred`, newest
first), **Recently played** (songs by last play, stopping at the first
never-played one, capped at 50), and **Picked songs** — single songs chosen
on the watch by browsing albums, or on the phone by search.

Choose on the watch (Music › Music Providers › Tracks Music) or on the phone
(Music tab › Playlists on the watch › Send). The phone's selection is an offer
delivered once; whichever screen was touched last wins. Ticking downloads at
the next sync; un-ticking deletes from the watch immediately, no network
needed. "Sync now" is the last of the choices, on the charger or off it,
with Account below it.

Audio is always Navidrome's mp3 transcode at 192 kb/s, never the source
file — a 40 MB FLAC lands as about 5 MB. `Navidrome.MAX_BITRATE` is the one
constant to change. Cover art is fetched per album at sync time and put up by
the app when a song starts, because the player only shows art it finds inside
the file and the transcode carries none.

### On the watch

Storage is flat — one small value per key — because a single stored value is
capped (8 KB on older firmware, 32 KB now) and a library is not small:
`sel` (chosen playlist ids), `pls` (every playlist offered), `pl:<id>` (that
playlist's song ids), `s:<songId>` → media-store ref and `r:<ref>` → song id.
Titles are not stored; the player reads ID3 tags from the file, which
Navidrome writes when it transcodes.

A sync: log in → list playlists → page each chosen list → compare with what
the media store holds → download what is missing, one at a time → retry
failures once (a song Navidrome had to transcode from scratch can time out the
first time; by the retry it is cached) → delete what is no longer wanted →
`notifySyncComplete`. Deletions go last: on the simulator, a download issued
straight after a batch of deletions dies inside the system's callback
dispatch, and freeing space first is a weak argument on a watch with gigabytes.

`isSyncNeeded()` answers yes whenever a playlist is ticked, which is what makes
the watch re-sync on every charge — the same behaviour people report from
Spotify — and a sync that finds nothing new costs a handful of small requests.

## Testing

See [music-testing.md](music-testing.md) for the end-to-end checklist.

## Implementation map

| Path | What |
|---|---|
| `backend/app/services/music_transcode.py` | The three-path normaliser |
| `backend/app/services/music_source.py` | Upload vs Navidrome, lazy fetch |
| `backend/app/services/subsonic.py` | Subsonic client + rotation picking |
| `backend/app/api/music/sync.py` | Watch reconciliation (desired vs actual) |
| `frontend/src/lib/mtp.js` | MTP over WebUSB, chunked data-out |
| `frontend/src/lib/deviceSync.js` | `pushMusic` — the USB push |
| `watchapp/` | The Connect IQ app (Monkey C); `Navidrome.mc` is the client |
| `mobile/.../WatchAppConfig.kt` | What the phone answers the watch with, and which host it will proxy |
| `mobile/.../http/HttpHandler.java` | The proxy itself, and Garmin's binary JSON (`GarminJson`) |
