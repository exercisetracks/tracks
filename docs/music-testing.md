<!-- SPDX-FileCopyrightText: 2026 Hawk Fugagli -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Testing the watch music app

End to end, in the order that fails fastest. Each stage is independently
checkable, so when something breaks you know which layer owns it.

## Stage 0 — Prerequisites (do these first, they gate everything)

**0a. Device definition.** The signing key is already generated and needs
nothing further; the device definition is the only outstanding prerequisite.
The SDK ships none, and this is the one step needing a Garmin account (free).

```bash
~/.Garmin/ConnectIQ/bin/sdkmanager
```

If installing it fresh, extract the **whole** zip into `~/.Garmin/ConnectIQ/` —
both `bin/` and `share/`. The binary alone starts, accepts the licence, and
then dies on a modal "Failed to load image" dialog looking for
`share/sdkmanager/connectiq-icon.png`, with nothing clickable behind it.

```bash
curl -L -o /tmp/sdkmgr.zip https://developer.garmin.com/downloads/connect-iq/sdk-manager/connectiq-sdk-manager-linux.zip
unzip -o /tmp/sdkmgr.zip -d ~/.Garmin/ConnectIQ/
```

Sign in → **Devices** tab → tick **fēnix 6X Pro** → download. Verify:

```bash
ls ~/.Garmin/ConnectIQ/Devices/fenix6xpro
```

**On Arch this will not start as-is.** The SDK manager is built against
WebKit GTK-4.0 (it embeds a browser for Garmin's SSO login) and Arch ships only
4.1 / libsoup3. Three ways round it, cheapest first:

```bash
# 1. Legacy libs from the AUR — one-time, then the GUI runs natively
yay -S webkit2gtk libsoup          # 4.0 and 2.4, both AUR-only now

# 2. Run it in a distro that still ships them, sharing the same home
distrobox create -i debian:12 -n ciq && distrobox enter ciq
sudo apt install -y libwebkit2gtk-4.0-37 libsoup2.4-1
~/.Garmin/ConnectIQ/bin/sdkmanager
```

3. Or run the SDK manager on any other machine, sign in there, and copy the
resulting `~/.Garmin/ConnectIQ/Devices/fenix6xpro` directory across. The device
files are plain data — nothing is machine-specific about them.

There is no headless path: the manager has no CLI mode, and the download
endpoint (`monkeynet.garmin.com`) is not publicly resolvable.

**0b. Navidrome on HTTPS with a real certificate.** Connect IQ refuses
self-signed certificates outright — this is not negotiable and there is no dev
override on hardware. It is the *music server* the watch talks to, not Tracks:
Navidrome must be reachable from the watch's Wi-Fi at an `https://` name with
a CA-signed cert (Let's Encrypt is fine). Check from a machine on the same
network:

```bash
curl -sS 'https://your-navidrome/rest/ping.view?u=USER&p=PASS&v=1.16.1&c=x&f=json'
```

Anything other than `"status":"ok"` here means stop — every later stage will
fail on it. The Tracks server needs no certificate for this path at all.

**0c. The watch is a fēnix 6X *Pro/Sapphire/Solar*.** A non-music fēnix 6 has no
audio hardware and no Music Providers menu at all.

## Stage 1 — Build and sign

```bash
cd watchapp
./build.sh fenix6xpro
```

Expect `BUILD SUCCESSFUL`, then `Bundled 1 builds (3 product numbers) ... -> ../mobile/app/src/main/assets/watchapp`.
`./build.sh all` does the same for every watch; either works for this test.

If it builds but does not bundle, you ran it without a device id — that build is
deliberately not shipped, because an untargeted `.prg` fails to install in a way
that looks like a phone bug.

## Stage 2 — Simulator (catches most logic bugs without touching the watch)

The simulator has no phone to pair with, so a dev build carries the music
server baked in, and kicks off one sync on launch (`monkeydo` starts an audio
provider straight into playback, and the sync menu is GUI-only):

```bash
cd watchapp
cat > .dev.env <<EOF          # gitignored
TRACKS_DEV_URL=https://your-navidrome
TRACKS_DEV_USER=you
TRACKS_DEV_PASS=secret
EOF
./build.sh fenix6xpro --dev
~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-9.2.0/bin/connectiq &
~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-9.2.0/bin/monkeydo build/TracksMusic-dev.prg fenix6xpro
```

`monkeydo` prints the app's trace. A healthy run reads:

```
sync: 4 playlists offered, 2 chosen
sync: ~starred -> 5 songs
sync: ~recent -> 10 songs
sync: 11 held, 12 to remove, 2 to download
sync: done, 2/2 downloaded, 0 failed, 0 playlists skipped
```

then the player shows the first song. Useful knobs:

- `TRACKS_DEV_PLAYLISTS=~starred,<playlist id>` — what the dev build ticks
  (default: the two built-ins). Each rebuild syncs once on its next launch.
- `TRACKS_DEV_PLAY=<playlist id>` — play that instead of syncing, to check the
  playlist → player path.
- Storage lives in `/tmp/com.garmin.connectiq/GARMIN/APPS/DATA/TRACKSMUSIC.*`
  and the media store under `…/DATA/MEDIA/TRACKSMUSIC/`; delete them for a
  clean slate. After an app crash the simulator will not launch the next run —
  restart it (`pkill -x simulator`).

The two menu screens (download picker, playback picker) need the simulator's
GUI: **Simulation** menu, on a wrist-sized window. The sync engine and the
playlist → iterator → player path were verified this way against a live
Navidrome; the menus compile and follow the same code, and had their first
outing on hardware.

## Stage 3 — Install on the watch

1. Rebuild and reinstall the phone app so it carries the new `.prg`:
   ```bash
   cd mobile && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. On the phone: **Music** tab (or the Music step during onboarding).
3. Tap **Send music app to watch**.

**Expected:** "Sent." within a few seconds.

**The `.prg` will not appear in the watch's file list.** Media apps are
relocated into an encrypted store on install — that is success, not failure.
Confirm the real way: on the watch, **Music → Music Providers**. "Tracks Music"
should be listed.

## Stage 4 — Pairing (the interesting bit)

Connect the music server in the phone's Settings › Music first (or in the web app).
Then on the watch: **Music Providers → Tracks Music**.

**Expected:** "Asking phone…", "Signing in…", "Loading…", then a list:
*Sync now*, *Pick songs*, and toggles for *Liked songs*, *Recently played*,
*Picked songs*, and every playlist on the server.

The first step is the phone answering `https://tracks.invalid/ciq/config` over
Bluetooth with the server, username and password. Nothing leaves the phone —
`.invalid` is a reserved TLD that cannot resolve. The next two are the watch
logging in to Navidrome and listing playlists *through the phone's proxy*,
which only forwards to that one host. `adb logcat -s HttpHandler WatchHttpProxy`
shows both.

| What you see | What it means |
|---|---|
| "Open Tracks on your phone and set up a music server" | Phone answered, but no server is connected yet |
| "Could not reach the Tracks app" | Bluetooth link down, or Tracks not running on the phone |
| "The music server rejected the login" | Wrong username/password on the server settings |
| "Could not reach the music server. Is your phone connected?" | Proxy failed — phone offline, or the server is not reachable from the phone |
| The playlist list | Paired and signed in |

If the watch already knows a list from an earlier visit, it shows that when the
phone is away rather than an error.

## Stage 5 — Sync over Wi-Fi

1. Tick a small playlist or two, then **Sync now** (or back out — the system
   offers to sync).
2. The watch brings up Wi-Fi, signs in to Navidrome itself, and downloads.

**Expected:** a progress percentage, then "Music up to date" (or a count of
what could not be fetched). Nothing on the Tracks server changes — the watch
keeps its own record. The one thing worth checking server-side is Navidrome's
log, where each song shows as a `stream` with `format=mp3&maxBitRate=192`.

**The first sync of a freshly-transcoded song may report a failure and then
succeed on its built-in retry** — Navidrome has to transcode FLAC on the fly,
and the watch does not wait long for the first byte. Once cached server-side
it is instant.

## Stage 6 — Playback and playlists

Watch: **Music → Music Providers → Tracks Music** (playback side). You should
get *All music* with a song count, then each playlist that has songs on the
watch. Pick one and it plays that playlist in its own order.

Titles and artists come from the ID3 tags Navidrome writes when it transcodes,
not from anything Tracks sends — so if a track plays as "Unknown", the tagging
on the server is what to look at.

## Stage 7 — The removal path

1. Untick a playlist on the watch. Its songs are deleted **immediately** —
   no sync, no network — except the one playing, which goes next sync.
2. Or on the phone, untick and Send: that takes effect when the watch next
   opens its download screen, and the deletion at the following sync.

## Stage 7a — Single songs and art

- *Pick songs* → an album → tick a song. Back out, *Sync now*: it arrives under
  *Picked songs*. On the phone, *Find a song* does the same by search.
- When a song starts, its album's cover should appear in the player. If it
  stays on the default art, the watch could not fetch
  `getCoverArt.view` over Wi-Fi — the one part of this the simulator could
  not verify against a real server.
- The player's more menu should offer library, volume, play, next, previous,
  repeat, shuffle and providers, laid out as for My Music; DOWN skips.

## Stage 7b — Automatic sync

Put the watch on its charger with Wi-Fi in range and wait. Garmin's own
providers re-sync on every charge; this app says yes to the same question
(`isSyncNeeded`) whenever a playlist is ticked. Star a song in Navidrome, then
check it arrived under *Liked songs* without touching the watch.

## Stage 8 — The cable path (optional, needs the dock)

If the watch is plugged into the machine running the server, `garmin-sync`
pushes the same library into the watch's **native** `Music/` folder. Check it
lands under the watch's own "My Music", separately from the Connect IQ app:

```bash
docker compose logs garmin-sync --tail 40 | grep -i music
```

`watch_uploaded_at` is set on the tracks it pushed; the watch app's library is
untouched — the two transports never satisfy each other.

## Stage 9 — Navidrome specifics

- **On the watch,** "Recently played" and "Liked songs" are read live at every
  sync from Navidrome's own API (`/api/song?_sort=play_date…`,
  `/api/song?starred=true…`), paged a few rows at a time under the watch's
  ~16 KB response ceiling. Playlists over a few songs are only readable this
  way, which is why the watch app is Navidrome-only.
- **Smart playlists refresh (USB path):** play something new in Navidrome, wait
  past the 6-hour staleness window (or clear `music_rotate_last_run`), sync, and
  the carried set should change.
- **Lazy fetch:** a freshly imported Navidrome track has `blob_id = NULL` until
  something asks for it. Watch it materialise:
  ```bash
  docker compose exec backend python -c "
  from app.database import SessionLocal; from app.models.music import MusicTrack
  db = SessionLocal()
  for t in db.query(MusicTrack).filter_by(source='subsonic').all():
      print(t.title, 'cached' if t.blob_id else 'reference only')
  "
  ```

## When it goes wrong

| Symptom | Most likely cause |
|---|---|
| App missing from Music Providers | Untargeted build, or the transfer failed — check `adb logcat -s TracksWatch` |
| "Could not reach the music server" on sync | No CA-signed cert on Navidrome (stage 0b), or the watch's Wi-Fi cannot route to it |
| "N playlists too big for the watch" | A single row would not fit the ~16 KB response ceiling even alone — almost certainly a song with an enormous embedded lyrics/comment tag |
| "The music server rejected the login" | Password changed on the server — update it in Tracks; the watch picks it up the next time the app opens or syncs (or at once: Account › Sign in from phone) |
| Sync completes, no music plays | Nothing was ticked — open the download screen and choose playlists |
| Music plays but is missing from "My Music" | Working as designed — this app's audio is sandboxed, see [music.md](music.md) |

## What has and has not been verified

Verified in the simulator against a live Navidrome (stage 2): login, paged
listing of server playlists and both built-ins, the ~16 KB response ceiling and
the page-halving that works under it, downloads landing in the media store with
their ID3 tags, re-sync doing nothing when nothing changed, selection changes
removing and adding songs, the download retry, and playlist-scoped playback.
Also: the phone-side proxy's codec and allow-list under unit test.

**Not verified: the two on-watch menu screens, and anything on real hardware.**
Stages 3–7b are the first time this code meets a fēnix.
