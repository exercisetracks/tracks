# Working on Tracks

Orientation for someone — or something — about to change code here. Read the
[README](README.md) first for what Tracks *is*; this file is about how to work
on it.

---

## The five things in this repo

Tracks is one product across five codebases that talk to each other. Knowing
which one you are in matters more than anything else here, because they have
different languages, different test runners, and different constraints.

| Directory | What it is | Language |
|---|---|---|
| `backend/` | FastAPI + Postgres. The source of truth for everything. | Python |
| `frontend/` | React + Vite SPA, MapLibre for maps. | JS/JSX |
| `mobile/` | Native Android client, Kotlin Multiplatform core. | Kotlin/Java |
| `watchapp/` | "Tracks Music" — a Connect IQ audio provider for the watch. | Monkey C |
| `garmin-sync/` | USB/MTP bridge that pulls files off a docked watch. | Python |

Plus `caddy/` (the single entry point everything is routed through),
`brouter/` and `go-pmtiles/` (map service images), and `spec/` (golden fixtures
shared by the backend and frontend test suites).

Three of these are clients of the backend, and two of them — the phone and the
watch — are clients that go offline for days. That shapes most of the design
decisions you will run into: field-level merge instead of last-write-wins,
outboxes instead of direct writes, sealed blobs instead of trusted uploads.

---

## Running it

```bash
cp .env.example .env    # first time only — then fill in the secrets
docker compose up -d
```

`docker-compose.yml` is the **development** stack, and only that: backend and
frontend source are bind-mounted live, Vite runs with HMR, and debug ports are
published on `127.0.0.1` only. The app is at `http://localhost:4080`. Installs
never run it — they run the all-in-one image from `deploy/` (see Releasing).

The backend **refuses to start** without `JWT_SECRET`, `ENCRYPTION_KEY` and
`SESSION_CACHE_KEY` set (see `backend/app/config.py`). That is deliberate — the
alternative is an instance running on a secret that is public in this repo.
Each value in `.env.example` has the one-liner that generates it. A script
that writes `.env` for you is welcome, but keep it out of git: `/setup.sh`
is ignored for exactly that.

| Change | What it takes |
|---|---|
| Frontend source | Browser refresh. |
| Backend source | `docker compose restart backend` — workers don't hot-reload. |
| `caddy/Caddyfile` | `docker compose restart caddy`. A `caddy reload` is **not** enough: the Caddyfile is a single-file bind mount, and most editors replace the inode, so the container keeps serving the old file while reporting success. |
| Server DB models | Edit the models, then `docker exec backend alembic revision --autogenerate -m "…"` and read what it wrote. Never edit the 1.0.0 baseline — people's databases were built from it. `tests/test_migrations.py` fails until revisions and models agree. |
| Phone schema (`.sq`) | Bump `TracksSchema.VERSION`, add the step to its `migrations`, and commit the snapshot `SchemaMigrationTest` writes. See `TracksSchema`'s docs. |

---

## Testing

```bash
docker exec backend pytest             # ~1825 tests, several minutes
cd frontend && npm test                # vitest
cd mobile && ./gradlew :core:jvmTest   # shared core, no emulator
cd mobile && ./gradlew testDebugUnitTest   # device layer too
cd watchapp && ./build.sh              # compiles, type-checks, signs
```

The backend suite needs the containers up — it runs *inside* the backend
container because that is where its dependencies live, and because it needs a
PostgreSQL server.

It runs against **real Postgres**, in a database of its own (`tracks_test`,
created on first run) alongside the app's. That is not incidental: a large part
of this backend is Postgres-only SQL — full-text search, trigram similarity,
geometry containment for map bounding boxes — and none of it can execute on
anything else. The conftest refuses to start if the database name does not end
in `_test`, because the fixtures empty tables between tests and the alternative
is someone's health history.

Each test runs inside a transaction that is rolled back afterwards, so tests
leave nothing behind and never need to clean up. Two consequences worth knowing:

- Identity sequences are reset per test, so the first row of a table is id 1.
- Code that manages its own `SAVEPOINT` does not nest inside that, and such a
  test needs `@pytest.mark.real_transaction` (the migration test and device
  registration need it today).

`watchapp/` has no test suite. Connect IQ has no practical unit-testing story,
so it is verified in the simulator (`./build.sh fenix6xpro --dev`) and then on
hardware. Treat watch changes as unverified until they have run on a watch.

**A test that depends on today's date is a bug.** Two tests here were written
with hardcoded 2026 dates and quietly began failing months later when those
dates stopped being in the future. Anchor to `datetime.now()` with an offset.

---

## Conventions

These are not aspirations — they are what the existing code already does, and
matching them is most of what makes a change fit.

**Comments say why, not what.** The bar in this codebase is high and worth
keeping: a module docstring explains what the file is for and what constraint
shaped it, and inline comments explain decisions a reader would otherwise
undo. Where an obvious approach was tried and rejected, the comment says so —
several of them record hardware behaviour that took days to establish. If you
find yourself deleting a comment to make a change, read it first; it is
probably load-bearing.

**Security decisions are written down where they are made.** Every trade-off —
cleartext HTTP on the phone, the plaintext music password on the watch, the
open-mode window before setup — is documented at the code that makes it, with
the reasoning and the mitigation. Keep that up. An undocumented security
decision reads as an oversight to the next person, and gets "fixed".

**The desktop is styled from a kit, like the phone.** The phone's look lives
in its shared components (`PillButtons.kt`, `BarPill`, `TracksSwitch`); the
web's lives in `frontend/src/design/kit.js` (classes: `card`, `section-title`,
`bar-pill`, `chip`, `choice`, `segmented`, `switch`, `field`, `field-label`,
`modal`, `icon-btn`, `badge`, `spinner`, `alert-error`) beside the `.btn`
family in `tailwind.config.js`, and `frontend/src/components/ui/` (`Section`,
`Card`, `PageHeader`, `BarPills`, `Tabs`, `Switch`, `Checkbox`, `Button`,
`Modal`, `InfoTooltip`, `DatePicker`, `AnchoredPopover`). Checkboxes and radios are styled
globally in `index.css`. Use these rather than spelling out Tailwind for a
control the kit already has: each hand-written copy drifts a shade from the
last, which is how pages came to look subtly different. Picking a piece: a
window that governs a whole page (Dashboard period, Health range) is
`BarPills` beside the title; one-of-a-few inside a page or form is `Tabs`;
picks among peers in a form are `chip`; an option with a title and a line
under it is `choice`; a setting that applies at once is `Switch`, one that is
submitted with a form is `Checkbox`. `src/test/StyleKit.test.js` fails when a
copy of a kit piece reappears.

**Every file carries SPDX headers.** `REUSE.toml` covers the exceptions.

```
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
```

**Tests are named as sentences** — `test_an_older_edit_loses`,
`test_a_redirect_off_the_configured_host_is_refused`. The docstring says why
the case matters, usually by naming the failure it prevents.

**Don't commit personal data.** `fit-files/`, `.env`, keystores and
`map-data/` are gitignored. Health and activity files are real personal
records; check `git status` before staging broadly.

---

## Where the hard-won knowledge lives

Some of this system was reverse-engineered against hardware, and the notes are
worth more than the code they explain:

- [docs/garmin-ble-protocol.md](docs/garmin-ble-protocol.md) — how the watch
  link actually behaves, measured against a fenix 6X Pro rather than inferred
  from Gadgetbridge. Where the two disagree, that file is what the hardware
  did. Read it before touching anything in `mobile/device-garmin/`.
- [docs/music.md](docs/music.md) and
  [watchapp/README.md](watchapp/README.md) — why music cannot be pushed over
  BLE on any Garmin, and the two transports that work instead.
- [docs/music-testing.md](docs/music-testing.md) — testing the watch music
  app end to end, stage by stage, so a failure points at the layer that owns
  it.
- [docs/browser-device-sync.md](docs/browser-device-sync.md) — the WebUSB/MTP
  path.
- [docs/offline-first.md](docs/offline-first.md) — the design for a phone
  that never needs a server, and for several writers syncing through one.
  Its decisions were made deliberately; ask before departing from them.
- [docs/map-screenshot-testing.md](docs/map-screenshot-testing.md) — driving
  the map headlessly, which is the only way to see what a tile change did.
- [mobile/README.md](mobile/README.md) — module layout and what is verified on
  hardware versus only written.
- [docs/running-pace-research.md](docs/running-pace-research.md) — where the
  one running-fitness number every pace comes from is estimated, the
  literature behind it, which coefficients are still unverified, and how it
  relates to the other fitness numbers (CTL, watch VO2max, auto thresholds).
- [docs/training-intensity-distribution-research.md](docs/training-intensity-distribution-research.md)
  — a summary of the research on how endurance training should split across
  intensity zones, for designing training plans.

---

## Releasing

Tracks is installed by people who never read this repository, from images
and files that update in place. A release is therefore the one change here
that cannot be taken back — read all of this before running it.

### Where a release goes

| Channel | What is published | Who receives it |
|---|---|---|
| Docker Hub [`exercisetracks/tracks`](https://hub.docker.com/r/exercisetracks/tracks) | The all-in-one image ([deploy/Dockerfile](deploy/Dockerfile)), tagged `X.Y.Z`, `X.Y`, `X`, `latest` | Every install. `:latest` is what the README's `docker run` and `compose.yaml` use, so **every** release — a 2.0 included — reaches all of them on their next pull. |
| GitHub release `vX.Y.Z` on [exercisetracks/tracks](https://github.com/exercisetracks/tracks/releases) | Signed APK, its SHA-256, `compose.yaml`, release notes | Android users, directly or through Obtainium, which watches these releases. |
| `deploy/compose.yaml` and `deploy/.env.example` on `main` | The README links the **raw files on main** | Every *new* install, immediately on push — it is not versioned. An edit to it ships the moment it is pushed, release or not. |

**Not yet set up — do not create accounts, submit, or publish to these
without the maintainer:** Google Play, F-Droid, IzzyOnDroid, the Connect IQ
Store, and iOS. Each has decisions behind it that are the maintainer's
(signing, store listings, policy declarations); see `mobile/RELEASE.md`.

### One-time setup on the release machine

Releases are built and published from the maintainer's machine, not CI —
the watch app needs the Connect IQ SDK and a developer key that live there.
Check each of these before the first release in a session:

| Needs | Check | Notes |
|---|---|---|
| Docker Hub, as **exercisetracks** | `docker info \| grep Username` | The maintainer also has a personal Docker Hub account; pushing as it fails, or lands images under the wrong name. |
| GitHub, with push to the `exercisetracks` org | `gh auth status` | `release.sh` pushes `main` and the tag and creates the release. |
| Android release key | `mobile/keystore.properties` exists and points at a `.jks` | The key lives in `~/exercisetracks-release-key/` (password in the README beside it). **If it is missing, stop and ask. Never generate a new one**: a different key is a different app to every installed phone, which can then only be updated by uninstalling — and losing its data. |
| Connect IQ SDK + developer key | `watchapp/build.sh` runs | Only when the watch app changed. See [watchapp/README.md](watchapp/README.md). |
| Git identity | `git config user.email` | This repository commits as `28853569+WebF1yin@users.noreply.github.com`, set in its local config. Never commit here under a personal address — authorship is public and permanent. |

Images are built with Docker's legacy builder, for amd64 only (there is no
`buildx` on the release machine). ARM images need `docker-buildx` and
`qemu-user-static` installed first; nothing else stands in the way.

### Releasing, step by step

1. **Pick the version.** Patch for fixes, minor for features, major for
   anything that breaks an unattended upgrade — a migration that cannot run on
   its own, a removed setting, a changed volume layout. Default installs
   follow `:latest`, so a 2.0 still reaches them on their next pull: it has to
   carry its own upgrade path, and only installs pinned to `:1` are spared it.
2. **Run every suite you touched, and see it pass** (see Testing above).
   A release is not the place to discover a red one.
3. **Schema changes have migrations.** A server model change needs an Alembic
   revision; a phone `.sq` change needs a `TracksSchema` step and a snapshot
   (both covered in the table under Running it). The suites fail without them
   — that is their purpose.
4. **Bump the version in all three places** and commit it on `main`:
   `SERVER_VERSION` in `backend/app/version.py`, `frontend/package.json` (and the two matching
   lines at the top of `package-lock.json`), and `versionName`/`versionCode`
   in `mobile/app/build.gradle.kts`, where `versionCode` is
   major × 10000 + minor × 100 + patch. `release.sh` checks these rather than
   editing them, so the bump is an ordinary reviewed commit. `API_VERSION`
   in the same file is separate: it moves only when the REST surface changes
   in a way a client can observe (its docstring says when).
5. **Watch app changed?** Run `watchapp/build.sh all`, which rebuilds the
   bundle the APK carries. `release.sh` refuses a bundle older than the
   watch app's source.
6. **Release:** `./scripts/release.sh X.Y.Z` from a clean `main`. It builds
   every image and the APK first and publishes only once all of them built,
   then pushes `main` and the tag, then creates the GitHub release. Expect
   20–40 minutes, most of it uploading.
7. **Verify every channel** — the script finishing is not proof:
   - `gh release view vX.Y.Z -R exercisetracks/tracks` lists the APK, its
     `.sha256` and `compose.yaml`.
   - Docker Hub shows `X.Y.Z`, `X.Y`, `X` and `latest` on `exercisetracks/tracks`
     (`curl -s https://hub.docker.com/v2/repositories/exercisetracks/tracks/tags`).
   - A fresh install works, pulled from Docker Hub rather than a local image:
     remove the local `:X` tag, then start it under **its own container name,
     port and volume names**, create an admin through `/api/auth/setup`, and
     check `/api/capabilities` reports the new version. Leave maps off: nothing
     downloads until `map_enabled` is set, and setup alone does not set it.

### When something goes wrong

- **The GitHub step failed** (it did once, on a network blip): the images and
  tag are already public. `release.sh` prints the exact `gh release create`
  command that finishes the job — run that; do not re-run the script.
- **A bad release reached users:** never delete or re-point a published
  version's tag — installs pinned to it, and anyone who verified its digest,
  depend on it meaning one thing. Fix forward with the next patch. If it is
  urgent, first point the moving tags back at the previous release:
  `docker pull exercisetracks/tracks:X.Y.(Z-1)`, then `docker tag` it as `X.Y`,
  `X` and `latest` and push those three.
- **A bad APK** cannot be recalled from phones that installed it. Publish a
  fixed one with a higher `versionCode`.

### Never

- **Change** the Android `applicationId` (`com.exercisetracks.app`), the
  signing key, or the Docker image names. Each change silently abandons every
  existing install.
- **Edit** the Alembic baseline (`0001baseline`) or a committed phone schema
  snapshot: installed databases were built from them.
- **Change** the all-in-one image's PostgreSQL major version (16), the
  `/data` and `/map-data` layout, or the `secrets.env` format, except in a
  2.0 that ships the upgrade path. A database directory only opens under the
  major version that made it.
- **Publish** a debug APK, or images built with development dependencies.
  `release.sh` passes `INSTALL_DEV_DEPS=false` for this reason.
- **Push** the archived pre-1.0 history (`../Tracks-history.git` on the
  maintainer's machine) or any branch from it, anywhere. It contains about
  740 personal FIT files: GPS tracks of where the maintainer lives and trains.
- **Test against the developer's data.** The development compose project is
  also named `tracks`, so a test install started with the default project
  name shares its database volume. Use `docker compose -p <other-name>` or
  distinct `docker run` volume names. Clean up by removing those named test
  volumes and containers only. **Never `docker volume prune`**: it deletes
  every unused anonymous volume on the machine, not just the test's.

---

## Before you open a PR

- The suite you touched is green, and you ran it rather than assuming.
- New behaviour has a test that fails without your change. Check that it does.
- Anything security-relevant says in a comment why it is safe.
- No personal data, secrets or `.env` in the diff.
