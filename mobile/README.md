# Tracks Mobile

A native Android client for Tracks that works with no server at all. The phone
parses its watch's FIT files itself — with ports of the server's own parsers and
metrics, held to them by fixtures — so activities, health, the dashboard,
training load, strength standings, the plan, flows and saved places all live and
change on the phone. A Tracks server, when there is one, adds map basemaps, the
web app, plan generation and a backup, and the two sync field by field. See
[`../docs/offline-first.md`](../docs/offline-first.md).

This is an expedition tool, so battery behaviour is a requirement, not polish.

**Status:** a full app, not a prototype. It has the same pages as the web app,
reached from a sidebar with the same names in the same order: Dashboard,
Activities, Training, Strength, Flexibility, Race Plans, Health, Maps, Music
and Settings. On top of those come guided workouts (planned runs recorded with
GPS and cues, strength sessions, stretching flows), phone feeds to the watch,
and medication reminders. The web app still has things the phone lacks: full
settings, activity editing beyond renaming, and the cycling, swimming and
triathlon race-plan breakdowns. Training plans are generated on the phone
(endurance sessions and field tests; the strength and stretch sessions the
server also weaves in are not yet). Still server-only: importing a GPX/course
file, race-day weather, and the calendar subscription feed. Pulling from a fenix 6X
and delivering to the server is validated on hardware, as is pushing the
training calendar to it; see
[`../docs/garmin-ble-protocol.md`](../docs/garmin-ble-protocol.md) for what has
and has not been seen working on a watch.

## Layout

```
mobile/
├── core/          Kotlin Multiplatform — models, domain math, API client,
│   ├── commonMain   FIT parsers, metrics, replica + local store. Must compile for iOS.
│   ├── androidMain  SQLCipher driver, Keystore-backed token store
│   └── jvmTest      the test suite CI runs — no emulator needed
├── app/           Android — Compose UI, WorkManager
├── device-api/    Vendor-neutral device contract — one interface per vendor
├── device-garmin/ Vendored Gadgetbridge Garmin BLE support, plus the Tracks
│                  side of it. See device-garmin/SHIMS.md before touching it.
├── fit-codegen/   Gadgetbridge's FIT message generator — a build tool whose
│                  output reaches the APK and whose profile JSON never does
├── routing-brouter/ Vendored BRouter, for routing inside offline regions
│                  with no server. See its VENDORED.md; never hand-edit.
└── tools/gfdi/    Python for decoding BLE captures and FIT dumps — how the
                   protocol notes in docs/ were established
```

`core/commonMain` must never touch a platform API — that is what keeps the
module reusable, and the iOS targets in `core/build.gradle.kts` are what enforce
it rather than a comment asking politely. Anything Android-specific goes in
`androidMain` or `:app`.

## Building

```bash
cd mobile
./gradlew :core:jvmTest      # the shared core
./gradlew testDebugUnitTest  # the device layer too
./gradlew :app:assembleDebug # the APK
./gradlew installDebug       # onto a connected device
```

Needs `local.properties` with `sdk.dir=$HOME/Android/Sdk` (git-ignored; Android
Studio writes it, or write it by hand). Targets **compileSdk 35 / minSdk 26** —
Gadgetbridge supports 23, but the BLE and foreground-service APIs this app leans
on are far less painful from 26 up.

The debug APK is ~34 MB, almost all of it SQLCipher's native libraries for four
ABIs. Release builds should split by ABI; nobody needs all four.

## Licence

The whole repository is AGPLv3 — see [`../LICENSE.md`](../LICENSE.md) and
[`../NOTICE`](../NOTICE). `device-garmin/` vendors Gadgetbridge, which is
AGPLv3, and the project relicensed to match rather than carry a per-directory
split.

## Toolchain setup

Nothing here is installed by the Docker stack; this is host software.

### 1. JDK 21

```bash
sudo pacman -S jdk21-openjdk        # Arch
# or: sudo apt install openjdk-21-jdk
java -version                        # expect 21.x
```

### 2. Android Studio + SDK

Install Android Studio (Arch: `yay -S android-studio`, or the JetBrains
Toolbox). On first run let it install:

- **SDK Platform 34** or newer
- **Android SDK Build-Tools**
- **Android SDK Platform-Tools** (this is what provides `adb`)

Then export the SDK location — Studio shows it under
*Settings → Languages & Frameworks → Android SDK*:

```bash
# ~/.bashrc
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

### 3. A physical phone

**The emulator cannot do Bluetooth LE.** Every part of the watch integration
needs real hardware. On the phone: *Settings → About → tap Build number seven
times*, then *Developer options → USB debugging*. Plug it in and confirm:

```bash
adb devices    # your device should be listed as "device", not "unauthorized"
```

### 4. Release keystore

Generate it once, back it up somewhere outside the repo, and never lose it —
losing it means users must uninstall and reinstall to ever get another update.

```bash
keytool -genkey -v -keystore ~/tracks-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias tracks
```

Keep it out of git. Point `signingConfigs` at it via environment variables or a
`local.properties` entry, not a committed path.

## Building and testing the core

```bash
cd mobile
./gradlew :core:jvmTest
```

This runs `core/` against `spec/fixtures/`, the same golden corpus the Python
and JavaScript suites use — see [`../spec/README.md`](../spec/README.md). It has
already earned its keep: it caught Kotlin's `roundToInt()` rounding halves away
from zero where Python's `round()` rounds half to even, which put every zone
boundary landing on an exact `.5` one unit off between phone and server.

`core` is pure Kotlin with no Android APIs, so it needs no Android SDK and no
Android Gradle Plugin. That is deliberate: it is the module a future iOS app
reuses, and keeping Android out of it is what makes that possible. iOS targets
are declared in `core/build.gradle.kts` but only configured on macOS — their
value on Linux would be nil, and on a Mac they force `commonMain` to compile for
iOS, which is what actually stops a JVM-only API sneaking into shared code.

## The session state machine

`TracksClient` exists mostly to keep the session alive without involving the
user, and that is the part worth reading before changing anything:

| Response | Meaning | What the client does |
|---|---|---|
| `401 session_expired` | Authenticated, vault shut | Device unlock, retry once |
| `401` anything else | Access token dead | Refresh, retry once |
| Refresh rejected | Token expired or family revoked | Clear everything, `LoggedOut` |
| Vault shut, no device key | Nothing to unlock with | `VaultLocked` — prompt for password |

Two details that look like implementation trivia and are not. A refresh is
**never** used to reopen a vault: the decryption key is derived from a secret
the refresh token does not carry, so it would burn a rotation and still fail.
And recovery is serialised behind a mutex — without it, five requests failing
together would each mint a refresh token, and the server's replay detection
would revoke the whole family, turning a lapsed session into a forced logout.

Recovery runs **once** per request. A second failure is real, and retrying
further just turns a broken session into a request storm from a phone on a bad
connection.

## Sync and local data

The phone's database (SQLCipher, key in the Keystore) has two halves, and the
split is the whole design:

- **What a person said** — goals, the plan, flows, workouts, sessions, doses,
  meals, injuries, tracks, places, settings, and the edits made to an
  activity — lives in the **replica** (`com.tracks.core.replica`,
  `Replica.sq`): one generic table of synced rows, each field stamped with a
  hybrid logical clock, merged field by field with delete-wins, per
  [`../spec/sync.yaml`](../spec/sync.yaml). The server runs the same merge in
  Python; both replay `spec/fixtures/sync_scenarios.json`, and
  `ConvergenceTest` runs several phones with skewed clocks and random sync
  orderings and requires them all to end identical. This is the only copy of
  an unpushed edit.
- **What the phone derived** from FIT files — activities, laps, tracks, sets,
  days of sleep, steps and stress — lives in `Local.sq`, written only by
  `LocalImporter`, which runs the ported parsers with the server importer's
  own rules (activity identity from watch serial and start second, lowest hash
  wins, the same day-merge rules) and is checked against the server by
  `spec/fixtures/local_import.json`. None of it syncs; every device computes
  it from the same files.

Every FIT file enters through one door, `LocalFiles`: stored in app-private
storage sealed with AES-GCM under a Keystore key (`AndroidBlobs`) and imported
in the same call, whether it came off the watch, from a run recorded on the
phone, or down from the server's history. Screens read both halves through
`LocalSources`, `LocalMetrics`, `LocalTraining`, `LocalMapItems` and
`LocalActivityDetail`, which return the API's own models so the UI never knew
where the data came from.

**Syncing** (`AppContainer.syncWithServer`) pushes dirty fields, pulls
everyone else's, downloads FIT files the phone lacks (unmetered networks only)
and uploads the ones the server lacks through the sealed ingest. A standalone
phone simply has nothing to sync with. Every pull names the server
(`server_id`) and the account's `epoch`: a recreated server gets this phone's
data re-pushed; an account whose owner deleted their data gets this phone's
copy deleted too.

**Signing out keeps the data.** The phone's data belongs to the first account
it linked to. Another account on the same server is refused while data
remains, and erasing is an explicit choice (`AppContainer.eraseLocalData`). A
server with a different id — possibly the same one rebuilt — is a question:
"restore this phone's data here?"

There are **no database migrations.** `TracksSchema` drops and recreates the
whole database whenever its `VERSION` changes, and a test fails if the schema's
SQL changes without that bump. That is acceptable only while nobody but the
developer depends on this app: dropping the replica loses unpushed edits.

## Background sync

`SyncWorker` keeps phone and server in step without the app being opened. WorkManager
rather than a service or a coroutine, because Doze and App Standby kill anything
else — and a phone in a pocket on a mountain is the situation this app exists
for, so work that only runs with the screen on would miss the point.

Every six hours, not every fifteen minutes: the data changes when a watch syncs,
a few times a day at most, and each wake-up costs radio time that an expedition
cannot spare. Constrained on `batteryNotLow`: syncing is a convenience, and
the battery may not be.

`Result.retry()` versus `Result.failure()` is the part to get right. A locked
vault, missing credentials, or an incompatible server all fail outright — no
amount of waiting fixes any of them, and retrying on a timer would just hammer
an endpoint guaranteed to 401. Everything else retries with exponential backoff,
because out here a failed request usually means no signal.

## Phone feeds

A watch wants four things from a phone that have nothing to do with fitness:
notifications, weather, the agenda, and what is playing. A fitness app that
pairs to a watch and then silences all four is a downgrade from Gadgetbridge, so
they are in scope.

| Feed | Source | Permission |
|---|---|---|
| Notifications | `NotificationListenerService` | `BIND_NOTIFICATION_LISTENER_SERVICE` (optional) |
| Music | `MediaSessionManager` | rides on the notification grant |
| Weather | broadcast from a weather app | none |
| Calendar | `CalendarContract.Instances` | `READ_CALENDAR` (optional, runtime) |

**Weather works alongside Gadgetbridge.** Established empirically, because it
was not obvious: manifest receivers do *not* receive implicit broadcasts on
Android 8+, but Breezy Weather calls `queryBroadcastReceivers` and sends
explicitly to every app that matches. `adb shell cmd package query-receivers -a
nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER` lists both apps, so
both get the forecast. We speak that action because it is what weather apps
already implement — inventing our own would mean asking every weather app to add
support for us.

**Music rides on the notification permission** because
`MediaSessionManager.getActiveSessions` requires a notification-listener
component to prove the caller may observe media. One grant buys both, and
neither is needed for the app to work.

**Notifications can be acted on from the watch.** Each relayed notification
carries the posting app's own reply action and up to five of its buttons
("Mark as read", "Archive", "Like"), plus Clear and Mute app. The watch answers
with an index, and the phone performs the app's own `PendingIntent` — a reply
goes in through the app's `RemoteInput`, exactly as the system's inline-reply
field would send it. The quick replies the watch offers are edited in
Settings → Notifications on the watch. Only what the notification already
offered can be done; buttons that would open a screen on the phone are not
offered, since nothing can open one from a pocket. **Written and unit-tested,
not yet tried on a watch.** Unknowns there: whether the fēnix 6X offers the
quick-reply list for every app or only for messages, and whether clearing on
the watch sends a dismissal or only hides it locally. Call controls (answer,
decline) are still unhandled.

**Calendar reads `Instances`, not `Events`.** Events holds the recurrence
*rule*; Instances holds the expanded occurrences. Querying Events would show a
weekly stand-up once, on the day the series began, and never again.

Only what a watch can display ever leaves the phone. No feed is persisted.

## Things worth knowing before writing any of this

**Background execution is the whole game.** Android kills background work via
Doze and App Standby. There are two legitimate ways to run: **WorkManager** for
anything deferrable (delta sync, uploads), and a **foreground service with a
declared type** for work that must run now. Android 14+ requires the type in the
manifest *and* a runtime justification; `connectedDevice` and `dataSync` are the
two needed here.

**Prefer CompanionDeviceManager over BLE scanning.** It gives a system pairing
dialog, an association that survives reboots, and
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` so the OS wakes the app when the
watch comes into range. Not scanning is the single biggest battery win
available.

**Bluetooth permissions changed twice.** API 31+ needs `BLUETOOTH_SCAN` and
`BLUETOOTH_CONNECT` as runtime permissions; below that, scanning required
`ACCESS_FINE_LOCATION`.

**One watch, one phone app.** A Garmin watch holds a single BLE pairing for its
companion protocol, so using Tracks Mobile means unpairing from Garmin Connect.
Surface that in onboarding; it is a fact, not a bug.

**Process death is normal.** The app is killed and recreated constantly, so UI
state must be reconstructable from persisted state. That is why the offline
cache is load-bearing rather than an optimisation.

**Encrypt the local cache.** The app will hold decrypted GPS that the server
deliberately never stores in the clear. SQLDelight over SQLCipher with the key
in the Android Keystore, gated behind device biometrics. Skipping this makes the
phone the weakest link in a system built around per-user envelope encryption.

## Server API notes

The backend has been prepared for a native client. Relevant endpoints:

| Endpoint | Why it matters |
|---|---|
| `GET /capabilities` | Version + feature negotiation. Public. Call before assuming an endpoint exists — an installed app can be older or newer than the server. |
| `POST /sync/push`, `GET /sync/pull` | Field-level sync of every synced entity — see `spec/sync.yaml`. Cursor is the server's arrival sequence. |
| `POST /auth/refresh` | Rotating refresh tokens. Opt in at login with `issue_refresh_token`. |
| `GET /maps/style.json?base_url=` | The map style. Pass `base_url` — MapLibre Native has no page origin to resolve `/api` paths against. |
| `GET /activities/{id}/track?max_points=` | Bound the payload; the server-side default can be uncapped. |
| `GET /activities/heatmap?bbox=&zoom=` | Bound the payload; unfiltered this is the user's entire GPS history. |
| `POST /sync/ingest` | Sealed FIT upload. Needs no crypto session, so **watch sync works while the vault is locked** — the expedition case. |

**Two expiries, not one.** The JWT lasts 30 days, but the Redis-cached
decryption key behind it lapses independently (7 days sliding, and it dies when
Redis restarts). When it does, endpoints carrying encrypted data return
`401 {"detail": "session_expired"}` while everything else keeps working. Treat
that as *unlock*, not *log out* — and note that refreshing an access token
cannot unlock the vault, because the key is derived from a secret the refresh
token does not carry.

**Enrol a device key, or the user retypes their password every week.** After
login, `POST /auth/device-keys` returns a 32-byte secret exactly once. Store it
in the Android Keystore — hardware-backed, `setUserAuthenticationRequired`, and
never in SharedPreferences. On `session_expired`, `POST /auth/device-unlock`
with it, replace the stored refresh token from the response, and retry the
original request. The user sees nothing.

The secret decrypts this user's data, so treat it exactly as seriously as a
password: it is revocable server-side (`DELETE /auth/device-keys/{id}`, which is
the answer to a lost phone), and a password change revokes it along with every
refresh token.
