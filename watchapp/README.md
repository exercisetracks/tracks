<!-- SPDX-FileCopyrightText: 2026 Hawk Fugagli -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Tracks Music — Connect IQ audio content provider

The cable-free path for getting music onto a Garmin watch. It works like
Garmin's own Spotify app: tick playlists, and the watch keeps them current by
itself — downloading from **your Navidrome** over **its own Wi-Fi**, whenever it
is on the charger. No computer, no USB, no phone in the loop after setup, and no
Garmin service at any point. Tracks' only part is telling the watch where the
music server is — or the watch can be told on the wrist.

## Why this exists

Garmin's BLE file transfer stamps an enumerated file type on every upload, and
there is no audio type — only FIT types and `PRG(255, 17)`, a Connect IQ app.
So music can never be pushed to the native player over Bluetooth on any Garmin
generation. An audio content provider app is the only way to put audio on a
watch without a cable, because it downloads over Wi-Fi by itself.

## What makes this one unusual

Nothing here touches Garmin's infrastructure:

| Normally | Here |
|---|---|
| Publish to the Connect IQ Store | Side-loaded over BLE by the Tracks phone app (`FILETYPE.PRG`) |
| Configure in Garmin Connect | Watch asks the phone over Connect IQ's HTTP proxy |
| Music from a streaming service's CDN | Your own Navidrome, directly |

Pairing is the neat part. Every time the app opens, and before every sync, it
requests one reserved URL, `https://tracks.invalid/ciq/config`. `.invalid` is
reserved by RFC 2606 and can never resolve, so the request cannot escape; the
Tracks Android app answers it locally with the server address, username and
password. Nothing is typed, nothing is published, and no request leaves the
phone. The same phone proxies exactly one other destination — that music
server — so the watch can list playlists over Bluetooth when it is not on
Wi-Fi.

The answer carries a **revision** that moves only when the phone's music
settings change, and the watch applies it only when it is newer than the last
it applied — so a change on the phone arrives the next time the app opens or
syncs, and an account typed on the watch is not overwritten on every ask. A
newer answer saying the phone has no server signs the watch out. There is no
background poll: a five-minute temporal event was built and taken out for
battery. The whole protocol is in [docs/music.md](../docs/music.md).

## Building

```bash
./build.sh                # compiles, type-checks and signs (no device needed)
./build.sh all            # every music-capable watch, bundled into the Android app
./build.sh fenix6xpro     # one watch, bundled alone — quicker while iterating
```

The script expects the SDK at `~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-9.2.0`
(override with `CIQ_SDK`) and a key at `~/.Garmin/ConnectIQ/developer_key.der`
(override with `CIQ_KEY`). To make a key:

```bash
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER \
        -in developer_key.pem -out developer_key.der -nocrypt
```

**Device definitions are the one thing that needs a Garmin account.** The SDK
ships none; the Connect IQ SDK Manager downloads them per-device after a (free)
sign-in, into `~/.Garmin/ConnectIQ/Devices/`. Until a device is there,
`./build.sh` with no argument still compiles, type-checks and signs — enough for
CI and for catching every API error — it just is not built for a watch, so it is
deliberately *not* bundled into the phone app.

### Every watch

A Connect IQ app is compiled per model, so the phone carries one build for each
watch that can run a music app — 93 device definitions covering 152 Garmin
product numbers as of SDK 9.2.0. Which watches those are is not kept by hand:
[devices.py](devices.py) reads every downloaded definition and keeps the ones
that accept an `audioContentProvider`, and `./devices.py manifest` writes them
into `manifest.xml`. Download *all* devices in the SDK Manager before running
it, then `./build.sh all` (about five minutes, in parallel).

The builds go into the app as one xz stream plus an index
(`mobile/app/src/main/assets/watchapp/`): 17 MB of near-identical builds is 6.8
MB compressed one by one and about 230 KB together. The phone picks a build by
the product number the watch sends in its handshake, checks its sha256, and
pushes it over Bluetooth (`WatchManager.installMusicApp` →
`GarminIntegration.installWatchApp`), from onboarding or Settings › Music. A watch
with no build is refused by name rather than sent the wrong one.

**Only the fēnix 6X Pro has run a build on hardware.** The others compile
against their own definitions, which catches API and resource errors but not
memory, layout, or firmware quirks. Device definitions also do not say whether
a watch has Wi-Fi, and audio only downloads over Wi-Fi (see below), so on a
music watch without it the app installs and browses but cannot fetch songs.

**The `.prg` will disappear from the watch's visible filesystem after install.**
That is success, not failure: media apps are relocated into the device's
encrypted store.

### Simulator

The simulator has no phone to pair with, so `--dev` bakes a music server in
(from `.dev.env`, gitignored) and syncs once on launch:

```bash
printf 'TRACKS_DEV_URL=https://…\nTRACKS_DEV_USER=…\nTRACKS_DEV_PASS=…\n' > .dev.env
./build.sh fenix6xpro --dev
~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-9.2.0/bin/connectiq &
~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-9.2.0/bin/monkeydo build/TracksMusic-dev.prg fenix6xpro
```

`TRACKS_DEV_PLAYLISTS` picks what to tick, `TRACKS_DEV_PLAY` plays a playlist
instead, `TRACKS_DEV_PROBE` compiles in a probe file, and
`TRACKS_DEV_CONFIG_URL` points the phone's config request at a URL of your own
(a JSON file served from `python3 -m http.server` works) so revisions and log
out can be driven without a phone. A dev build is never
bundled into the phone app. See [docs/music-testing.md](../docs/music-testing.md).

## Layout

| File | Role |
|---|---|
| `TracksMusicApp.mc` | `AudioContentProviderApp` entry point |
| `TracksConfig.mc` | Which server, as whom; dev-build stubs |
| `TracksPairing.mc` | Asks the phone for the server, user and password; applies an answer only if its revision is newer; switching account or signing out |
| `AccountMenu.mc` | Account menu: who, sign in from phone, sign in on watch (keyboard + login check), log out |
| `Navidrome.mc` | Login, paged lists, stream URLs; `NavidromeList` walks a list under the response ceiling |
| `TracksSyncDelegate.mc` | The sync — login, list, page, reconcile, download, retry, delete |
| `TracksLibrary.mc` | Flat storage: chosen playlists, their songs, song ↔ media-store ids |
| `TracksContentIterator.mc` | Feeds the player its songs, in order |
| `TracksContentDelegate.mc` | Playback callbacks (deliberately empty) |
| `ConfigureSyncView.mc` | Download screen: Pick songs, playlist toggles, Sync now, Account |
| `PickSongsView.mc` | Albums (recent, then all, a screenful at a time) → songs as toggles |
| `PlaybackView.mc` | Playback screen: All music + each downloaded playlist |
| `TracksScreen.mc` | The one status layout, wrapped and shrunk to fit the circle |

## What it does

- **Playlists.** Tick to download at the next sync; un-tick to delete from
  the watch *now*, no network needed. "Liked songs" and "Recently played"
  are built from the server's starred set and play history.
- **Single songs.** "Pick songs" browses albums — recently played first,
  then all of them a screenful at a time — and ticks individual songs onto a
  "Picked songs" list. The phone app can add to that list by search.
- **Account** (last on the download screen) shows who the watch is signed in
  as, and can sign in from the phone, sign in on the watch with the system
  keyboard (checked against the server before it replaces anything), or log
  out. Logging out or changing to a different account deletes the downloaded
  music — it belongs to the account.
- **Sync now** is the last choice above Account, and the system
  syncs by itself whenever the watch is charging on Wi-Fi. Syncing off the
  charger works; it is just the battery's problem.
- **Cover art** is fetched per album at sync time (`getCoverArt`, 60 px — small enough to fit one stored value)
  and put up by the app when a song starts. The player only shows art it
  finds *inside* an audio file, and Navidrome's transcode has none in it.
- **The player** gets the native control set — library, volume, play,
  next, previous, repeat, shuffle, providers — so its ring of icons lays out
  as it does for My Music. Shuffle and repeat are the app's to implement
  and are; the current song is never deleted out from under the player.

## What it talks to

Navidrome, and the phone for `tracks.invalid/ciq/config`.

| Call | Purpose |
|---|---|
| `POST /auth/login` | Once per session: JWT for `/api`, plus the Subsonic token+salt for streaming |
| `GET /api/playlist` | Every playlist, paged |
| `GET /api/playlist/{id}/tracks` | A playlist's songs, paged |
| `GET /api/song?starred=true&_sort=starred_at` | "Liked songs" |
| `GET /api/song?_sort=play_date&_order=DESC` | "Recently played", stopping at the first never-played song |
| `GET /api/album`, `GET /api/song?album_id=` | Albums and their songs, for picking |
| `GET /rest/stream.view?format=mp3&maxBitRate=192` | The audio, transcoded server-side — never the original FLAC |
| `GET /rest/getCoverArt.view?id=al-…&size=60` | Cover art, one per album |

**Why the native API and not just Subsonic:** a Connect IQ web response is
capped at about 16 KB (measured: 12 song entries pass, 14 fail). Subsonic's
`getPlaylist`/`getStarred2` cannot page, so any real playlist is unreadable
through them. Navidrome's `_start`/`_end` paging is what makes lists possible
at all; the walker halves its page size on every "too large" down to one row.
The Tracks server has no music endpoint for the watch; `GET
/music/server/watch-config` hands the *phone* the credentials to forward.

## Requirements and limits

- **Navidrome on HTTPS with a CA-signed certificate.** Connect IQ refuses
  self-signed certificates outright — this is the one hard infrastructure
  prerequisite, and it applies to the music server, not to Tracks.
- **Navidrome only.** Its native API is what makes lists fit; see above.
- **~16 KB per web response**, **512 KB** for code *and* data, **32 KB per
  stored value** (8 KB on old firmware) on a fēnix 6X Pro. Hence paged lists,
  sequential downloads, and flat storage.
- **Wi-Fi only** for the audio itself. Connect IQ does not download media over
  the phone link; pairing and playlist browsing do use it.
- Music downloaded here lands in the app's **encrypted sandbox**, not the
  native `Music/` library. It plays through this app's player, and does **not**
  appear under "My Music". A watch synced over USB *and* via this app has two
  separate libraries.
- **Deletions happen after downloads.** On the simulator a download issued
  straight after a batch of `deleteCachedItem` calls dies inside the system's
  own callback dispatch ("Error in onSong()", no frames). Downloads-then-
  deletions has never failed.
- **The playing song is never deleted.** `onSong(START)` records it; a sync
  or an un-tick leaves it for next time. Deleting it crashes the player when
  it comes back ("System Error", no frames) on the simulator.
- **Audio downloads only work inside a sync.** `HTTP_RESPONSE_CONTENT_TYPE_AUDIO`
  from any other mode answers -1002 without opening a connection.
- **Album art in a file is not shown** by the simulator's player, even though
  `Media.AlbumArt` describes it; app-supplied art via `setAlbumArt` is. The
  simulator's *image* fetcher also cannot reach some hosts its JSON and audio
  fetchers can, so art from your own server is unverified until hardware.
- **A failing `makeImageRequest` can call back synchronously** on a fēnix 6X
  Pro (firmware 28.02): the callback runs *inside* the request call. Issuing
  the next cover straight from `onArt` therefore recursed once per album and
  crashed the sync with "Stack Overflow Error" after the downloads had landed
  (CIQ_LOG.YML, 2026-09-28) — the simulator's callbacks always arrive later, so
  it never showed. The next cover is now issued from a timer, and art stops
  after three failures in a row. Do not chain requests directly from a
  callback whose failure path might be synchronous.

## Notes from getting this to compile

Written against CIQ **9.2.0**, and a few things the obvious reading gets wrong:

- **`has` is a reserved operator**, so a method cannot be called `has`.
- **`Media.notifySyncProgress`/`notifySyncComplete` are deprecated.** The live
  ones are on `Communications`. Garmin's own `monkeymusic` sample still uses the
  old pair.
- **The app does not own the content iterator.** `AudioContentProviderApp`
  supplies a `ContentDelegate` (via `getContentDelegate(args)`), and *that*
  returns the `ContentIterator`. There is no `getContentIterator` on the app.
- **`getInitialView` is not the entry point** for a provider — it is
  `getSyncConfigurationView()` and `getPlaybackConfigurationView()`.
- **`ContentIterator` returns `Media.Content`, not `Media.ContentRef`.**
  `Media.getCachedContentObj(ref)` converts between them.
- **`PlaybackProfile` has no `attributes` field**, and shuffle/repeat are
  `PLAYBACK_CONTROL_*` entries in `playbackControls` like any other button.
- **A media download's callback `data` is typed as a `PersistedContent` union**,
  which is wrong — that module is for courses and waypoints. At runtime it is a
  `Media.ContentRef`. `instanceof` against it is "unreachable" to the type
  checker (and so removable by the optimiser), which is why the code uses the
  runtime `has :getId` check instead.
- **`Media.getContentRefIter()` returns null on an empty store**, though it is
  typed as never null. Calling `next()` on it crashes; the cast in
  `TracksLibrary.contentRefs` is what lets the null check survive the checker.
- **`Lang.format` uses `$1$` placeholders**, not `%1$d`. The latter renders
  literally.
- **`:release` is stripped from every build made without `-r`.** Stubs meant
  for "every non-dev build" need a custom annotation (`:nodev`).
- **`new Lang.Method(Module, :func)` works for module functions**; it was a
  class + static method that did not.

App id: `821c220f1cb24c338d2c68b3dafb1d3f`
