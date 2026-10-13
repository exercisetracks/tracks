<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Garmin Explore sync — courses, saved places and the live activity

How a phone puts courses and saved places on a Garmin watch, takes them off,
and reads activities back. It is written from **decoded Bluetooth captures of
Garmin Explore** talking to a fēnix 6X Pro Solar (firmware 28.02), not from
Gadgetbridge or any other source. Read it before touching
`mobile/device-garmin/src/main/java/com/tracks/device/garmin/explore/`.

Everything here was observed on the wire. Where something is inferred, it says
so, and where something is unknown, it is under [Open questions](#open-questions).

---

## Contents

1. [The model in one page](#the-model-in-one-page)
2. [Captures and tools](#captures-and-tools)
3. [Session flow](#session-flow)
4. [Message reference](#message-reference)
5. [Lines (courses, activities)](#lines-courses-activities)
6. [Saved places (waypoints)](#saved-places-waypoints)
7. [Operations, step by step](#operations-step-by-step)
8. [The live activity](#the-live-activity)
9. [Failure: `SYNC_FINISHED_FAILED`](#failure-sync_finished_failed)
10. [What Tracks got wrong, and why](#what-tracks-got-wrong-and-why)
11. [Open questions](#open-questions)
12. [Captures still wanted](#captures-still-wanted)

---

## The model in one page

**The phone owns the library; the watch mirrors it.** Explore declares a
*collection* (a 16-byte UUID it made up) to the watch. Every course and saved
place the phone wants on the watch is an *item* in that collection, under a
UUID the **phone** generated. The watch reads the collection, notices items it
does not have, and pulls each one's content from the phone.

- **Adding** something is adding an item and announcing a change.
- **Removing** something is marking its item `tombstone = 1` with a fresh
  stamp, and announcing a change. There is no delete command. The tombstone
  stays in the collection afterwards.
- **The watch drives every read.** The phone only answers, and says "I have
  changes" when asked. Nothing is pushed unsolicited.
- **Version stamps are Unix seconds.** Not Garmin's 1989 epoch.
- **Courses are "lines"** (type 9), with content in parts: name, points,
  stats. **Saved places are "waypoints"**, one record each.
- **Recorded activities are lines too** (type 2), which the watch describes and
  the phone pulls. The **activity in progress** is an "active line" (type 3).

The FIT-file upload paths (`CREATE_FILE`, `FileSyncService`) are a separate
world. A course pushed as a FIT file does land on the watch, but it belongs to
nobody's collection, so no phone can remove it by this mechanism.

---

## Captures and tools

| Capture | What it shows | Where |
|---|---|---|
| 2026-08-25 | Explore deleting a course (first sighting of tombstones) | lost with the old machine |
| 2026-10-12 a | Explore session after a long-used watch; test3 deleted | `~/garmin-captures/` (`br2`) |
| 2026-10-12 b | Both apps failing on a corrupted watch | `~/garmin-captures/` (`br3`) |
| **2026-10-12 c** | **Freshly factory-reset watch: first sync, course push, course delete, waypoint push, waypoint delete, bulk add, bulk delete, activity recorded with live sync, activity saved** | `~/garmin-captures/2026-10-12-explore-clean/` |

Capture **c** is the reference: every operation, starting from an empty watch.
In capture c's timeline (seconds from the start of the file):

| t (s) | What the user did | Session outcome |
|---|---|---|
| 90 | First Explore sync after pairing | Explore itself ended it FAILED; normal |
| 121 | Sync, nothing to do | OK |
| 180 | Course `testcourse1` created in Explore and synced | OK; watch pulled the line |
| 263 | `testcourse1` deleted in Explore | OK; course gone from watch |
| 330 | Waypoint `test way point 1` created and synced | OK |
| 435 | Waypoint deleted; 2 courses + 2 waypoints added (`bulk1`–`bulk4`) | OK, all in one session |
| 497 | All four deleted at once | OK |
| 527–558 | Activity running with "sync current activity" on | `core_service` position stream |
| 562 | Activity saved; watch starts a sync itself | OK; activity line pulled |

**The captures are personal data.** They contain GPS positions near the
owner's home. They are kept out of the repository on purpose and must never be
committed, pasted into issues, or uploaded.

**How to make one.** Android Developer options → *Enable Bluetooth HCI snoop
log* → **Enabled** (full, not filtered), then **reboot the phone**. A Bluetooth
toggle is not enough on Android 17. Do the actions, then `adb bugreport`. The
log is `FS/data/misc/bluetooth/logs/btsnoop_hci.log`, and the file before the
last Bluetooth restart is `btsnoop_hci.log.last`. Disable Tracks first
(`adb shell pm disable-user --user 0 com.exercisetracks.app`), so two apps are
not answering the same watch.

**How to read one.**
`python3 mobile/tools/gfdi/decode_capture.py btsnoop_hci.log > dec.txt`
decodes btsnoop → ATT → multi-link → COBS → GFDI → protobuf.
It does not yet reassemble protobuf messages split into chunks
(`protobuf #N, 27B (chunk of 63B)`). Those show as `bytes[…]` or `unsupported
wire type`, and a large `collection_read_response` is the usual victim.

---

## Session flow

Explore sync is `Smart` (the GFDI protobuf envelope, message 5043/5044)
**field 22**. Every message below is one field inside it, and "phone→watch" or
"watch→phone" says who sends it. Requests and responses alternate; nothing
overlaps.

A complete session, as capture c shows it on every successful sync:

| # | Direction | Field | Message | Notes |
|---|---|---|---|---|
| 1 | P→W | 1 | start_sync_request | Or the watch sends field 1 and the phone answers 2 |
| 2 | W→P | 2 | start_sync_response | |
| 3 | W→P | 32 | active_line_digest_write_request | Only if an activity is or was in progress |
| 4 | P→W | 33 | active_line_digest_write_response | Phone may ask for its data, then 28/29 follow |
| 5 | W→P | 4 | collection_list_write_request | Watch: "collections I know you have" |
| 6 | P→W | 5 | collection_list_write_response | **Phone declares its collections and its default** |
| 7 | W→P | 8 | collection_digest_write_request | Watch: collections whose content changed on its side |
| 8 | P→W | 9 | collection_digest_write_response | Phone may ask for one's content, then 22/23 pages |
| 9 | W→P | 14 | waypoint_digest_write_request | Watch's changed waypoints |
| 10 | P→W | 15 | waypoint_digest_write_response | |
| 11 | W→P | 24 | line_digest_write_request | Watch's changed lines (activities, deletions) |
| 12 | P→W | 25 | line_digest_write_response | Phone may ask for a line's data, then 28/29 |
| 13 | W→P | 18 | change_summary_request | "What has changed on your side since transaction N?" |
| 14 | P→W | 19 | change_summary_response | Phone: a later transaction, plus which kinds changed |
| 15 | W→P | 10 | collection_digest_read_request | Only if the phone said collections changed |
| 16 | P→W | 11 | collection_digest_read_response | |
| 17 | W→P / P→W | 6 / 7 | collection_read_request / response | Paged, 2 items a page |
| 18 | W→P / P→W | 16 / 17 | waypoint_digest_read_request / response | Paged, 1 entry a page |
| 19 | W→P / P→W | 12 / 13 | waypoint_read_request / response | One per new waypoint |
| 20 | W→P / P→W | 26 / 27 | line_digest_read_request / response | Paged, 1 entry a page |
| 21 | W→P / P→W | 30 / 31 | line_read_request / response | Per part, points paged 10 at a time |
| 22 | W→P | 3 | sync_finished_notification | `1: 1` OK, `1: 2` FAILED |

Steps 1–12 are the watch telling the phone about **its** side. Steps 13–21 are
the watch reading **the phone's** side. With nothing to change, step 14 says so
and steps 15–21 are skipped.

**Timing.** A session with nothing to do takes about 3 s. Pushing one course
took 12 s, deleting one 10 s, and four adds plus a delete 8 s.

---

## Message reference

Field numbers are protobuf field numbers. `stamp` is always `{1: unix_seconds,
2: derived}`, where `derived` is 0 or 1 and its meaning is unknown. Echo it.
Unknown fields are listed as seen.

### Opening

**start_sync_request (1).** Explore sends:

```
{ 1: sync_type          # 1 = FULL (first sync), 2 = INCREMENTAL (every later one)
  2: 2                  # protocol version
  3: app_uuid[16]       # stable per app install; the watch keys its cursor on it
  4: capabilities       # 29 on the first sync, 31 after
  5: {1: 4, 2: 0}  5: {1: 8, 2: 0}  5: {1: 2, 2: 0} }   # capability details
```

The watch starts sessions too, for example right after an activity is saved. It
then sends field 1 to the phone with `{1: type, 2: 2, 4: 10, 5: {...}, 6: 10}`,
and the phone answers field 2 with `{1: 1, 3: 2, 4: app_uuid}`.

**start_sync_response (2):** `{1: 1 (accepted), 2: {1: unix, 2: n, 3: n, 4: n},
3: 2, 5: 8|10, 6: {1: 2, 2: 0}, 7: 10}`.

### Collections

**collection_list_write_request (4), W→P:**
`{1: {1: [ref…], 2: default_ref}}`, where a ref is `{1: uuid, 2: stamp}`.
On a fresh watch this is **empty**. Later it echoes what the phone declared.

**collection_list_write_response (5), P→W — the phone declares its library:**

```
{ 1: 0
  2: { 1: { 1: collection_uuid, 2: stamp, 3: 0 }   # repeated: every collection the phone has
       2: { 1: default_uuid, 2: {1: 0, 2: 0} } } } # the default: where new items go
```

On the first sync Explore sent only the `2` (default) part, with stamp 0. From
the next session on it sent its collection in `1` too.

**collection_digest_write_request (8), W→P:** `{1: {1: [{1: uuid, 3: 1, 4:
stamp}]}}` lists collections whose content changed on the watch. It is empty
when nothing did.

**collection_digest_write_response (9), P→W:** `{1: 0}`, or `{1: 0, 2: op}` to
pull a collection, where `op = {1: {1: collection_uuid, 2: 2}, 2: offset}`.
The watch then sends pages.

**Collection page (22), W→P:** `{1: more?1:0, 2: {1: collection_uuid, 3: {1:
[item…]}}}`. **Page ack (23), P→W:** `{1: op}` to ask for the next page, with
offset = items received so far, or empty when done.

**collection_digest_read_request (10), W→P:** `{1: from_txn, 2: to_txn, 4: 2}`.
**Response (11):** `{1: 0, 2: {1: [{1: uuid, 2: 0, 3: 1, 4: stamp}]}}`.

**collection_read_request (6), W→P:** `{1: {1: {1: uuid, 2: 2}, 2: offset, 3:
page_size (2)}}`.
**Response (7):** `{1: more?1:0, 2: {1: uuid, 3: {1: [item…]}}, 3:
next_offset (only when more)}`.

**Item:** `{1: item_uuid, 2: stamp, 3: tombstone 0|1, 4: kind}`, where kind
**3 = course (line)** and **0 = waypoint**. The phone serves its **whole**
collection every time, tombstones included. Tombstones are never dropped in
these captures.

### Change summary

**change_summary_request (18), W→P:** `{1: last_txn_seen, 2: 0, 3: 115}`.

**change_summary_response (19), P→W:**
- With changes: `{1: last_txn_seen + 1, 3: 1, 4: 1, 5: 1}`. Fields 3/4/5 say
  collections, waypoints and lines changed; Explore always sets all three.
- With none: `{1: last_txn_seen}`.

The watch then asks for the range `from = last_seen, to = new` in each read
request (10/16/26 carry `1: from, 2: to`). Explore increments by exactly one per
session with changes.

### Digests the phone serves

**waypoint_digest_read_request (16):** `{1: from, 2: to, 3: offset?, 4: 1}`.
**Response (17):** `{1: more?1:0, 2: {1: {1: wp_uuid, 2: 0, 3: stamp}}, 3:
next_offset?}`, **one entry per page**, or `{1: 0, 2: <empty>}` when there are none.

**line_digest_read_request (26):** `{1: from, 2: to, 3: offset?, 4: 1, 5: 115}`.
**Response (27):** one line reference per page:

```
{ 1: line_uuid, 2: line_type (9 = course), 3: 0,
  4: stamp(part 1), 5: stamp(part 2), 6: stamp(part 4), 7: stamp(part 8), 8: stamp(part 16) }
```

It is empty (`{1: 0, 2: <empty>}`) once nothing is pending. After a delete,
Explore's line digest no longer lists the deleted course.

### Digests the watch writes

**line_digest_write_request (24), W→P:** `{1: {1: [line_ref…]}, 2: 115}`.
These are the lines that changed on the watch since this app's cursor: new
recorded activities, and **lines deleted on the watch**, which carry `3: 1`
(measured: every course deleted by hand appeared with `3: 1`, and its summary
came back nameless). A FULL session lists every line.

**line_digest_write_response (25), P→W:** `{1: 0}`, or `{1: 0, 2: {1: uuid, 2:
part_mask, 3: 0, 5: 115}}` to ask for a line's data. The watch then sends
`line_data_ready_request` (28) pages, and the phone answers
`line_data_ready_response` (29) with the next op, or empty to stop.

**waypoint_digest_write_request (14):** `{1: <waypoints>, 2: 1}`.
**Response (15):** `{1: 0}`.

---

## Lines (courses, activities)

A line has a UUID, a type, and parts addressed by a bit:

| Part bit | Field in the line | Content |
|---|---|---|
| 1 | 4 | **Summary:** `{1: stamp, 2: name, 3: created_unix}` |
| 2 | 8 | **Points**, paged (see below) |
| 4 | 5 | **Stats:** `{1: stamp, 2: f32 distance_m, 3: f32 ?, 4: f32 ?, 11: f32 max_elev_m, 12: f32 min_elev_m}` |
| 8 | 6 | `{1: stamp, 2: 9}` (type echo?) |
| 16 | 7 | `{1: stamp, 2: 0, 3: 0}` |

Line types: **9 = course**, **2 = recorded activity**, **3 = active (in
progress)**.

**line_read_request (30), W→P:** `{1: {1: uuid, 2: part_bit, 3: offset, 4:
count (10), 5: 115}}`. For the course push in capture c, the watch asked for
part 1, then part 2 at offsets 0/10/20/30, then parts 4, 8 and 16.

**line_read_response (31), P→W:** `{1: more?1:0, 2: {1: uuid, 2: type, 3: 0,
<part field>: {...}}, 3: next_offset (when more)}`.

**Points part (field 8).** These are parallel arrays, one entry per point:

| Field | Type | Meaning |
|---|---|---|
| 1 | stamp | |
| 2 | fixed64 | **Position:** high 32 bits = longitude, low 32 = latitude, both semicircles (signed int32 × 180 / 2³¹) |
| 3 | fixed32 | Elevation in metres, IEEE float |
| 4 | varint | Cumulative distance in metres (integer) |
| 6 | bytes | Empty in courses |
| 9 | varint | Offset of this page's first point |
| 10, 11, 12 | fixed64, f32, string | **Course points:** position, elevation, name ("Start Point 1", "End Point 2"); on the first page only |
| 13–25 | various | Course-point attributes, first page only (types, distances); not yet decoded |

Explore sends every vertex **twice**, apparently as start and end of
consecutive segments. Whether a single copy works is untested.

Explore writes repeated fields **unpacked** (one tag per value). The watch
writes them **packed** (`bytes[80]` of positions, and so on) in
`line_data_ready_request`. A reader must accept both.

---

## Saved places (waypoints)

**waypoint_read_request (12), W→P:** `{1: {1: {1: wp_uuid, 2: 1}}}`.

**waypoint_read_response (13), P→W:**

```
{ 1: 0
  2: { 1: wp_uuid
       2: { 1: stamp
            2: fixed64 position      # as for line points
            3: name                  # "test way point 1"
            4: created_unix
            5: f32 elevation_m
            6: 8284                  # symbol? (icon id)
            7: 0
            8: 10                    # colour?
            9: 1 } } }
```

---

## Operations, step by step

### Put a course on the watch (newer protocol)

1. Generate a random 16-byte UUID for the course, and keep it: it is the
   course's identity on this watch from now on.
2. Add the item `{uuid, stamp now, tombstone 0, kind 3}` to the phone's collection.
3. Add the line reference (type 9, a stamp for each part) to the line digest.
4. When the watch asks for a change summary, answer `last + 1` with all flags.
5. Serve the collection, then the line digest, then every `line_read_request`
   for parts 1, 2 (paged), 4, 8 and 16.
6. The course appears on the watch at the end of the session.

### Take a course off

1. Set its item to `tombstone = 1` with a **fresh** stamp. Keep it in the collection.
2. Drop the line from the line digest.
3. Announce a change. The watch reads the collection, sees the tombstone, and
   removes the course. It reads no line data.

### Saved places

These follow the same steps with kind 0, the waypoint digest and
`waypoint_read`. Removal is a tombstone on the item.

### Several at once

Everything changed since the watch's last transaction goes in one session with
one transaction increment (capture c, t = 435 and 497).

### Something deleted on the watch itself

Measured against Tracks on a factory-reset fēnix 6X, 2026-10-13. How the watch
says so depends on what was deleted and what kind of session follows.

| Deleted | Incremental session (`1: 2`) | FULL session (`1: 1`) |
|---|---|---|
| Saved place | Once, in the **first** session after: a waypoint digest write (14) entry with `2: 1`, stamped at the minute of deletion. Never repeated. | Left out of the waypoint digest writes, which list every place the watch holds. |
| Course | **Nothing at all.** | Its item comes back tombstoned when the phone pulls the watch's side of the collection (msgs 8/9/22/23), and the line digest write lists it with `3: 1`. |

Two consequences shape how a phone must behave:

- **Announce changes only in a FULL session.** Announcing makes the watch re-read
  the whole collection. If a deleted course is still live in it, the watch puts
  it straight back. In incremental sessions that is exactly what happened:
  test1 was deleted, then reappeared with the next send.
  Tracks starts every session FULL. When the watch starts an incremental one
  itself, Tracks answers "nothing to change" and starts a FULL session as soon
  as it ends.
- **Agree, don't argue.** The phone tombstones its own item, keeps the course
  or place, and marks it off the watch. It is not re-sent unless the person
  asks. A phone that kept serving such an item live left the watch failing
  every session, for Explore as well. That happened twice on 2026-10-12, and
  each time only a factory reset recovered it.

Omission from a FULL listing counts only when the watch lists the phone's
collection in its collection list write (4). A watch that does not list it
either never took the collection up or has been reset. In that case Tracks
re-sends everything into the same collection.

**Never treat "transaction 0" as a reset.** Every FULL session asks from 0.
Reading it as a reset replaced the collection every time and stranded the old
ones on the watch.

### What the watch offers, and what the phone must take

Explore takes everything, and so does Tracks:
- **The in-progress line (32):** the 33 reply asks for part 2. Read part 2,
  then part 16, then send an empty reply.
- **Recorded lines (type 2) in the line digest (24):** read part 2 page by page,
  then parts 1, 4 and 16, then send an empty reply.
- **Course lines (type 9):** answered `{1: 0}`, not read.
- **Collections changed on the watch's side (8):** each one is pulled. The
  `{1: 65}` stamp seen there is normal once places have been added.
- **The collection digest read (10) is paged.** Field 3 is an offset and field
  4 the page size. Ignoring the offset sent a watch round 240 times.

### Things that must not reach a Garmin as files

Course files (`GARMIN/Courses`) and `Locations.fit` put a second copy of
everything on the watch. The watch names the copy "test1 1", and it belongs to
no collection, so nothing can take it off again.

The watch also rewrites its own saved-places file after every change and
announces it. Tracks reads that file on the phone and adds only places made on
the watch. It skips any position where Tracks has ever put a place
(`WatchLibrary.sentPlaces`) and any position where Tracks already has one.

### Read a recorded activity

The watch announces the new line (type 2) in its line digest write. The phone
answers with a data op (part 2), and the watch sends `line_data_ready_request`
pages of packed points (positions, elevations, times, and a lap-letter string).
The phone answers each page with the next op.

---

## The live activity

With "sync current activity" on, the watch sends **`core_service` (Smart field
13) message 7** about once a second while recording:

```
{ 1: { 1: { 1: lat_u32, 2: lon_u32 }   # lat ≈ value × 180 / 2^32 (degrees); lon's sign encoding not yet confirmed
       2: f32 altitude_m
       3: garmin_timestamp              # seconds since 1989-12-31
       6: 2 } }
```

Separately, each Explore session opens with
**active_line_digest_write_request (32)** describing the in-progress line
(type 3). Explore asks for its parts, as for any line. That is how Explore
shows the track so far.

---

## Failure: `SYNC_FINISHED_FAILED`

`sync_finished_notification {1: 2}` means the session failed and **the watch's
cursor did not advance**. The same changes are offered again next time.

Measured on 2026-10-12: after courses were deleted by hand on a watch that had
been through many confused sessions (duplicated courses, a phone-less default
collection), **every** session failed for **both** Tracks and Explore. It
failed whether or not the phone had anything to say. A factory reset cleared it.
Two separate phones and protocols failing identically pointed at the watch's
state, not the messages. Explore also ends sessions FAILED itself sometimes
(its first sync after pairing did).

That state was caused by deleting things by hand on the watch while the phone
kept serving them live. See "Something deleted on the watch itself" above. Item
count is not a cause: 150 live items and 430 items in all in one collection
synced fine.

Practical rules:
- Never treat a session as having applied anything unless it finished `1: 1`.
- Surface repeated failures to the person, instead of waiting forever.

---

## What Tracks got wrong, and why

So the next attempt does not repeat these:

- **Pushed courses as FIT files.** `FileSyncService` accepted them and kept
  nothing, which showed up as "sent" courses that were not on the watch.
  Legacy `CREATE_FILE` does work, but such a course is in no phone collection,
  so it can never be removed over Bluetooth. The fix is the newer protocol, as
  Explore does it.
- **Served a line digest with `deleted = true`** instead of tombstoning a
  collection item. The watch ignores that.
- **Used Garmin-epoch stamps.** They are about 20 years older than the watch's
  own, so the watch's copy always wins.
- **Answered the collection list with a bare status.** That declares no
  collections, so the watch never reads any.
- **Tried to remove items in a collection the phone did not own** (the watch's
  default, or Explore's). Only the owner's tombstones apply.
- **Concluded "on the watch" from a finished transfer.** Only the watch's own
  listing (the collection, or its line digest) says that.

---

## Open questions

- What `stamp.2` ("derived") means; Explore sets both 0 and 1.
- Line reference field 3 when the phone serves it (always 0 from Explore).
- The second and third float in line stats, and course-point fields 13–25.
- Whether a course needs its vertices duplicated.
- Whether a phone may declare more than one collection, and what "default"
  changes beyond where new items go.
- How the watch reports a waypoint deleted on the watch.
- Longitude encoding in the `core_service` live position.
- What `5: 115` is (constant in every read; likely a format or schema version).

---

## Captures still wanted

The aim is to leave Gadgetbridge completely, so the project's licence is its
own to choose: new protocol code is written from captures like these, never
from Gadgetbridge's source or schema. Each of these is a capture of
Garmin Connect or Explore doing one thing, from a known state, with the time
of each action written down:

1. **Pairing from scratch**, including bonding and the first GFDI handshake
   (factory-reset watch → pair in Connect).
2. Explore: **rename** a course; **edit** a course's line; a course **deleted
   on the watch** followed by an Explore sync.
3. Explore: a **waypoint deleted on the watch**.
4. Connect: **workout** and **training-plan** push. Compare with
   `docs/garmin-ble-protocol.md`'s NewFiles findings.
5. Connect: **activity, health and sleep download**, the file-sync listing and
   flags (FileSyncService), end to end.
6. Connect: **weather**, **notifications** with replies and actions,
   **find my phone**, **music controls**, **calendar**.
7. Connect: **settings** changes (user profile, Wi-Fi, auto-upload).
8. Connect: **Connect IQ app** install and uninstall.
9. Connect: **ephemeris/AGPS** and **firmware** checks (the HTTP proxy).
10. Connect: **LiveTrack** and incident detection, if enabled.
