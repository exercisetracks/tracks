<!-- SPDX-FileCopyrightText: 2026 Hawk Fugagli -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Offline-first: a phone that never needs a server

**Status: design, settled; not built yet.** Every decision here was made
explicitly (2026-09-24) and should not be relitigated in code review. If
building it turns up a case this does not cover, ask; don't guess.

## Why this exists

Tracks started as a self-hosted web app, and grew a phone client that behaved
like Immich's: useful offline, but only as a cache of a server. That is now the
wrong shape. A phone paired to a watch already holds every raw input Tracks
needs, and a person with no homelab should be able to install the app and use
all of it.

The server stays, and gets *more* useful rather than less: it is where maps are
built, where the web app lives, where several phones and a family meet, and a
backup. But it stops being the only place anything can be computed or decided.

Two hard requirements follow, and most of this document is about the second:

1. **Everything but basemaps works with no server, ever.** Parsing, analysis,
   planning, coaching rules, onboarding.
2. **Sync is correct every time, with any number of writers.** Two phones, the
   web app, a USB bridge and a watch can all produce data for one account while
   some of them are offline for weeks. Nothing is duplicated, nothing is lost,
   and every replica ends up identical.

## What exists today (and is replaced)

| Today | Problem once the phone is a peer |
|---|---|
| `/sync/delta` keyset changefeed over activities + daily metrics | Read-only; covers two tables |
| `cached_document` JSON blobs for every other screen | Wiped by a refresh; not queryable; phone cannot own them |
| `outbox` of ~20 `OfflineWrite` types | Assumes the server arbitrates; last-arrival-wins for all but plans |
| Negative local ids + `local_id_map` | Must be re-solved per entity; breaks down with two phones |
| `LocalActivities` / `LocalHealth` provisional rows | Exist only until the server's copy arrives |
| `field_updated_at` on `planned_workouts` (`merge.py`) | The right idea, on one table, with wall-clock stamps |

`merge.py` is the seed of the new design; the rest is retired once every
client is on the new protocol.

## Settled decisions

| Area | Decision |
|---|---|
| Compute | Port server logic to Kotlin `mobile/core`, each module held to the Python by golden fixtures in `spec/`. |
| Authority | Only **sources** sync. Every device derives metrics itself, with code proven identical. |
| Identity | Client-generated **UUIDv7** on every syncable row. |
| Conflicts | **Field-level last-write-wins** ordered by **hybrid logical clocks**. Append-only logs union by id. |
| FIT history | A linked phone pulls **all** raw FIT blobs, in the background, on Wi-Fi and charging. |
| Dedupe | Per user: SHA-256 of file bytes, then device serial + start time. |
| First link | A standalone phone joining an existing account **merges both ways**, with a one-time review of settings that differ. |
| Web app | Stays online-only; follows the same write rules. |
| Standalone safety | Passphrase-encrypted export to a user-chosen location, plus reminders. |
| AI | The deterministic coaching engine runs everywhere; LLM features only with a server. |
| Maps | No server: blank basemap and a one-line explanation. Public tiles are **deferred**. |
| Music | Out of scope for standalone. |
| Accounts | Server supports several accounts (a family). A phone belongs to one account and may have several watches. |

## The data model: three kinds of row

Everything the backend stores falls into exactly one of these, and the
classification decides how it syncs.

### 1. Sources — sync, merge field by field

What a person or a device actually said. These carry a UUID and a per-field
clock, and are the only mutable rows that sync.

- Profile and `UserSettings` (body, zones overrides, units, theme mode, accent, equipment)
- `UserDevice` / `Device` (which watches belong to whom)
- `TrainingGoal`, `RacePlan`, `TrainingPlan`, `PlannedWorkout`
- `UserFlexibilityFlow` + `FlowStretch`, `UserFlexibilityPreference`, `UserCustomStretch`
- `UserWorkout` + `UserWorkoutExercise`, `UserExercisePreference`, `UserCustomExercise`, 1RM overrides on `UserExerciseStrength`, `AnimationConfirmation`
- `Medication` + `MedicationSchedule`, `Injury`, `Meal`
- `CustomTrackFolder`, `CustomTrack`, `Waypoint`
- The **user-editable fields** of `Activity` (name, sport override, notes, equipment, merge membership) — not its measurements
- Manual `DailyMetric` entries (weight, hydration, calories in), keyed by `(user, date)`

### 2. Logs — sync, union by id

Things that happened. Created once and never edited, only retracted. They merge
by set union on UUID, so they cannot conflict.

- `MedicationLog`, `MealLog`, `UserWorkoutSession` (with its sets)
- **FIT files**, identified by content hash, stored as blobs

A retraction ("I didn't actually take that dose") is a tombstone, like any delete.

### LLM output — sync, read-only

Coaching text produced by a linked server's LLM provider is written by the
server alone and syncs to phones as read-only rows. A recommendation read on
Tuesday with signal can then be reread on Thursday without it. Phones never
create or edit these, so they need no merge rules.

### 3. Derived — never sync, recompute

Everything computed from sources and logs, on every device:

- Activity measurements, `Lap`, `DataPoint`, `PowerBest`, `PaceBest`, `ClimbSplit`, `StrengthSet`
- Device-derived `DailyMetric` fields (sleep, HRV, stress, steps, body battery)
- CTL/ATL/TSB, readiness, VO₂max trend, `UserFitnessFingerprint`, estimated 1RMs, progression stages
- Automatic workout↔activity matching

Because these never sync, they can never conflict. The cost is that the phone
and the server must compute them **identically**, which is what the fixture
corpus is for (see [Porting](#porting-the-server-logic)).

### Not synced at all

Server infrastructure (`MapRegion`, `StreetJunction`, `PoiSearch`, music, upload
jobs, sync agents, refresh tokens, device keys, ICS tokens) and reference
libraries. The exercise and stretch libraries move into `spec/` as data that
both apps ship, the way zones and muscle groups already do. That also fixes the
duplicate and raw-key muscle tags seen on both apps today.

## Identity

Every source and log row gets `uid UUID NOT NULL UNIQUE`, generated by whoever
creates it. UUIDv7, because it is time-ordered and so indexes well in both
Postgres and SQLite.

The server **keeps its integer primary keys** internally. Foreign keys, the
Postgres-only queries, and every existing endpoint the web app uses stay put.
The UUID is the sync identity; the wire protocol never carries an integer id.
Keeping the integers means the change to every relation is a new column rather
than a rewrite.

References between synced rows (a flow's stretches, a planned workout's goal)
travel as UUIDs and are resolved on arrival. A reference to a row that has not
arrived yet is kept and resolved later. Order of arrival is never assumed,
because two phones can deliver a parent and a child in either order.

Negative ids, `local_id_map` and `allocateLocalId` are deleted.

## Clocks

Wall clocks are not good enough to order edits. Phones drift, and a phone with
its clock set a day ahead would win every conflict for a day. So each edit is
stamped with a **hybrid logical clock**:

```
hlc = (wall_ms: int64, counter: int16, node: 64-bit device id)
```

- `node` is a random id created once per install (and one per server). It breaks
  ties deterministically.
- On a local edit: `wall_ms = max(now, last.wall_ms)`, and `counter` increments
  if `wall_ms` did not move.
- On receiving any stamp, the local clock advances past it. So a device can
  never produce an edit that sorts before one it has already seen, even with a
  wrong clock.
- A stamp more than 24 h ahead of the receiver's wall clock is **refused** and
  logged. A phone whose clock was set to 2035 must not poison a field
  permanently.

Encoded as a fixed-width sortable string, so both SQLite and Postgres compare
them with ordinary string comparison.

The web app does not keep a clock. The server stamps web edits on arrival,
because the web client is always online, so arrival *is* when the edit was
made.

## Merge rules

For each synced row, each mergeable field carries its own stamp:

```
field_clock: {"title": "<hlc>", "is_complete": "<hlc>", ...}
```

This generalises `planned_workouts.field_updated_at`, which it replaces.

- **Field write**: applied if its stamp is strictly greater than the stored
  stamp for that field. Ties lose, so replay is idempotent, as in `merge.py`
  today.
- **Create**: a create is just a set of field writes to a UUID nobody has seen.
  Two replicas creating the same UUID cannot happen by accident.
- **Delete**: a `deleted` field like any other, set to its HLC. **Delete
  wins**: once a row is deleted, an edit from another device is discarded,
  whatever its stamp, and nothing resurrects it. Predictability matters more
  here than preserving a late edit; people who remove something do not expect
  it back.
- **Logs**: union by UUID; a tombstone retracts.
- **Children** (a flow's stretch list, a workout's exercises): each child is its
  own row with its own UUID and an `order` field. That way, reordering on one
  phone and adding a stretch on another both survive. Ordering keys are
  fractional (lexicographic midpoints), so an insert never renumbers siblings.

These rules are implemented **twice**: in Python for the server and in Kotlin
for the phone. They are held together by a shared scenario corpus,
`spec/fixtures/sync_scenarios.json` (vocabulary in `spec/sync.yaml`), written as operation sequences across N replicas
with the expected converged state. Both test suites replay it. A randomised
convergence test (random ops, random delivery order, assert all replicas are
equal) runs on each side too.

## The protocol

Replaces `/sync/delta` and the outbox.

### Change sequence

Every write to a synced row on the server also sets `server_seq`, taken from one
Postgres sequence. That gives a total order of *arrival* at the server. The pull
cursor is a single integer, and the tie-group problem that `sync_delta.py`
works around with `(updated_at, id)` disappears.

### Push — `POST /sync/push`

```json
{ "node": "…", "changes": [
    { "entity": "planned_workout", "uid": "…",
      "fields": { "title": ["Tempo", "<hlc>"], "deleted": [true, "<hlc>"] } } ] }
```

The server merges each field under the rules above and answers per change:
`applied`, `stale` (lost on some fields), or `pending_reference`. The phone
keeps its pending changes, now a table of dirty fields rather than a queue of
API calls, until a push acknowledges them. Pushing the same batch twice is
harmless.

### Pull — `GET /sync/pull?since=<seq>`

Returns full current rows (values plus `field_clock`) for everything with
`server_seq > since`, including tombstones, paged, together with the next
cursor. The phone merges these with the same rules it uses locally, so a pull
never overwrites an edit the phone made but has not pushed yet.

### Blobs

- `POST /sync/blobs` — the existing sealed upload (`/sync/ingest`). It still
  needs no crypto session, so watch sync keeps working with the vault locked.
- `GET /sync/blobs/manifest?since=` — hashes, sizes and device/start metadata
  of every FIT file the account has.
- `GET /sync/blobs/{sha256}` — the file, for the phone's background history
  download.

### Order within a sync

Push, then pull, then blobs. A sync interrupted anywhere resumes correctly,
because every step is idempotent and the cursor only moves with its data (the
rule `SyncEngine` already enforces).

### Tombstones

Kept forever, as today; they are never pruned.

### A wiped account versus a recreated server

These look the same to a phone, since in both cases the server no longer has
its data, but they need opposite responses. So every pull carries the server's
`server_id` and the account's `epoch` (see `spec/sync.yaml`):

- **A recreated server** has a new `server_id`. The phone re-pushes everything
  it holds, and the server gets its data back.
- **A deliberate "Delete my data"** bumps the account's `epoch`. The phone
  wipes its own copy too, and a push from an older epoch is refused.
  Otherwise any phone still holding the data would silently undo the
  deletion.

### Signing out

Signing out unlinks the server and **keeps** the phone's data, because for
someone who started standalone it may be the only copy. The data belongs to
the account it was first linked to. Signing back in to that account resumes.
Signing in to a different account on the same server is refused while local
data exists, and the app offers an explicit erase instead. On a server with a
different identity, which may be the same server rebuilt, the phone asks
before restoring its data into the account. Merging one person's history into
someone else's account by accident cannot be undone.

## FIT files and dedupe

A FIT file is identified by the SHA-256 of its bytes, **scoped to the user**.
The current check in `services/fit_import.py` is not scoped. Two family members
importing the same file would silently drop the second, so this is fixed in
phase 1 regardless of anything else.

A second rule catches the same activity arriving as different bytes (a watch
re-exporting, a different transfer path). An activity's uid is derived from
**device serial + start second** (see `spec/sync.yaml`), so both copies are
the same activity by construction, on every replica, with no comparison step.
The file with the lowest hash is the one parsed; the others are kept as blobs,
so nothing is ever deleted. A file with no device serial falls back to its own
hash.

Recordings from *different* devices that overlap in time (watch + bike
computer) stay separate activities. Combining them is the existing merge
feature, which becomes a synced source edit.

Monitoring, sleep and HRV files dedupe by hash only; they are already
day-keyed by content.

## Porting the server logic

Order is by what a standalone phone needs first. Each module is ported into
`mobile/core/commonMain`, with a `spec/make_*_fixtures.py` generator that runs
the Python over real inputs and records outputs, and a Kotlin test that
replays them. This is the existing pattern (zones, strength, fueling, FIT
workout encoder), and it has already caught one rounding divergence.

1. FIT parsing — `parsers/activity.py`, `sleep.py`, `daily_health.py`, `smoother.py` (~2.6k lines). Replaces `FitSummary` and `FitDailyHealth`, which are partial.
2. Activity metrics, zones time-in-zone, sport-specific calculators (`activity_metrics`, `road_cycling`, `mtb`, `muscle_activation`).
3. Training load, readiness, user stats, VO₂max trend.
4. Workout↔activity matching.
5. Strength and mobility plan generation (`strength_plan/`, ~2k).
6. Endurance plan generation (`plan/`, ~3k).
7. Coaching rules engine (`coaching/`, ~1.4k).
8. Race predictor (`race_predictor/`, ~1.2k). Its weather input needs a network; offline, it predicts without weather and says so.
9. Course and location FIT encoders (`fit_course`, `fit_locations`).

Fixtures are real FIT files. Those are personal data, so the fixtures record
derived outputs plus a hash of the input, and the inputs live in the gitignored
`fit-files/`. Where a synthetic file exercises the same path, that goes in the
repo instead.

### Plan generation needs one extra rule

Generating a plan is not derived data. It is a user action whose output (the
workouts) is a source. If two offline phones both regenerate, both sets of
workouts would sync and the calendar would double. So `TrainingPlan` carries a
`generation` UUID (an LWW field), each generated workout records the generation
it came from, and workouts from a losing generation are tombstoned by whichever
replica notices. Hand-added workouts (`plan_id = NULL`) are untouched, as today.

## The phone's storage

The mirror stops being derived data. It becomes the phone's own database.
Synced rows live in **one generic table** keyed by `(entity, uid)`, holding
field values as JSON alongside the per-field clock, the delete stamp and the
set of dirty fields. That replaces `cached_document`.

One table rather than a table per entity, because every entity merges
identically and the phone never queries into these rows with SQL. They number
in the hundreds and are decoded whole. Derived data, where queries do matter
(activities by date, daily metrics), keeps typed tables. This reverses a stated
assumption in `Tracks.sq` ("a schema change can drop the tables") in
principle. It does **not** yet in practice: see [No backwards
compatibility](#no-backwards-compatibility).

SQLCipher with the key in the Keystore is unchanged, and matters more now: for
a standalone user this *is* their only copy.

FIT blobs live in app-private storage. They are encrypted at rest using a key
derived from the same Keystore key, not left plain on disk, because they
contain precise GPS.

### Compatibility since 1.0.0

*Settled.* Until the first public release Tracks carried no compatibility and
no migrations: models were edited directly and databases recreated. That
ended with 1.0.0, because a standalone phone's database is its user's only
copy and a server's is someone's whole history.

- **Server**: the Alembic history was collapsed into one baseline, the 1.0.0
  schema, which is frozen. Every change since is a revision on top, applied
  at startup (`main._run_migrations`); `tests/test_migrations.py` fails when
  the revisions and the models disagree. A database from before 1.0.0, or
  from a newer release than the one starting, is refused with an
  explanation rather than half-migrated.
- **Phone**: `TracksSchema` migrates version by version from the 1.0.0
  baseline. `SchemaMigrationTest` replays every released schema from a frozen
  snapshot and requires the migrated result to equal a fresh install. Only a
  pre-release database is still dropped.
- **Protocol**: an older app and a newer server negotiate through
  `GET /capabilities` (`backend/app/version.py`). Removing something an
  installed app depends on means raising `MIN_CLIENT_API_VERSION`, which
  tells that app to update instead of failing one endpoint at a time.

### Size

Five years of one person's FIT history (activities, monitoring, sleep) is
under 10 MB, so pulling all of it is not a storage concern. Settings shows the
space used anyway, and warns past 500 MB. That is far beyond real use, and
reaching it most likely means something is duplicating files.

## Accounts

Most of this exists already: rows are scoped by `user_id`, crypto is per user,
and an admin can create users (`api/users.py`). The work is an audit for places
that assume one user (`db.query(User).first()` in `auth.py`, the unscoped hash
check above) and a test that two users' data never cross in sync.

A phone is bound to one account. It may pair several watches; each is a
`UserDevice` source row, and dedupe keys on device serial, so two watches never
collide.

## Standalone mode

- **Onboarding** forks on its first screen: *Use on this phone* or *Connect to my
  server*. Standalone runs the desktop Setup's profile steps (body, zones,
  strength equipment, look), a short note that a self-hosted server is more
  private and enables basemaps, then watch pairing. The server path signs in and
  pulls the profile, skipping those steps. Either path can add the other later
  from Settings.
- **Linking later** runs a first sync that merges both ways under the normal
  rules. It then shows a one-time review of any settings whose values differ,
  so a zone change on one side is a visible choice rather than a clock race.
- **Backup**: a passphrase-encrypted archive, written through the Storage
  Access Framework to wherever the user picks, and restorable onto a fresh
  install (from Settings, or "Restore from a backup" on the first screen). It
  holds every synced row with its stamps and tombstones, every FIT file, and
  the account binding — nothing derived, which is rebuilt from the files on
  restore. `PBKDF2-SHA256` (600k iterations) + `AES-256-GCM`; the reasoning is
  at `mobile/app/.../backup/BackupCrypto.kt`, the format at
  `mobile/core/.../backup/Backup.kt`. The dashboard nudges when there is no
  server and no backup newer than two weeks.

  *Not built: server import.* It needs no new protocol. A server-side importer
  would decrypt the file with the passphrase, hand the rows to the existing
  `sync.store.push` as one client's changes (they already carry stamps, so
  they merge like any phone's), and feed each FIT file to `/sync/ingest`'s
  importer. Linking the phone that made the backup does the same thing with no
  file at all, which is why it waited.
- **Maps** with no server show tracks, courses and waypoints on a blank
  canvas, with one line saying basemaps come from a Tracks server.
  Offline routing (the vendored BRouter) still needs segments, so drawing
  without a server falls back to straight lines.

## Design system

Theme mode and accent colour are already user settings, synced between phone
and web. What is not shared is everything underneath: corner radii (the phone
uses nine different values, the web mostly `lg`/`xl`/`full`), spacing, the
type scale, and the font.

`spec/design.yaml` defines radii, spacing, the type scale, elevation, and
component specs (card, chip, pill, section header, stat). A codegen step writes
the Tailwind theme extension for the web and a Kotlin `Tokens` object for
Compose, the same way `zones.yaml` works. **Inter** ships with both: a subset
variable font in the APK, self-hosted on the web, with tabular figures for
stats.

## Mobile UI revamp

Every screen reaches parity with the desktop, except map region building.
The four named for a rebuild:

- **Training plan**: a goal card at the top, with days/week, intensity,
  predicted time, the phase timeline, and regenerate / sync to watch / copy
  subscription link. Below it, a week agenda by default, with desktop-style
  coloured, titled chips; long-press to move a workout to another day. A toggle
  switches to the month view.
- **Guided strength session**: one exercise at a time, with the pose or body
  diagram, large set/rep/weight controls, an automatic rest timer, and a peek at
  what's next.
- **Stretching flow player**: hands-free. A large countdown; voice and
  vibration cues on transitions and side switches; the screen stays on; and it
  can be controlled from the lock screen or notification.
- **Onboarding**: as in [Standalone mode](#standalone-mode).

New destinations: Goals, Race Plans. Settings gains everything the web edits.
Activity detail gains sport change, delete, merge, notes and equipment.

### Bugs seen while surveying, to fix along the way

- Raw muscle keys shown as labels (`hip_external_rotators`, `spinal_erectors`), on phone and web.
- Duplicate muscle tags ("calves calves", "upper_back upper_back") on the web.
- Units mixed within one workout: "Easy — 1.51 mi", "2 km", "13:16/mi".
- ISO dates in user-facing text on the phone.
- Dashboard training-load chart renders empty on the phone.
- Calendar cells show only colour dots, with no legend.

## Phases

Each phase gets its own branch off `mobile-app`, leaves every suite green, and
ends with a commit (or several).

1. **Sync foundation.** Backend: `uid`, `field_clock`, `deleted` and
   `server_seq` on all source and log tables (new Alembic baseline, no migration),
   HLC and merge in Python, `/sync/push`, `/sync/pull`, the blob manifest and
   download, per-user dedupe fix, multi-account audit. Core: HLC, merge, new
   store schema, new engine. Shared scenario corpus. The web switches its
   writes to the merge path.
2. **Kotlin ports**, in the order above, each with fixtures.
3. **Standalone mode**: onboarding fork, local-only operation, first-link
   merge, backup/restore, the blank-map state.
4. **Design tokens**: `spec/design.yaml`, codegen, Inter, then the web and the
   phone moved onto them.
5. **UI revamp and parity**: the four screens, then the remaining parity list.

## Resolved questions

Kept so the reasoning is not lost.

1. **Delete vs concurrent edit**: delete wins, no resurrection.
2. **FIT history size**: not a concern (five years < 10 MB). A 500 MB warning exists as a tripwire.
3. **Old clients**: none before 1.0.0. From 1.0.0 on, see [Compatibility since 1.0.0](#compatibility-since-100).
4. **Library updates**: exercise and stretch libraries ship with the app; a new exercise needs an app update. Custom exercises and stretches sync as user data.
5. **LLM output**: syncs to phones as read-only rows.
