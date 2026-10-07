// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Backing up and restoring an account from the browser, in the phone's file
 * format (see crypto.js and format.js).
 *
 * ## What goes in, and from where
 *
 * Exactly what a phone's backup holds, taken from the endpoints a phone syncs
 * through: every row `/sync/pull` serves (with stamps and tombstones), the
 * account binding (this server's id and the account's id — the pair a phone
 * records when it signs in), and every live FIT file, fetched one at a time
 * from `/sync/blobs`. A restore sends it back the way a restored phone would:
 * the rows through `/sync/push`, where they merge field by field with what
 * the account already holds, then each file through the ordinary upload,
 * which skips the ones the server already has.
 *
 * ## Why a module and not a component
 *
 * A job outlives the page that started it: leaving Settings must not cancel
 * a backup halfway through a few hundred megabytes. The state lives here, the
 * pill in the app shell and the Settings section both read it, and closing
 * the tab while one runs asks first.
 */
import { useSyncExternalStore } from "react";
import { api } from "../../api/client";
import { createSealer, NotABackup, openBackup, WrongPassphraseOrDamaged } from "./crypto";
import { BackupFormatError, BackupReader, writeBackup } from "./format";

const LAST_BACKUP_KEY = "tracks_last_backup_at";
const PULL_LIMIT = 2000; // the server's maximum page (spec/sync.yaml)
const PUSH_BATCH = 500;

// ── State ───────────────────────────────────────────────────────────────────

let state = { running: null, result: null };
const listeners = new Set();

function set(patch) {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}

function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * `running` is { kind: "backup" | "restore", done, total } while a job runs —
 * total null until it is known — and `result` is { ok, message } once one ends.
 */
export function useBackupJob() {
  return useSyncExternalStore(subscribe, () => state);
}

/** The words the pill shows for a running job. */
export function jobLabel({ kind, done, total }) {
  const n = new Intl.NumberFormat();
  if (kind === "backup") return total == null ? "Preparing backup" : `Backing up ${n.format(done)} of ${n.format(total)} files`;
  return total == null ? "Checking backup" : `Restoring ${n.format(done)} of ${n.format(total)} files`;
}

/** When this browser last wrote a backup, as epoch ms, or null. Per browser, as the phone's is per phone. */
export function lastBackupAt() {
  try {
    const v = Number(localStorage.getItem(LAST_BACKUP_KEY));
    return v > 0 ? v : null;
  } catch {
    return null;
  }
}

function warnOnLeave(e) {
  e.preventDefault();
  e.returnValue = "";
}

function begin(kind) {
  if (state.running) return false;
  set({ running: { kind, done: 0, total: null }, result: null });
  window.addEventListener("beforeunload", warnOnLeave);
  return true;
}

function end(result) {
  window.removeEventListener("beforeunload", warnOnLeave);
  set({ running: null, result });
}

function progress(done, total) {
  set({ running: { ...state.running, done, total } });
}

// ── Where the file goes ─────────────────────────────────────────────────────

/**
 * Ask where to save, and return a target to write sealed chunks to. Call it
 * straight from a click: the save picker needs the click's user activation.
 *
 * Where the File System Access API exists (Chromium) the backup streams to
 * disk as it is sealed, whatever its size. Elsewhere the sealed chunks are
 * held until the end and handed to the browser's download — sealed, so
 * nothing readable sits in memory, but all of it does. Returns null when the
 * person cancels the picker.
 */
export async function chooseBackupTarget(filename) {
  if (typeof window.showSaveFilePicker === "function") {
    let handle;
    try {
      handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{ description: "Tracks backup", accept: { "application/octet-stream": [".tracksbackup"] } }],
      });
    } catch (e) {
      if (e?.name === "AbortError") return null;
      throw e;
    }
    const writable = await handle.createWritable();
    return {
      write: (chunk) => writable.write(chunk),
      close: () => writable.close(),
      // A failed backup must not leave a file that looks like one. The
      // writable's bytes are discarded by abort; the picker already made an
      // empty file, which remove() takes away where the browser supports it.
      abort: async () => {
        await writable.abort().catch(() => {});
        await handle.remove?.().catch(() => {});
      },
    };
  }
  const parts = [];
  return {
    write: async (chunk) => { parts.push(chunk); },
    close: async () => {
      const url = URL.createObjectURL(new Blob(parts, { type: "application/octet-stream" }));
      const a = Object.assign(document.createElement("a"), { href: url, download: filename });
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    },
    abort: async () => { parts.length = 0; },
  };
}

// ── Backing up ──────────────────────────────────────────────────────────────

/** Every row the account has, newest copy of each, and the header of the first page. */
async function pullAll() {
  const rows = new Map();
  let since = 0;
  let first = null;
  for (;;) {
    const page = await api.syncPull(since, PULL_LIMIT);
    first ??= page;
    // A row edited while the pages are read comes again later; keep the last.
    for (const c of page.changes) rows.set(`${c.entity}\u0000${c.uid}`, c);
    since = page.next;
    if (!page.has_more) break;
  }
  return { rows: [...rows.values()], serverId: first.server_id, epoch: first.epoch };
}

/**
 * Write a backup of the signed-in account to `target` (see chooseBackupTarget),
 * sealed under `passphrase`. Resolves when done; progress and the outcome go
 * to useBackupJob.
 */
export async function runBackup({ user, passphrase, target }) {
  if (!begin("backup")) return;
  try {
    const { rows, serverId } = await pullAll();
    const names = [...new Set(rows
      .filter((r) => r.entity === "fit_file" && r.deleted == null && r.fields?.sha256?.[0])
      .map((r) => r.fields.sha256[0]))].sort();
    const sealer = await createSealer(passphrase);
    await target.write(sealer.header);
    await writeBackup(
      async (bytes) => { for (const chunk of await sealer.write(bytes)) await target.write(chunk); },
      {
        binding: { server_id: serverId, account: String(user.id) },
        rows, names, createdAtMs: Date.now(), read: api.syncBlob, onProgress: progress,
      },
    );
    await target.write(await sealer.finish());
    await target.close();
    try { localStorage.setItem(LAST_BACKUP_KEY, String(Date.now())); } catch { /* per-browser nicety */ }
    end({ ok: true, message: "Backup written." });
  } catch (e) {
    await target.abort();
    end({ ok: false, message: `Could not write the backup: ${e?.message ?? e}` });
  }
}

// ── Restoring ───────────────────────────────────────────────────────────────

function restoreError(e) {
  if (e instanceof WrongPassphraseOrDamaged || e instanceof NotABackup || e instanceof BackupFormatError) return e.message;
  return `Could not restore the backup: ${e?.message ?? e}`;
}

/**
 * Read a backup through to its end, changing nothing, and say whose it is.
 *
 * Every restore starts here. A streamed file is only known to be whole once
 * its last chunk authenticates, and a damaged backup must be refused before
 * it has half-merged into the account, not after. Returns
 * `{ differentServer }` — true when the backup came from another Tracks
 * server, which the caller should confirm — or `{ error }`.
 *
 * The account rules are the phone's sign-in rules (ReplicaStore.link): a
 * backup bound to another account on this server is refused, since merging one
 * person's history into another's cannot be undone; one from a phone never
 * linked to a server, or from this account, restores; one from a different
 * server is this account's data only if the person says so — the usual case
 * is a server that was rebuilt.
 */
export async function checkRestore({ user, file, passphrase }) {
  if (!begin("restore")) return { error: "A backup or restore is already running." };
  try {
    const reader = await BackupReader.open(await openBackup(file, passphrase));
    await reader.forEachFile(async () => {});
    const here = await api.syncPull(0, 1);
    const b = reader.binding;
    window.removeEventListener("beforeunload", warnOnLeave);
    set({ running: null });
    if (b && b.server_id === here.server_id && b.account !== String(user.id)) {
      const error = "This backup belongs to another account on this server, so it cannot be restored into this one.";
      end({ ok: false, message: error });
      return { error };
    }
    return { differentServer: Boolean(b && b.server_id !== here.server_id) };
  } catch (e) {
    const error = restoreError(e);
    end({ ok: false, message: error });
    return { error };
  }
}

/** Apply a backup that checkRestore passed: rows first, then files. */
export async function runRestore({ file, passphrase }) {
  if (!begin("restore")) return;
  try {
    const reader = await BackupReader.open(await openBackup(file, passphrase));
    const { epoch } = await api.syncPull(0, 1);
    // Rows before files, as on the phone: a rename or a completed workout
    // names an activity its file has not created yet, and the server holds
    // such a row until the file arrives rather than dropping it.
    let refused = 0;
    for (let i = 0; i < reader.rows.length; i += PUSH_BATCH) {
      const res = await api.syncPush({ changes: reader.rows.slice(i, i + PUSH_BATCH), epoch });
      // Read-only entities (a file's own row) are rebuilt from the upload, so
      // their refusal is expected; anything else refused is worth saying.
      refused += (res.results ?? []).filter((r) => r.status === "rejected" && r.reason !== "readonly").length;
    }
    const total = reader.names.length;
    let done = 0;
    let restored = 0;
    progress(0, total);
    await reader.forEachFile(async (name, bytes) => {
      if (bytes.length) {
        try {
          await api.uploadFitFiles([new File([bytes], `${name}.fit`)]);
          restored++;
        } catch {
          // One file the server cannot import does not stop the rest — the
          // phone's restore counts and carries on the same way.
        }
      }
      progress(++done, total);
    });
    api.clearCache();
    const rows = refused ? ` ${refused} saved change${refused === 1 ? " was" : "s were"} refused by the server.` : "";
    end({ ok: true, message: `Restored, with ${restored} activity and health files.${rows}` });
  } catch (e) {
    end({ ok: false, message: restoreError(e) });
  }
}
