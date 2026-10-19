<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Status on the phone: one pill, then the drawer

How the Android app tells someone what it is doing out of sight, and what came
of it. The code is `ui/status/` (`StatusBoard`, `StatusHost`, `StatusDrawer`);
this is the rules it follows, and the ones a new message has to follow.

## Why there is one

Each feature used to report its own way:
- a pill for the server sync, one for the watch and one for backup;
- a banner for map downloads;
- a toast for the map's receipts;
- a Material snackbar for everything else;
- pull-to-sync's own spinner.

They were drawn in two columns at the top that overlapped. One watch sync could
show three times at once: the gesture's spinner, the watch pill, and a
"Sending to the watch…" toast. Now everything goes to `StatusBoard`, and
`StatusHost` draws it as one pill at the top of every screen.

## The pill

```
 ╭──────────────────────────────────╮
 │ ◔  Watch · Reading the watch  +2 │    collapsed: one line
 ╰──────────────────────────────────╯
 tap ↓
 ╭──────────────────────────────────╮
 │ ◔  Watch · Reading the watch     │    expanded: everything
 │ ◔  Server · Receiving changes 40%│
 │ ⚠  Could not save that place   × │
 │ ✓  Imported 4 places           × │
 │ Sync now                   Close │
 ╰──────────────────────────────────╯
```

The pill is **news, not a dashboard**. It pops up when something new happens and
then goes, whatever the news is:

| What happened | How long it pops up |
|---|---|
| A job starts (a source begins running) | 4 s |
| A result | 4 s |
| A failure | 8 s |

A job's progress ticks ("Downloading 3 of 40…") do not pop it again, and a
source that has popped stays quiet until it stops and starts afresh. Collapsed,
the pill shows the one thing it popped up for, live (a job keeps updating its
ring), and "+N" for whatever else is running or unresolved. Tapping it opens the
card with all of it and **Sync now**, which stays open until **Close**.

This replaced a pill that stayed for as long as a job ran or a failure was
unresolved. Syncs and downloads run for many minutes, and the pill sat over
every screen for all of them (changed 2026-10-18).

## The drawer's status area

Once the pill has gone, the news lives in the drawer, below Settings, under
**Status**:

- each running job, one per source, with its progress;
- the last 5 results, newest first, each with when it happened ("4 min ago")
  and a × to dismiss;
- **Sync now**.

A row is one line until tapped, then shows all of its words.

The **menu button** says there is something in there: a ring around it while
anything runs, and a red dot for a failure posted since the drawer was last
opened. Opening the drawer counts as seeing it. The map's floating menu button
does the same.

| Glyph | Means | Colour |
|---|---|---|
| Ring (filling or spinning) | Running | `primary` |
| ✓ `CheckCircle` | Done | `primary` |
| ⚠ `Warning` | Failed: needs the person | `error` |

The surface is the theme's `surface`, raised (tonal 3, shadow 6), fully rounded
when collapsed and `Radius.xl` when expanded. It is never the inverse surface,
which turned it white in dark mode.

## Sources: one slot each

| Source | Who sets it |
|---|---|
| `Server` | the replica sync (`syncProgress`) |
| `Watch` | `WatchSyncRunner.progress`, and messages about the watch |
| `Maps` | area downloads (`downloadJob`), only while the map is open |
| `Backup` | the backup writer |
| `App` | edits and imports on this phone: results only |

Each source has **one running slot**. That slot is what stops the same
operation appearing twice. For example, a "Sending “Loop” to the watch…"
message and the watch runner's progress both fill the `Watch` slot, so they
show as one line.

A result from a source:
- ends a "…" job that source posted;
- answers that source's earlier failures. A watch sync that worked removes
  "Restart your watch".

It never ends a runner's progress, which clears itself when the runner stops.

## Writing a message

Post it: `container.status.post(text)`. View models that already have a
`message` or `notice` field keep it; the shell and the map forward those to the
board. Never show a snackbar, toast, banner or pill of your own:
`OneStatusVoiceTest` fails if you do.

- **Work starting ends in "…"**: "Sending “Loop” to the watch…". It becomes a
  running job until the source's result arrives, or after 2 minutes.
- **A result is a past-tense fact**: "Imported 4 places", "“Loop” is on the
  watch". Use no exclamation marks and no "Successfully".
- **A failure starts with "Could not"**, or names what went wrong ("…failed",
  "…refused"). `StatusBoard.isFailure` reads the words, so this is a rule, not
  a style.
- **Lead with the fix.** The pill shows one line for a few seconds, so put
  what to do first: "Restart your watch — it refused 12 file(s). Hold
  LIGHT…". The full text is in the card and the drawer.
- **Name the thing, not the mechanism**: "“Loop” is on the watch", not
  "Explore sync completed".
- **Say "watch" or "server"** when the message is about one of them. The words
  pick the source when the caller does not.

## Not the pill's job

- **Android system notifications** are for when the app is not open:
  background watch sync and "Restart your watch" (`WatchSyncNotifications`).
- **Errors inside a form**, like a bad value, stay beside the field.
- **What is selected or marked on the map** (the "12 campgrounds" chip) is
  state, not news. It sits under the pill.

## Gestures

- **Pull down to sync** works on every page but the map, whose drag pans. With
  a watch it syncs the watch, otherwise the server. Its spinner lets go as soon
  as the pull is answered; the pill announces the sync and the drawer carries
  it from there.
- **The map hides the Android status bar.** A swipe from the top edge brings it
  back for a moment.
