<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Status on the phone: one pill

How the Android app tells someone what it is doing out of sight, and what came
of it. The code is `ui/status/` (`StatusBoard`, `StatusHost`); this is the rules
it follows, and the ones a new message has to follow.

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

Collapsed, it shows **one** thing, in this order:

1. **The newest failure.** It stays until it is dismissed, or answered by a
   later result from the same source.
2. **A running job.** The ring fills with a percentage when the job knows how
   far along it is, and spins when it does not.
3. **The newest success**, for 3.5 s.

Everything else running or unresolved is a "+N". Tapping the pill opens the card
with all of it, and **Sync now** (the same sync as pull-to-sync).

With nothing to say, the pill is gone. There is no idle state.

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
- **Lead with the fix.** The pill shows one line, so put what to do first:
  "Restart your watch — it refused 12 file(s). Hold LIGHT…". The full text is
  in the card.
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
  as the pull is answered; the pill carries the sync from there.
- **The map hides the Android status bar.** A swipe from the top edge brings it
  back for a moment.
