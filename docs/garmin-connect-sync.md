<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Garmin Connect sync — workouts, calendars and the rest

What Garmin Connect does over Bluetooth when it puts workouts and training
calendars on a watch, from a capture of Connect and a factory-reset fēnix 6X Pro
Solar (2026-10-12). This is the companion to
[garmin-explore-sync.md](garmin-explore-sync.md), which covers courses and saved
places. As there, everything here was observed on the wire, and nothing is
taken from Gadgetbridge.

## Capture

`~/garmin-captures/2026-10-12-connect/`, kept out of the repository. It holds
personal data and, because a Garmin Wallet card was set up during it,
possibly payment-provisioning traffic: **never share it, and do not analyse the
wallet exchange**.

The Bluetooth log rotates on every Bluetooth restart, and only the current file
and the one before it survive. The large Connect IQ transfers (Spotify, a watch
face) forced a rotation, so **the Connect IQ installs and the Wi-Fi setup are
not in this capture**. What survives (`btsnoop_hci.log.last`, about 18 minutes)
holds:
- workouts sent one at a time
- calendar events
- an MTB training plan, synced and then deleted
- settings changes
- weather, music controls and find-my-phone

To decode it, the handles must be named, because the capture includes other
Bluetooth devices:

```
python3 mobile/tools/gfdi/decode_capture.py btsnoop_hci.log.last 0x18,0x1b,0x1d,0x20,0x22,0x25
```

`dump_transfers.py` takes the same handle list but cannot yet follow this
capture's transfer channels (most streams fail to decompress), so the file
bodies Connect sent are not extracted. Fixing that is the next tooling job.

## Workouts and the training calendar

**Connect uses FileSyncService (`Smart` field 43) for all of it, not the legacy
`CREATE_FILE`/NewFiles path.** One upload is:

```
P→W 43.3  upload request  { 1: { 1: file_id{ 1: 0xffff…ffff, 2: n }, 2: { 1: 0, 2: "FIT_TYPE_5" }, 3: size }, 2: 34, 3: 0, 4: 15 }
W→P 43.4  accepted        (bytes then travel on a separate multi-link transfer channel)
W→P 43.5  transfer progress / complete
P→W 43.21 complete ack    { …, 34 }
```

In order, each Connect sync of the calendar was:

1. `FIT_TYPE_41` (~11.6 KB) — sent on every sync. Not yet identified.
2. **`FIT_TYPE_7`, the schedule** — sent **before** the workouts. It was 72 bytes
   with nothing scheduled, 122 bytes with a little, and 622 bytes for the MTB plan.
3. **Every `FIT_TYPE_5` workout the schedule names**, one upload each. That was
   17 for the plan, resent in full even when only one changed. This matches
   [the batch rule](garmin-ble-protocol.md): a schedule binds only to workouts
   that arrive with it.
4. A `FIT_TYPE_39` request from the phone, also on every sync.

The plan's events appeared on the watch, and deleting the plan in Connect
removed them at the next sync. Deletion was simply a new, smaller `FIT_TYPE_7`
(72 bytes) — **the calendar is replaced whole, never edited**. Workouts sent on
their own ("Send to device") went as a single `FIT_TYPE_5` upload each.

Events added to the Connect *calendar* (rather than a training plan) did not
appear on the watch. Connect sent nothing that would carry them.

**Why this matters for Tracks.** Tracks sends its calendar over the legacy
NewFiles path, which works. Earlier notes
concluded that a `FIT_TYPE_7` over FileSyncService is acknowledged and dropped.
Connect's sequence differs from what Tracks tried in two ways, and both are
worth testing:
- the schedule goes **first**
- the workouts follow it in the **same sync**

## Other uploads Connect made

| Type | When | Notes |
|---|---|---|
| `FIT_TYPE_2` (settings), 368 B | after a settings change in Connect | the user profile and device settings travel as a settings FIT file |
| `FIT_TYPE_41`, ~11.6 KB | every sync | unidentified; likely a Garmin-specific device or config file |
| `FIT_TYPE_39` | every sync, requested by the phone | unidentified |
| `FIT_TYPE_32`, `FIT_TYPE_58`, `FIT_TYPE_59` | listed by the watch | monitoring and health files the phone downloads |

## The user profile (2026-10-13)

When a profile value is changed in Connect, Connect uploads a `FIT_TYPE_2`
settings file (368 bytes) over FileSyncService. The file holds four kinds of
record:
- `file_id`: type 2, manufacturer 1, product 65534
- `user_profile` (3): see the table below
- three `unknown_222` records
- `device_settings` (2)
- `ohr_settings` (188)

The `user_profile` fields that matter:

| Field | Meaning | Encoding |
|---|---|---|
| 1 | gender | enum: 0 female, 1 male |
| 2 | age | years |
| 3 | height | centimetres (FIT metres at scale 100) |
| 4 | weight | tenths of a kilogram |
| 24 | birth year | year − 1900 |
| 28 / 29 | wake / sleep time | seconds after midnight |

Tracks sends only the first five, in a file of its own over the legacy
`GARMIN/NewFiles` path (`SettingsFit.encodeUserProfile`), and the watch
accepts it.

**Getting a file's bytes out of a capture.** Large transfers go on a
multi-link handle as frames `d0 NN` + payload. The first frame is a 6-byte
header, and the body is **raw deflate** (no zlib header). The `files`
extractor assumes zlib, so it fails on these. Concatenate the payloads after
the 2-byte frame headers, skip 2 bytes, and inflate with `wbits = -15`.

## Wi-Fi network setup (2026-10-13)

Smart field **11**:
1. The phone asks with `8: {}`.
2. The watch answers `9: {1: <8 bytes>}`.
3. The phone sends `10: {}` three times. The watch answers `11: {1: <136 bytes>}`,
   which is probably the scan results.
4. The phone sends `14: {1: <192 bytes>}`, presumably the network and its
   password, and the watch answers `15: {1: <168 bytes>}`.
5. Then come `12` and `13`.

**Every payload is encrypted**, at about 7 bits of entropy per byte, and none
of them parse as protobuf. No request goes to Garmin's servers during the
exchange, so the key is agreed between phone and watch, probably from the
watch's 8-byte opening value. Tracks cannot set up Wi-Fi until that scheme is
known.

**The capture holds secrets.** It contains the Garmin account's OAuth tokens
in plain text, in the `http_service` requests the watch makes through
Connect. Never share it.

## Seen, not yet written up

The capture also holds these, all decodable with the command above:
- weather (GFDI `WEATHER_REQUEST`)
- music controls (`MUSIC_CONTROL`, `MUSIC_CONTROL_ENTITY_UPDATE`)
- find my phone (`FIND_MY_PHONE_REQUEST`/`CANCEL`)
- notifications (`NOTIFICATION_UPDATE`/`DATA`/`CONTROL`)
- `calendar_service`, `http_service` (the watch's HTTP proxy), `core_service`
- `installed_apps_service`, `app_config_service` and `device_status_service`
- `AUTH_NEGOTIATION` in the reconnection

## Still to capture

- **Connect IQ install and uninstall**, alone in a capture of its own, with a
  bug report taken straight after so the log cannot rotate past it.
- **Wi-Fi network setup** from Connect.
- **Overnight health sync** (sleep, HRV), the morning after wearing the watch.
- **Pairing from scratch.**
- Garmin Wallet is out of scope. Card provisioning runs through Garmin's and
  the card networks' servers with keys Tracks cannot hold, so it cannot be
  reimplemented.
