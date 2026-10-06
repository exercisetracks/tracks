<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Tracks

**Your own private Garmin Connect / Strava — self-hosted, for you or your household.**

Tracks pulls your activities, sleep and health data straight off your Garmin
watch, analyses your training, builds periodized training plans, and shows it
all on fully self-hosted maps. It runs on your own hardware, and nothing it
holds ever goes to Garmin or anyone else.

It comes in three parts that work together or on their own:

- **The server**: the web app, the database, and the maps. It runs in Docker on
  a home server, NAS or any Linux box.
- **The Android app**: talks to your watch over Bluetooth in place of Garmin
  Connect, records runs, and keeps working for days with no server in reach.
- **Tracks Music**: a watch app that keeps playlists from your own Navidrome
  music server on the watch, downloading over the watch's Wi-Fi.

[![Release](https://img.shields.io/github/v/release/exercisetracks/tracks)](https://github.com/exercisetracks/tracks/releases/latest)
[![Docker pulls](https://img.shields.io/docker/pulls/exercisetracks/tracks)](https://hub.docker.com/r/exercisetracks/tracks)
[![License: AGPL v3](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)](LICENSE.md)

> ⚠️ **Built for a household, not as a public service.** Tracks supports
> several accounts, each with its own data sealed under its own key, but it is
> meant to run on your network behind your own HTTPS reverse proxy. Read
> [Security](#security) before exposing it to the internet.

---

## Contents

- [Features](#features)
- [Install the server](#install-the-server)
- [Install the Android app](#install-the-android-app)
- [Connect a Garmin watch](#connect-a-garmin-watch)
- [Updating](#updating)
- [Backups](#backups)
- [Configuration](#configuration)
- [Security](#security)
- [Building from source](#building-from-source)
- [License](#license)

---

## Features

### Activities & analysis
- **Automatic import** from the watch, over Bluetooth (phone) or USB (server),
  or by uploading FIT files in the browser.
- **Sport-aware views**: running, road/indoor cycling, MTB, swimming, rowing,
  paddling, hiking, nordic/alpine skiing, climbing, bouldering, strength,
  golf, triathlon, team sports, and more.
- **Training load**: fitness, fatigue and form (CTL / ATL / TSB), VO₂max trend,
  per-sport TSS, and a race-time predictor. Heart rate is checked sample by
  sample against pace, gradient and cadence before it is trusted. Activities
  with no heart rate or power still count, estimated from sport, duration,
  distance and climb.
- **Health**: sleep, HRV status, daily monitoring, meals and medication.

### Coaching & planning
- **Periodized training plans** for running, cycling, MTB, triathlon, skiing,
  hiking, rowing, climbing, swimming, strength and flexibility. They are
  driven by a fitness "fingerprint" taken from your own history, and they
  regenerate as you train.
- **Workouts pushed to the watch**, along with your training calendar.
- **Strength and stretching**: a body map of which muscles each session works,
  guided sessions, and flows.
- **Goals and race plans**, with a calendar you can subscribe to (`.ics`).
- **AI coaching** (optional) through Ollama, Anthropic or OpenAI. API keys are
  encrypted at rest.

### Self-hosted maps
- **Vector basemap** from your own [Protomaps](https://protomaps.com) archive,
  with no tile keys and no usage limits.
- **Hillshade, 3D terrain and contour lines.**
- **Trail-aware routing** with [BRouter](https://github.com/abrensch/brouter),
  plus long-distance trail overlays (AT, PCT, CDT, …).
- **Courses**: draw routes, organise them in folders, and send them to the
  watch with turn-by-turn directions.
- **Offline regions** for the web and the phone: draw an area and download it.

### The phone, offline
The Android app is a complete Tracks of its own. It parses the watch's files
on the phone, using ports of the server's own parsers and metrics, so the
dashboard, training load and plan all work with no server at all. When a
server is reachable, the two sync field by field, and edits made on either
side while apart are merged rather than overwritten.

---

## Install the server

You need [Docker](https://docs.docker.com/engine/install/) on Linux, a NAS,
or any machine that runs it. Then start Tracks with one command:

```bash
docker run -d --name tracks --restart unless-stopped \
  -p 4080:80 \
  -v tracks-data:/data \
  -v tracks-maps:/map-data \
  exercisetracks/tracks:latest
```

Or with Docker Compose, using [`compose.yaml`](deploy/compose.yaml) and,
optionally, [`.env.example`](deploy/.env.example) for settings:

1. Save `compose.yaml` in a folder of its own.
2. To change a setting (the port, or where data and maps are kept), save
   `.env.example` in the same folder **renamed to `.env`**, and edit the
   values in it. Every line is optional and shows its default; leave out the
   file entirely to run with the defaults.
3. Run `docker compose up -d` in that folder. After editing `.env` later,
   run it again to apply the change.

Open **http://localhost:4080** (or `http://<server-ip>:4080`) and create
your admin account. There's nothing to configure first. On its first start,
Tracks generates its own secrets and database, and keeps them in the
`tracks-data` volume.

> **Create the admin account before exposing the port.** Until one exists,
> Tracks has no credentials to check, so anyone who can reach it can claim it.

**Storage:**
- `tracks-data` holds your database and the original files from your watch.
  It stays small (megabytes per year).
- `tracks-maps` holds offline maps. **Allow at least 20 GB** if you turn maps
  on:
  - When you turn maps on (during setup, or later in Settings), Tracks
    downloads a global basemap (about 16 GB) and terrain overview (about
    3 GB). With maps off, nothing is downloaded.
  - Detailed regions add more as you download them.

To keep maps on a bigger disk, use a folder instead of the volume, e.g.
`-v /mnt/storage/tracks-maps:/map-data` (`TRACKS_MAPS` in `.env`).

### Putting it behind HTTPS

Tracks serves plain HTTP on one port and expects your reverse proxy to handle
TLS. Plain HTTP is fine inside your own network, and the Android app accepts
it there. Anything reachable from outside should be HTTPS. Some examples:

**Caddy**
```
tracks.example.com {
	reverse_proxy localhost:4080
}
```

**nginx**
```nginx
server {
    listen 443 ssl;
    server_name tracks.example.com;
    # ssl_certificate / ssl_certificate_key …
    client_max_body_size 200m;   # FIT and music uploads
    location / {
        proxy_pass http://127.0.0.1:4080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

With your own proxy in front, set `BEHIND_PROXY=true`. That lets
login rate limiting see real client addresses rather than your proxy's.

---

## Install the Android app

Download `tracks-<version>.apk` from the
[latest release](https://github.com/exercisetracks/tracks/releases/latest)
and open it on the phone. Android will ask you to allow installing from your
browser or file manager. The app needs Android 8.0 or newer.

- **Automatic updates:** add `https://github.com/exercisetracks/tracks` to
  [Obtainium](https://obtainium.imranr.dev), and it will offer each new
  release as it comes out.
- **Verify the download:** each release lists the APK's SHA-256, and every
  release is signed with the same key.
- **F-Droid and Google Play** are planned.
- **iPhone:** there's no iOS app yet.

On first launch, either sign in to your Tracks server or start without one.
A phone without a server keeps everything on the phone, encrypted. You can
connect it to a server later, and it will sync what it has.

---

## Connect a Garmin watch

**Over Bluetooth, with the phone:** pair the watch from the app's Settings.
The app then takes over what Garmin Connect did, covering all of these:
- Activities, sleep, HRV and monitoring come off the watch.
- Workouts, the training calendar and courses go onto it.
- Notifications, weather and GPS assistance data go to it.

The app also installs **Tracks Music** on the watch for you. Point it at your
[Navidrome](https://www.navidrome.org) server in the app's Music settings, pick
playlists, and the watch keeps them current over its own Wi-Fi whenever it's
charging. See [watchapp/README.md](watchapp/README.md) and
[docs/music.md](docs/music.md).

Development and hardware testing are on a **fenix 6X Pro**. The Bluetooth
layer is built on [Gadgetbridge](https://gadgetbridge.org)'s Garmin support,
so watches Gadgetbridge supports are likely to work, but only the fenix 6X Pro
has been verified. [docs/garmin-ble-protocol.md](docs/garmin-ble-protocol.md)
records what has been seen working on real hardware.

**Over USB, on the server:** plug the watch into the machine running Tracks,
and add these two options to your `docker run` (or uncomment them in
`compose.yaml`):

```bash
  --device-cgroup-rule='c 189:* rwm' -v /dev/bus/usb:/dev/bus/usb \
```

Tracks starts its USB bridge when it sees the bus, and pulls new files
whenever the watch is connected. It gets the USB bus's device nodes only, not
a privileged container. It also seals every file against your account's key
before handing it to the server.

---

## Updating

With Docker Compose:

```bash
docker compose pull && docker compose up -d
```

With `docker run`, pull the new image, remove the old container, and run the
same command you started it with. Your data lives in the volumes, not in
the container.

```bash
docker pull exercisetracks/tracks:latest
docker rm -f tracks
docker run -d --name tracks …   # as above
```

Database upgrades run automatically when the new version starts. **Take a
backup first** (below). Downgrading is not supported, and Tracks will refuse
to start an older version on a database a newer one has upgraded.

`:latest` follows every release, including a 2.0, which may need you to read
its release notes first. To stay on 1.x, use the `:1` tag instead, or pin an
exact release
(`exercisetracks/tracks:1.1.0`) to update only when you change it. Tools like
[Watchtower](https://containrrr.dev/watchtower/) or
[Diun](https://crazymax.dev/diun/) can update or notify for you.

To update the Android app, install the newer APK over the old one, or let
Obtainium do it. Your data stays.

---

## Backups

One command writes everything Tracks needs to come back into a single file.
That covers the database, the original watch files and the generated
secrets.

```bash
docker exec tracks tracks-backup > tracks-$(date +%F).tar.gz
```

It runs safely while Tracks is in use. Map data isn't included, since it
downloads again. **Keep the backup file private.** It contains the keys that
protect the database, so it's as sensitive as the install itself.

To restore, start a fresh install (same command, new volumes) and feed it
the file:

```bash
docker exec -i tracks tracks-restore < tracks-2026-10-01.tar.gz
```

Everyone signs in again afterwards. If the install already has accounts,
`tracks-restore` refuses unless you add `--replace`. On top of all this,
health data is sealed under a key derived from each user's password. That's
why the **recovery phrase** shown at account setup matters: keep it
somewhere safe.

---

## Configuration

Nothing needs setting. With Docker Compose, settings go in `.env`
([`.env.example`](deploy/.env.example) lists them): the port
(`TRACKS_PORT`), where data and maps are kept
(`TRACKS_DATA`, `TRACKS_MAPS`) and the ones below. With `docker run`, pass
them as `-e NAME=value`.

| Variable | Description |
|---|---|
| `HOST_UID` / `HOST_GID` | Owner of the files Tracks writes, if you bind-mount folders (default `1000`). |
| `BEHIND_PROXY` | `true` when your own reverse proxy sits in front of Tracks (default `false`). |
| `PMTILES_SOURCE_URL` / `DEM_SOURCE_URL` | Where the basemap and terrain are downloaded from, to use a mirror. |
| `TRUSTED_PROXY_HOPS` | For more than one proxy in front: how many, counting Tracks' own web server. Overrides `BEHIND_PROXY`. |
| `UVICORN_WORKERS` / `CELERY_CONCURRENCY` | API worker processes and background task workers (default `2` each). Raise on bigger hardware. |
| `BROUTER_HEAP` | Memory for the routing engine (default `256M`). |
| `TRACKS_USB_SYNC=off` | Don't start the USB bridge even when `/dev/bus/usb` is passed in. |
| `JWT_SECRET`, `ENCRYPTION_KEY`, `SESSION_CACHE_KEY`, `POSTGRES_PASSWORD` | Generated on first start into `/data/secrets.env`. Set them yourself only if you manage secrets elsewhere; a value you pass always wins. |

`docker logs tracks` shows the output of every service. If you want to look
around inside, `docker exec tracks supervisorctl -c /etc/tracks/supervisord.conf status`
lists them.

---

## Security

- **Finish setup before exposing the port.** Until an admin account exists,
  Tracks runs in *open mode*: it answers every request, because that is what
  lets you create the account in the first place.
- **Health data is sealed per user**, under a key derived from that user's
  password; the server holds only ciphertext while they're signed out. Files
  from the watch are sealed against the account's public key before they
  leave the phone or the USB container.
- Passwords are hashed with **bcrypt**. AI API keys and the music-server
  password are encrypted at rest with **Fernet**.
- Login and device unlock share brute-force rate limiting. Refresh tokens
  rotate on every use, and replaying one revokes the whole chain.
- The web container sets **CSP, `X-Content-Type-Options`,
  `X-Frame-Options`, `Referrer-Policy` and `Permissions-Policy`** on every
  response.
- **Only one port is published.** The database and session cache listen
  inside the container only. Every service except PostgreSQL runs as an
  unprivileged user, and nothing mounts the Docker socket. The opt-in USB
  bridge gets `/dev/bus/usb` only, not a privileged container.
- **Secrets are generated per install** on first start, so no two installs
  share one, and none uses a value published here.
- **Run it behind HTTPS.** Tracks' own web server speaks plain HTTP and
  expects your proxy to terminate TLS.

To report a vulnerability, please use
[GitHub's private reporting](https://github.com/exercisetracks/tracks/security/advisories/new)
rather than a public issue.

---

## Building from source

```bash
git clone https://github.com/exercisetracks/tracks.git
cd tracks
./setup.sh
docker compose up -d --build
```

The root `docker-compose.yml` runs in **developer mode**: source is
bind-mounted live, Vite serves the frontend with hot reload, and debug ports
are published on `127.0.0.1` only. To run your own build in production,
add the hardening overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

**[AGENTS.md](AGENTS.md) is the guide to working on Tracks**: how the five
codebases fit together, running the test suites, conventions, and releasing.

Development runs each service in its own container. The released
`exercisetracks/tracks` image ([deploy/Dockerfile](deploy/Dockerfile)) is
assembled from those same images, with a supervisor running them side by side:

| Service | Role |
|---|---|
| `web` (Caddy) | The one entry point: serves the app, routes `/api/*` and map tiles. |
| `backend` | FastAPI: REST API, FIT parsing, plans, map data pipeline. |
| `worker` | Celery: background imports. |
| `db` | PostgreSQL 16. |
| `redis` | Session key cache (in memory only, by design). |
| `go-pmtiles` | Serves map tiles from PMTiles archives. |
| `brouter` | Trail-aware routing (BRouter, the same version the phone uses). |
| `garmin-sync` | USB bridge to a Garmin watch (opt-in). |

```
backend/        FastAPI app
frontend/       React + Vite web app (MapLibre)
mobile/         Android app; core/ is Kotlin Multiplatform, ready for iOS
watchapp/       Tracks Music, the Connect IQ watch app
garmin-sync/    USB/MTP bridge
caddy/          Entry point config, and the tracks-web image
deploy/         The all-in-one image and compose.yaml that installs run
spec/           Fixtures shared by the backend and frontend test suites
docs/           Design notes and reverse-engineered protocol references
```

---

## License

Copyright © 2026 Hawk Fugagli

Tracks is free software under the
[GNU Affero General Public License v3.0 or later](LICENSE.md). You may use,
self-host, study, modify and redistribute it, commercially or not, as long as
derivative works carry the same license. Because it's the *Affero* GPL, that
extends across the network: if you run a modified Tracks that other people
use, you must offer them your modified source.

The Android app's Garmin support builds on
[Gadgetbridge](https://gadgetbridge.org). See [NOTICE](NOTICE) for
third-party attribution.
