<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# How the phone talks to a Garmin

This is the map of the phone's watch sync: what each kind of content travels
over, which code carries it, where that code came from, and how much of it has
been checked on a watch. Read it before you add a sync path. Update the table
in the same change as the code.

## One stack, not two

The phone does **not** run two sync systems side by side, one from
Gadgetbridge and one of Tracks' own. It runs one stack in three layers:

1. **Transport, vendored from Gadgetbridge 0.93.0.** The BLE queue
   (`BtLEQueue`), the two GFDI transports (`CommunicatorV1` for older
   single-channel watches, `CommunicatorV2` plus `MlrCommunicator` for
   multi-link ones), and the GFDI message codec. Everything goes over this,
   and there is only one copy of it.
2. **Protocol handlers, mostly vendored.** Garmin itself has two
   file-transfer protocols, and Gadgetbridge implements both:
   - the legacy one, `FileTransferHandler`: a directory listing, then one file
     at a time, each acknowledged, with `CREATE_FILE` for pushes;
   - the newer one, `FileSyncServiceHandler`: a protobuf listing, with files
     deflated and sent over their own channel.

   These two are the real "two systems". Both are Garmin's, and both are
   needed (see below). The handlers for notifications, music, weather, calls
   and installed apps are also Gadgetbridge's.
3. **Tracks' own code, written from captures of Garmin's apps.**
   `ExploreSession` and `WatchLibrary` (courses and saved places, written
   with no Gadgetbridge code at all), the FIT encoders, and every routing
   decision below. These live in `GarminSupport` (a Tracks-owned replacement
   for Gadgetbridge's own, in `src/compat`), `GarminIntegration` and
   `WatchManager`.

The captures did not add a second stack. What they replaced was
*decisions*: which protocol each kind of content should use, and how the
watch expects it. Gadgetbridge had guessed many of those wrong (see
[garmin-explore-sync.md](garmin-explore-sync.md) and
[garmin-connect-sync.md](garmin-connect-sync.md)).

The long-term direction is to remove Gadgetbridge (licence). New protocol code
is written Gadgetbridge-free in `com.tracks.device.garmin`, the way
`explore/` is. Vendored files change only through recorded patches
(`mobile/device-garmin/SHIMS.md`).

## What goes where

One route per kind of content. Where a fallback exists, the watch's own
handshake picks it, never which class happens to be in use.

| Content | Route | Fallback, and what triggers it | Code | Verified |
|---|---|---|---|---|
| Recordings and health files, pulled | FileSyncService | Directory listing: a V1 (single-channel) watch, or one that never answered a file-list request. That is remembered per watch and cleared once it answers. | `GarminSupport.fetchRecordedData`, `decideSyncProtocol` | fenix 6X, both routes |
| Training calendar + its workouts | Legacy `CREATE_FILE` into `GARMIN/NewFiles`, one batch | none: FileSyncService does not build a calendar (measured) | `WatchManager.pushSchedule` → `GarminIntegration.pushToInbox` | fenix 6X |
| Settings files (Wi-Fi uploads off, user profile) | Legacy, `GARMIN/NewFiles` | none | `stopWifiUploads`, `pushUserProfileIfDue` → `pushToInbox` | fenix 6X (sent; the watch does not confirm settings) |
| Server-built files (race plans) | FileSyncService | Legacy, if the watch refuses it on file-sync | `pushFromServer` → `pushFile` | partly |
| Watch apps (`.prg`) | Legacy (type 255/17 has no file-sync name) | none | `installWatchApp` → `pushFile` | fenix 6X |
| Courses and saved places | Explore sync | FIT files over legacy (`CourseFitEncoder`, `LocationsFitEncoder`), if the handshake does not declare `EXPLORE_SYNC` | `ExploreSession`; fallback in `WatchManager.pushMapItems` | Explore on fenix 6X (which declares it, bit 69). The file fallback has **not** been run on an older watch. |
| Courses/places listed by the server | never pushed from the list: the phone builds its own (avoids duplicates) | none | `pushFromServer` filter | fenix 6X |
| Notifications, calls, weather, music, find-phone | GFDI messages (Gadgetbridge handlers) | none | `GarminSupport`, `GarminIntegration` | fenix 6X |
| Live heart rate, activity status, live track | Realtime service, protobuf | none | patches 0015, 0016 | not yet on a watch |

Every path needs a reason in its own comment, and every fallback needs a
trigger the phone can detect. A path that is "probably needed by some watch" is
not in the table, and should not be in the code.

## The connection's lifetime

Per-connection state lives on `GarminSupport` and the handlers under it:
transfer queues, the protobuf reassembly map, the Explore session, MLR
windows. It is all discarded with the connection. When the phone and watch
disagree about a session, the cure is a **new connection**, not an in-place
repair:

- The watch starting a new GFDI session on a live link drops the link
  ([garmin-ble-protocol.md](garmin-ble-protocol.md), "A session restarted
  under a live link").
- A failed pull, and the first failed Explore session in a row, each rebuild
  the link and try once more.
- `BtLEQueue`'s action deadline and a dead dispatch thread drop the link.

`WatchLink` then reconnects. If you add per-connection state, put it where
`dispose()` throws it away. If a failure leaves the link wedged, rebuild the
link rather than adding a reset for one field.

## Rules for changing it

1. **Route by what the watch declares**: a `GarminCapability` from its
   handshake, the transport it offered, or a refusal it actually returned.
   Never route by `is GarminIntegration`. `WatchManager` holds the one cast,
   `garmin`, and uses it only for features no other vendor has.
2. **Pass the route with the call.** No process-wide mode flags that callers
   set and clear around a push. That is how `forceLegacyPush` worked until
   2026-10-20, and any push from elsewhere during that window went the forced
   way.
3. **A new route replaces the old one in the same change.** Delete the path
   it supersedes, or turn it into a fallback with a detectable trigger and add
   it to the table. The FIT course push sat unreachable for months because
   Explore was put in front of it and nothing said which watches still needed
   it.
4. **New protocol code comes from a capture, and is Gadgetbridge-free.**
   Write what the capture showed into the docs first (what was sent, what the
   watch did). Then write the code in `com.tracks.device.garmin`. Edit a
   vendored file only through a recorded patch; `VendoredLockTest` fails
   otherwise.
5. **Say what was verified.** Code that has only been written, not run on a
   watch, says so in its comment and in the table above.
