# Garmin BLE protocol

Reverse-engineering notes for the watch link. Everything here was measured
against a **fenix 6X Pro Solar (SW 2802)** on 2026-08-25, not inferred from
Gadgetbridge's source — where the two disagree, this file is what the hardware
actually did.

## Capturing watch traffic

Tracks logs its own GFDI frames, so for our own traffic you only need logcat.
For Garmin's apps you need the HCI snoop log.

1. Developer options → **Enable Bluetooth HCI snoop log** (Full, not Filtered).
2. **Reboot the phone.** A Bluetooth off/on cycle is *not* enough on Android 17
   — without a reboot you get only `btsnooz_hci.log`, a ~60-packet ring buffer
   with no useful payload.
3. Force-stop every app that holds the watch except the one under test. Tracks,
   Garmin Connect and Garmin Explore all fight for the link.
4. Reproduce the action, then `adb bugreport out.zip` straight away. The log is
   at `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (plus `.last`, the previous
   rotation). The ring holds ~3 MB, which a full Explore sync overruns in about
   a minute — keep the captured action short or the interesting setup messages
   scroll out.

Decode with:

```
python3 mobile/tools/gfdi/decode_capture.py btsnoop_hci.log > transcript.txt   # raw capture
adb logcat -d | python3 mobile/tools/gfdi/decode_log.py                        # our own traffic
```

`decode_capture.py` goes btsnoop → ATT → Garmin multi-link → COBS → GFDI →
protobuf, and dumps protobuf fields generically so messages absent from our
`.proto` files still decode. Sanity-check it against our own logcat on the same
session before trusting it on anything else.

**COBS framing gotcha**: Garmin frames as `00 <cobs> 00`, so consecutive
messages *share* a delimiter — the zero that ends one begins the next. Consume
up to but not including the closing zero. Getting this wrong silently drops one
whole direction of the conversation, and because a ring buffer always starts
mid-message it fails on every capture.

## Activities can be born "synced": the watch's own Wi-Fi upload (2026-09-30)

The file listing Tracks asks for first excludes files the watch considers
synced (`flags1`/`flags2` = `0xa5a5`). A watch that was ever set up with
Garmin Connect keeps that account and its Wi-Fi networks, and with **Auto
Upload** on it uploads each activity to Garmin the moment it is saved on a
known network — Garmin Connect need not be installed, and no phone is
involved. The activity is then already flagged synced and never appears in
that listing.

Measured, not inferred:

- A run saved at 18:33:08 was missing from the 18:36 unsynced listing.
  17 s after the save, Garmin's cloud pushed an FCM message to the (stopped)
  Garmin Connect app on the phone — what it sends when an activity arrives.
- Two 1-minute recordings made with the phone's Bluetooth **off** were both
  missing from the next unsynced listing — including one with Tracks' Explore
  sync disabled on both sides, which rules Explore out.
- In an unfiltered listing, `File.unk6` (field 6) was `0` for the new
  activity and `1` for every other file written in the same minute; older
  files Tracks keeps re-pulling read `2`. So field 6 looks like the sync
  state, with 0 = synced. One watch, one firmware (fenix 6X Pro Solar,
  SW 28.02); unconfirmed elsewhere.

Tracks copes either way: after the normal listing it runs an unfiltered one
from the page id where the last one ended (`activityRecoveryStartPage`), and
takes any activity it has never downloaded — 3–4 entries per sync.

**Auto Upload can be switched off over Bluetooth** (verified 2026-09-30, twice,
by toggling it on at the watch and back off from the phone): a FIT file of type
`settings` whose `device_settings` carries only field 38
(`wifi_auto_upload_enabled`) = 0, sent by the legacy create-file path. Field 98
is `wifi_enabled`. This is Gadgetbridge's experimental "Send Connection
Settings"; Tracks builds it in `SettingsFit`. Onboarding has a toggle for it,
on by default (`WatchPrivacyPreference`), and while it is on a sync that finds
an activity recorded since the last check and already flagged synced sends the
setting again — the sign that Auto Upload came back on. The watch says nothing back about settings, and Gadgetbridge's
transfer handler reports no progress for a settings file, so success is read
off the transfer going idle with no refusal recorded. What Bluetooth cannot do
is remove the watch from the Garmin account, which revokes the credentials it
would use if Auto Upload were turned back on; that is still the owner's job at
connect.garmin.com.

## Courses live on Explore sync, not the file table

This is the single most important thing to know. Courses are **not** addressed
by `fileIndex`, by FIT file type, or by byte size. `SetFileFlagsMessage` (GFDI
5008) does not manage them — its ERASE bit returns ERROR on this watch.

A course is an `ExploreSyncService` (field 22 of `Smart`) object identified by a
16-byte UUID, carried in a digest of `LineReference`s with
`LINE_TYPE_COURSE = 9`.

Session facts that are not guessable:

- **Tracks must initiate.** The watch offers a sync repeatedly while being
  declined, then stops offering forever.
- **`app_uuid`** must be stable and distinct per client — the watch keys its
  per-client cursor on it. Explore uses 16 random bytes, not ASCII.
- **`sync_capabilities`** must be **31**
  (`BASIC|GCJ02|ACTIVE_LINES|TRACKING_EVENT|COURSES|SINGLE_ITEM`). Explore sends
  31; with 10 the watch completes a sync but never asks the phone for anything.
- `StartSyncRequest` carries a repeated field 5 `{1: 4, 2: 0}` that is absent
  from Gadgetbridge's proto.
- FULL vs INCREMENTAL matters: a clean `SYNC_FINISHED_OK` establishes the
  cursor, after which INCREMENTAL returns only changes (236 lines → 2).

## Deleting a course

**The watch pulls; it never accepts a push.** Sending an unsolicited
`LineDigestWriteRequest` is well-formed, gets a GFDI ack, and does nothing —
measured, three variants, course still present afterwards. The watch drives:
it sends `line_digest_read_request`, `collection_read_request`,
`collection_digest_read_request`, `waypoint_digest_read_request`, and the phone
**answers with its library**.

Gadgetbridge's proto stubs `LineDigestReadResponse` as `{status}`. The real
shape is:

```protobuf
message LineDigestReadResponse {
  required ReadStatus status = 1;
  optional LineDigest  digest = 2;   // stubbed out upstream
  optional uint32      unk    = 3;
}
```

Answering with a bare status tells the watch "my library is empty", which is why
it then asks nothing further.

**Deletion is expressed on the collection item, not on the line.** Garmin Explore
never sets `LineReference.deleted` — all 36 line refs in a real capture carried
`deleted = 0`. A collection item is:

```
{ uuid = 1, version_stamp = 2, tombstone = 3, item_kind = 4 }
```

`item_kind` 0 = waypoint, 3 = line/course. A removed course gets
`tombstone = 1` with a fresh version stamp **and** is omitted from the line
digest. Verified by exact correlation: three courses deleted, three items with
`tombstone = 1`, and those same three were the only line-kind items missing from
the digest — the other 41 appeared in both.

So to remove a course: tombstone its collection item and drop it from the digest
you serve. There is no delete message to send.

## Still unknown

- **Training-calendar push over BLE — SOLVED 2026-08-28.** See "The two BLE
  upload paths do not put a file in the same place" below. The file was never
  the problem and neither was the protocol; the *destination* was.

  `GarminDevice.xml` (pull it from `Garmin/GarminDevice.xml` over MTP) groups its
  entries by `<DataType>`, and each one pairs an **output** path with an **input**
  path. `FIT_TYPE_7` has both: `Garmin/Schedule` as `OutputFromUnit` and
  `Garmin/NewFiles` as `InputToUnit`. Every FIT type follows that shape — it is
  published from its own directory and accepted through the NewFiles inbox.

  **So FIT_TYPE_7 is accepted as input, and the original note in this file saying
  so was correct.** A previous edit read the flat list of `<File>` blocks, missed
  the `<DataType>` grouping, saw only `Garmin/Schedule … OutputFromUnit`, and
  "corrected" it to the opposite. That was wrong. Confirmed on 2026-08-28 by
  capturing Garmin Connect building a calendar over BLE: it uploads a
  `FIT_TYPE_7` through `FileSyncService` with an upload request byte-identical in
  shape to ours — same `unk2 = 34`, `unk3 = 0`, `unk4 = 15`, same type name —
  differing only in the client file id and the size.

  So over BLE the watch accepts a `FIT_TYPE_7` through `FileSyncService`,
  answers the transfer-complete, and drops it — verified by reading the
  filesystem after a push: the file is in neither `NewFiles` nor `Schedule`,
  while workouts pushed in the same sync are in `Workouts` under their titles.
  `FIT_TYPE_5` has an inbound handler; `FIT_TYPE_7` appears not to.

  **Settled 2026-08-28: Connect's BLE type-7 upload does build a calendar.**
  Confirmed by the person who took the captures — the first recording of Connect
  BLE traffic produced a calendar on the watch, and that session carried a
  type-7. So the mechanism is real and reachable over BLE. Earlier wording here
  called this unverified; it is no longer.

## What the captures have now been asked, and answered (2026-08-28)

Six archived btsnoop captures were decoded and cross-checked against each other.
Everything below is settled; re-deriving it is wasted effort.

- **The HTTP proxy does not gate a calendar.** Connect answers 48 `http_service`
  proxy requests in a session and Tracks answers none, which made "the watch
  wants its cloud reachable before it accepts a training plan" look plausible.
  It is not: the calendar push is a self-contained window — file list, then
  `FIT_TYPE_41`, then the schedule, then the workouts — and nothing in it waits
  on an HTTP response. The proxy traffic belongs to the activity uploads either
  side of it, and the request closest to the schedule asks for
  `usercontact/contacts/sos`.

- **Service 47 does nothing for us.** Connect sends `47.3 {1:1}` immediately
  before its calendar push and an empty `47.6` 540 ms before the schedule
  upload, which reads exactly like arming something. Tracks now sends both, in
  Connect's order and position (`GarminIntegration.openSchedulePush` /
  `commitSchedulePush`). Measured 2026-08-28: both went out, the push completed
  21 of 21, and the calendar read back byte-identical. Eliminated by test.

  An earlier note here called it eliminated because the 2026-08-25 capture shows
  `47.3 {1:1}` only at t=103 s, long after its calendar push. That reasoning was
  wrong, and the trap is worth remembering: that capture is a ring buffer whose
  first record is the type-7 upload itself, so an earlier `47.3` would simply
  have scrolled out. Absence from a ring buffer is not evidence of absence.

- **`UploadRequest.unk2` is not always 34.** It genuinely varies — Connect sends
  24 for `FIT_TYPE_39` and for an early small type-7, 34 for the type-7 and
  workouts of a calendar push. Every *calendar* schedule in every capture uses
  34, which is what Tracks already sends. Matching, not a difference.

- **Push order already matches.** Connect sends the schedule and then its
  workouts; so does `pushSchedule`. The 2026-08-27 note about workouts arriving
  first describes an experiment, not the current code.

- **Both transports really do send the same bytes.** `/device-sync/schedule-bundle`
  and `/training-plan/sync/schedule-bundle` are two routes onto one
  `_schedule_bundle_for_user`. Worth having checked rather than assumed, because
  the whole "same bytes, different outcome" framing rests on it.

- **The transfer and the deflate are correct end to end.** Not an inference from
  the ack: the watch writes a BLE-pushed workout to `GARMIN/Workouts/<title>.fit`,
  and it can only know the title by parsing the FIT it was handed. The bytes
  arrive intact and get parsed. Every transport-level explanation is spent.

Also closed: Connect's schedule bytes cannot be recovered from these captures.
`dump_transfers.py` fails to reassemble the verbose ones, and the schedule's
file-sync object sits at page 138453 while every listing after the push starts
at 138519, so pagination hides whether the watch rewrote it.

## The burst terminator: understood by the watch, and not the calendar's cause

Garmin Connect answers the last `transfer_complete` of a burst with an **empty**
`TransferCompleteResponse` -- six bytes, `da 02 03 aa 01 00`, against the usual
eight carrying `unk1 = 34`. Seven times out of seven across both captured
calendar pushes, and the watch replies `file_sync_service.20 {1:1}` within
70-90 ms. A bare `20 {1:1}` never appears without one.

Tracks always sent `unk1 = 34`, so the watch never heard a session close and fell
back to an idle timeout -- measured in our own logs at 30.21, 30.87, 30.27 and
30.96 seconds after the last answered transfer.

**Tracks now sends it, and the watch understands it.** Measured 2026-08-28:

```
17:52:12.611  OUTGOING followup ... da0203aa0100     <- empty terminator
17:52:12.714  20: "\b\001"                           <- 20 {1:1}, 103 ms later
```

103 ms against 30 s. Worth keeping on its own merits: it takes a half-minute
stall off the end of every sync.

**It does not build a calendar.** Two syncs pushed all 21 files over
`FileSyncService` with the terminator in place; `Schedule.fit` read back unchanged
both times, and a manual reboot of the watch afterwards changed nothing. So the
session close was not what the FileSyncService path was missing, and the schedule
bundle stays on the legacy NewFiles route
(`WatchManager.SCHEDULE_OVER_FILE_SYNC`, false and one edit from true).

Still open, and it matters for newer watches that may not offer `CREATE_FILE` at
all: what else Connect's FileSyncService push does that ours does not. Untried
from the capture work, in order of cheapness -- `FileRequest.unk2` 24 -> 34
(Connect sends 34, we inherited upstream's 24); a `FIT_TYPE_41` changelog pushed
just before the schedule, which Connect did once; and `UploadRequest.unk2 = 24`,
since both 24 and 34 appear on type-7 uploads and may mark "plan batch" against
"standalone".

## SOLVED: a schedule has to arrive in NewFiles, with its workouts

The training calendar builds over BLE when the schedule and every workout it
names are pushed down the **legacy `CREATE_FILE` path**, together, in one run.
Confirmed on the watch on 2026-08-28 after months of the same bytes being
accepted and silently dropped.

### Why the destination is the whole answer

BLE has two upload paths and they do not put a file in the same place.

| | `FileSyncService` | legacy `CREATE_FILE` |
|---|---|---|
| addressed by | type *name* (`FIT_TYPE_7`) | numeric type/subtype (128/7) |
| lands in | the watch's library, filed at once | `Garmin/NewFiles`, per `GarminDevice.xml` |
| a workout becomes | `GARMIN/Workouts/<title>.fit` | an entry in the importer's next batch |

`Garmin/NewFiles` is the same inbox `garmin-sync` writes to over the cable, and
the watch's own importer scans it **as a batch**. That matters because of the
rule measured on USB on 2026-08-27: a schedule entry binds only to a workout
delivered *in the same batch*, never to one the watch is already holding — a
schedule naming sixteen workouts beside two workout files produced exactly two
calendar entries.

Over `FileSyncService` there is no batch to be in. Each file is an independent
object the watch files the moment it arrives, so by the time the schedule is
processed every workout it names is already a library file, not a batch-mate.
Nothing resolves, and an unresolved schedule is dropped without a word. That is
why the file turned up in neither `NewFiles` nor `Schedule`: it was imported,
resolved to nothing, and discarded.

### Why this looked like a dead end for so long

The legacy path *had* been tried, and AGENTS.md recorded it as accepted and
acknowledged and never becoming a calendar. That was true and it was a red
herring: those attempts pushed the schedule **on its own**, and the batch rule
had not been measured yet. The two facts were each correct and nobody had put
them together. Sending the whole bundle down the legacy path had never been
tried.

### What the watch does on import, and what it costs

**The watch reboots.** Ingesting a NewFiles batch restarts it, which drops the
BLE link mid-run. The phone sees a disconnect and reports the sync as failed
even though the push is exactly what succeeded. Two consequences worth knowing:

- A schedule push that "fails" with a disconnect immediately afterwards has very
  likely worked. Check the watch, not the notification.
- The read-back cannot run in the same sync, because the link the schedule
  arrived on is gone by the time the calendar exists. Confirmation needs the
  *next* sync.

Neither is a reason to go back to `FileSyncService`, which loses nothing except
speed and gains nothing except a calendar that never appears.

### Still to do

- Stop reporting the sync as failed when the watch drops the link straight after
  a schedule push. It is the expected outcome of a successful import.
- Re-confirm by read-back on a later sync. The run that proved this was verified
  by looking at the watch; the logcat buffer had reverted from 64 MiB to its
  512 KiB default and rotated past the sync, and the disconnect stopped the
  instrument from reading the calendar back.
- Decide what `FileSyncService` is still for on the push side. Workouts and
  courses do import from it, but nothing needs two paths.

## The upload cycle was inverted (fixed 2026-08-28)

Connect answers a file's `TransferComplete` **before** asking to send the next
file. Nineteen consecutive uploads in the capture that built a calendar, without
one exception:

```
0.960  transfer_complete_response
1.051  upload_request
1.698  transfer_complete_response
1.803  upload_request      ...
```

Tracks did the exact reverse on every cycle. From a push on 2026-08-28:

```
13:47:27.925  Requesting upload of WKT_20260829_522
13:47:27.935  Transfer complete on handle 4056     <- previous file
13:47:27.937  OUTGOING upload request              <- sent FIRST
13:47:27.941  OUTGOING followup (the answer)       <- sent SECOND
```

The watch tolerated four files that way and then stopped responding to the fifth
upload request entirely — **five minutes of silence**, ending only when the push
timed out and fell back to the legacy path. The link was alive throughout; it was
delivering phone notifications in the middle of it.

The cause was that an upload reported success when its *channel closed*, which is
about a tenth of a second before the watch sends `TransferComplete`. The next
file therefore always started before the previous one had been acknowledged.

The fix gates completion on both halves: `FileSyncServiceHandler` keeps a waiter
per handle, `handleTransferComplete` queues the handle rather than releasing it,
and `GarminSupport` drains the queue immediately after `sendOutgoingMessage
("followup", …)` — the point where the answer actually reaches the wire. Firing
the waiter inside the handler would not do: that method only *builds* the
response, so the release would still beat the send. A ten-second guard covers a
watch that never sends the message at all, since the bytes are already on the
device by then.

**Measured after the fix, same 22-file push:**

| | before | after |
|---|---|---|
| schedule to last workout | 5 min 22 s | **20.1 s** |
| longest single file | 5 min 0 s (timeout) | 1.4 s |
| files falling back to legacy | 1 | **0** |

Garmin Connect's own calendar push, for comparison, ran 21.0 s for 19 files. The
two are now the same shape on the wire.

**It did not fix the calendar.** The reasoning was that the schedule's own
`TransferComplete` was being answered after the next upload request had gone out,
so the watch might be reading the answer as "the client confirms this file" and
orphaning every schedule. Tested on 2026-08-28 with the ordering corrected and
the calendar read back over BLE in the same run: the watch's `Schedule.fit` came
back byte-identical to the copy taken before the fix — same 567 bytes, same
object id, still workouts 359-374, none of the 517-537 just pushed.

So this is a real bug, worth having fixed on its own merits, and it is not the
one. Keep the fix; drop the theory.

**What it does establish** is worth as much: Tracks and Garmin Connect are now
the same shape on the wire — same message sequence, same ordering, same pace,
every file over `FileSyncService`. The watch is handed a valid FIT_TYPE_7 in the
same way its own app hands one over, accepts it, and does not rewrite its
calendar. Whatever is left is not in the transport.

### The working push is the *minimal* one

The 2026-08-25 capture is a confirmed positive control: it built a calendar. What
it contains is the useful part. Across the whole push — the 578-byte schedule and
then eighteen workouts, t=1.0 to t=22.0 — the phone sends exactly two kinds of
message, nineteen times each:

```
file_sync_service.3   (upload_request)
file_sync_service.21  (transfer_complete_response)
```

Nothing else. No service 47, no `core_service`, no `http_service` until t=25.6,
after the push has finished and the downloads have started. No calendar_service.

That is worth stating plainly because it inverts how this problem has been
approached. The instinct has been to look for something Connect does that Tracks
omits, and the confirmed-working session is the one with the *fewest* moving
parts — and it is, message for message, what Tracks already does: schedule first,
`unk2=34`, one upload request per file, an answer to every transfer-complete.

So the difference is not in the surrounding session. Every remaining candidate is
in the file, the transfer bytes, or the state the watch is in when it arrives.

## Reading the watch's calendar back over BLE

`Schedule.fit` is now pullable, so the oracle no longer costs a USB cycle — which
mattered, because plugging the watch in drops the BLE link being tested.

`FILETYPE.SCHEDULES` joins courses and locations in the one-shot widening behind
`setPullSavedItems`, and arrives as `PulledFileKind.SCHEDULE`. That kind is
handled unlike any other: it is **never uploaded** — a training plan sent to the
activity ingest would be filed as a workout that never happened — and **never
confirmed**, because telling the watch it may reclaim its schedule would delete
the calendar the read exists to measure. `markSynced` already refuses it for the
same reason, testing `pull` rather than `shouldPull`.

What comes back is logged as `watch calendar: <name>, <n> bytes at <path>`. The
size is usually the whole answer — 110 bytes is an empty calendar — and the file
stays on the phone for `adb pull` and `mobile/tools/gfdi/fitdump.py` when the
question is "not empty, but is it *ours*".

**This replaces the MTP ritual, so stop reaching for it.** Older notes here end
every BLE experiment with "plug in and read `GARMIN/Schedule/Schedule.fit`" —
unplug the watch, sync, plug it back in, kill KDE's `kiod6`, mount over MTP. That
was never a good oracle: plugging the watch in drops the BLE link, so the
verification step destroys the conditions of the test and invites exactly the
confusion that once had a USB cycle credited to BLE. A sync now ends by reading
the calendar back over the same link that pushed it, with the watch untouched.

## Push order is not the answer either (tested 2026-08-27)

The obvious inference from the binding rule — send the workouts first so the
schedule has something to resolve against — was built, installed and run. It
does not work.

A clean BLE sync delivered all sixteen workouts and *then* the schedule, every
file accepted with status 0:

```
15:06:45 pushing SCHEDULE.fit as FIT_TYPE_7 over file-sync (517 bytes)
15:06:46 watch accepted SCHEDULE.fit as FIT_TYPE_7 (517 bytes)
15:06:46 pushed a training schedule covering 16 workout(s), with 16 of 16 workout file(s)
```

The watch changed nothing. `Schedule.fit` still held the entry Garmin Connect
had put there the day before, and `Workouts/Schedule/` still held only Connect's
dated file. Order was never the problem.

Also disproved along the way: **transfer slots are not leaked.** The watch closes
each channel itself and the close response arrives ~90 ms *before* the matching
`TransferComplete`, so `handleByService` frees the slot without us doing
anything. A fix that hooked `TransferComplete` to release slots early is
therefore dead code and was reverted. The ~30s alternating stall remains
unexplained; it correlates with `UploadResponse.unk8` being absent and nothing
else.

## The 30-second upload stall: the client must close the channel

Solved 2026-08-28, and it was not congestion, a slot leak, or the file. Measured
on one stalled upload:

```
12:47:10.884  watch is ready, sending 540 bytes
12:47:11.159  all fragments acknowledged          <- 275 ms
12:47:11.165  Sent ACK packet
              ... exactly 30.0 s of silence, nothing on the wire ...
12:47:41.128  Closing MLR communicator
```

The file was delivered and acked in 275 ms. The watch then waited exactly thirty
seconds and reclaimed the channel itself. **It expects the client to close a
file-transfer channel when it has finished sending**, and nothing did, so every
second upload paid a 30 s timeout — half the wall-clock time of every sync.

The fix is `ServiceWriter.closeWhenDrained()`, called after the last chunk is
written. It must wait for the *drain*, not just the last write: the reliable
layer may still be retransmitting, and closing under that truncates the file.
`MlrCommunicator` therefore fires a one-shot listener when `lastRcvAck ==
nextSendSeq` and its fragment queue is empty.

Result on a 21-workout sync: mean 1.2 s per file (was ~16 s), longest 3.2 s,
nothing over 20 s, whole sync under a minute instead of five.

Two wrong turns worth not repeating. The extra fields on `TransferComplete` are
not a verdict on the transfer — they alternate by handle parity. And
`TransferComplete` is useless as a close trigger: it arrives *after* the watch's
own close, so a fix hooked to it can never fire.

## The schedule binds only to workouts that arrive with it

Measured on 2026-08-27, and it is the whole reason a calendar stays empty even
when every file is correct. A schedule naming sixteen workouts went into
`GARMIN/NewFiles` beside **two** workout files. Exactly two calendar entries
appeared — the two from that batch. The other fourteen were already sitting in
`GARMIN/Workouts` from earlier syncs and resolved to nothing.

A schedule entry binds to a workout file **delivered in the same batch**, not to
one the watch is already holding. Both transports must therefore send every
workout the schedule names, every sync, however many the watch already has —
`/device-sync/schedule-bundle` for BLE and `/training-plan/sync/schedule-bundle`
for the cable. This is also why Garmin Connect re-uploads all eighteen workouts
every sync when only one has changed; it looked wasteful until this was measured.

## Workout files cannot be removed over Bluetooth, and they add up

Reported 2026-09-30: a sync fails because the number of workouts on the watch
exceeds its maximum. Nothing the phone does ever takes a workout off the watch.
Every calendar push sends the next week's workouts as new files, a regenerated
plan names them with new ids (`WKT_<date>_<id>.fit`), and the old copies stay.

What was checked, so nobody has to again:

- `SetFileFlagsMessage` DELETE errored on every watch tried and ERASE returns
  ERROR on the fenix 6X (see "Courses live on Explore sync" above). ARCHIVE is
  accepted, but for a course that did not make the item leave the list, and
  nobody has measured what it does to a workout.
- The protobuf `FileSyncService` has no delete message. `markSynced` only tells
  the watch to stop *offering* a file for upload.
- The backend has a delete list for workouts (`/device-sync/delete-list`), and
  `TracksClient.deleteList()` exists, but the phone has nothing to act on it with.

So the phone limits what it adds instead: a calendar carries at most
`SCHEDULE_DAYS_AHEAD` (4, and the server's `_SYNC_DAYS_AHEAD` to match) days and
leaves out rest days and finished workouts (`workoutsForSchedule`). That slows
the growth. It was 7 until the same day.

Unverified: the owner believes the watch drops workouts that are absent from a
later push. Nothing here confirms it, and no measurement of one has been made —
if it is true the old files age out on their own, and if a watch still refuses
a push it needs its old workouts cleared once, by cable or Garmin Connect.

## What the watch writes back

After ingesting a schedule the watch rewrites `Garmin/Schedule/Schedule.fit` in
its own format — little-endian, profile 21126, `file_id.serial_number` set to its
own unit id, `time_created` left `0xFFFFFFFF`. Useful because it shows which of
our values it keeps and which it normalises:

- `schedule.serial_number` / `time_created` — **kept verbatim**, confirming the
  foreign key into each workout's `file_id` is exactly right.
- `mesg 137` `message_index` — rewritten from our `1` to **0**, and every
  `schedule` field 7 rewritten from `1` to **0** to match. Field 7 is therefore
  the pointer to the training-plan message, confirmed by the watch's own
  normalisation rather than inferred.
- `schedule` field 9 — set to `255`, a field we do not write at all.

An empty calendar has `Schedule.fit` at 110 bytes with no `schedule` messages;
a populated one is 567 bytes for sixteen entries. Reading that file over MTP is
the fastest way to tell whether an import actually landed.

- Two undocumented `FileSyncService` fields, 5 and 20, seen on this watch.

## Pushing files: there are TWO upload paths, and they are not equivalent

Tracks uploads via the **legacy GFDI path**: `CREATE_FILE` (5005) →
`UPLOAD_REQUEST` (5003) → `FILE_TRANSFER_DATA` (5004). Garmin Connect does not
use it at all. On a fenix 6X Pro Solar, Connect uploads through the **protobuf
`FileSyncService`**, and the difference is why the training calendar never
worked.

Captured from Connect pushing a training calendar (2026-08-25): one
`FIT_TYPE_7` (schedule, 578 B), eighteen `FIT_TYPE_5` (workouts) and one
`FIT_TYPE_39`, all through this exchange:

```
phone -> watch   FileSyncService field 3  (upload request, undocumented)
  1 { File
      1 { id1 = 0xFFFFFFFFFFFFFFFF   // all-ones = "new file"
          id2 = <client-assigned id> }
      2 { 1 = 0, 2 = "FIT_TYPE_7" }  // type by NAME, not numeric type/subtype
      3 = 578 }                      // size in bytes
  2 = 34   3 = 0   4 = 15            // constants, purpose unknown

watch -> phone   FileSyncService field 4  (upload response, undocumented)
  1 = 0        // status, 0 = accepted
  2 = 0  3 = 0
  4 = 2577     // transfer handle
  8 = 10
```

The bytes then stream over a **separate multi-link file-transfer service**
(handle from the response), not as GFDI messages. Those channels carry
compressed data and will decode as garbage if you feed them to a GFDI parser —
`decode_capture.py` currently does exactly that, so ignore "UNKNOWN" frames with
absurd lengths on non-GFDI handles.

Two more undocumented `FileSyncService` fields ride alongside: phone→watch 21
(×22) and watch→phone 5 (×25), paired the same way as 3/4.

**Why this matters:** the watch accepts a type-7 schedule over the legacy path,
acknowledges every byte, and never builds a calendar. The file was always
correct — the same bytes work over USB via `GARMIN/NewFiles`. The delivery
mechanism was wrong. Workouts and courses happen to import from the legacy path,
which is what made this look like a file-format problem for so long.

## The phone is now the encoder (2026-08-29)

The watch push no longer asks the server for bytes. `WatchManager.pushSchedule`
builds the workout files and `Schedule.fit` itself, from the cached plan, using
`core/.../fit/` — a port of `app/calculators/fit_workout.py`.

**It always encodes locally, even with a perfectly good server.** That is not
laziness about the fallback path; preferring the server would break the thing
that keeps a sync quiet. A schedule matches an entry to a workout by
`(serial, time_created)`, and the two sides persist that timestamp in different
places — the server in `planned_workouts.fit_time_created`, the phone in its own
preferences. Alternating between the encoders produces different bytes for an
unchanged plan, so the fingerprint differs, so the bundle is resent, so **the
watch reboots every time signal comes and goes**. One encoder is the whole
point.

The port is held to **byte-for-byte equality** with the backend, checked by
`WorkoutFitTest` against goldens regenerated with `scripts/gen_fit_goldens.py`.
That bar is deliberate: a rejected FIT file says nothing about why, and every
failure on this project has been a field nobody was looking at. A
decode-and-check would pass on a file missing `weight_display_unit` or carrying
`target_type` on a yoga step, both silent, both animation-killing.

Three things measured while porting that a reading of the FIT spec gets wrong:

- **Headers differ per file type.** Workouts are protocol 2.0 / profile 21208;
  `Schedule.fit` is 1.0 / 21213 with big-endian records. The `FitFile` writer in
  `device-garmin` hardcodes 1.0 / 21117, little-endian, profiled messages only —
  so it cannot write either, which is why `core/.../fit/FitWriter.kt` exists.
- **Definition reuse and wrap.** The reference encoder reuses any definition
  still live in the local table, and allocates round-robin across 16 slots,
  evicting as it goes. The seventeenth distinct definition lands in slot 0.
- **`sub_sport` for HIIT is 70.** Not where the enum order suggests.

The phone's FIT profile and the backend's also disagree about what fields 9 and
10 of the workout message mean. Neither is authoritative — what matters is the
field numbers and base types the watch already accepts, which is what the
goldens pin.

### Workouts written on the phone

A workout authored offline carries a negative local id until its create reaches
the server. Both encoders clamp a serial with `maxOf(1, id)`, so those ids are
folded into a range above 1_000_000_000 first — otherwise every offline-authored
workout arrives as serial 1 and the calendar binds several entries to one file.
See `watchSerial` in `SchedulePushPlan.kt`.

## TODO: move the whole watch contract to Garmin's newer model

Both of Garmin's own apps talk to this watch in the **newer protobuf world** and
neither uses the legacy GFDI file path Tracks was built on:

- **Garmin Explore** moves courses and waypoints over `ExploreSyncService` as
  line/collection digests, addressed by 16-byte UUIDs.
- **Garmin Connect** moves files over `FileSyncService`, addressed by 128-bit
  file ids and a type *name*, with bytes on a separate multi-link channel.

Tracks currently addresses everything by legacy `fileIndex`, numeric
type/subtype and `CREATE_FILE`. That is why two features failed for months in
ways that looked like file-format bugs: the watch keeps the old doors open and
answers politely, but the newer content types are only wired behind the new
ones.

The server contract inherits the same assumption. `/device-sync/upload-list`
describes a pushable file by the **folder a cable sync would write it to**
(`GARMIN/NewFiles`, `GARMIN/Courses`) — see `GarminFileTypes` for why that is
already the wrong shape for BLE, which has no filesystem. Courses additionally
need a stable identity that survives round-tripping, because the watch names
them by UUID and the phone names them by filename, and nothing maps between the
two today.

Worth doing as one piece rather than patching each feature:

1. Server describes a pushable item by **type name** (`FIT_TYPE_7`) and a
   **stable id**, not by folder and filename.
2. Phone keeps a UUID map for Explore-sync objects so a course can be deleted,
   renamed or re-pushed by identity.
3. Legacy `CREATE_FILE` upload stays only as a fallback for watches that do not
   negotiate the newer sync.
