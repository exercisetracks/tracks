// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Importing FIT files in bulk, and exporting every one the account holds.
 *
 * ## Import
 *
 * What was picked or dropped is expanded (sources.js — folders, zips), the
 * server is asked which files it already has (/fit/precheck, filename + size,
 * so re-running an import that was stopped halfway does not send 2 GB again),
 * and the rest go through the ordinary /fit/upload in batches (uploadFiles).
 * Nothing here is new on the server: the upload already dedupes by content
 * hash, so a file the precheck missed costs only its transfer.
 *
 * ## Export
 *
 * The original files, as plain FIT in a zip — for leaving Tracks, or for
 * another app, the way Garmin Connect's export is. The list of files is the
 * pulled `fit_file` rows, and each file comes from /sync/blobs, exactly as a
 * backup reads them (lib/backup/job.js); the difference is that a backup is
 * sealed and only Tracks can read it, and this is meant to be opened by
 * anything. It is not encrypted: it is the person's own data, handed to them
 * at their request, and the save dialog makes plain where it goes.
 *
 * ## Why a module and not a component
 *
 * As with backups: a 25,000-file import runs for half an hour, and leaving
 * Settings must not cancel it. The state lives here; the pill in the app
 * shell and the Settings section both read it.
 */
import { useSyncExternalStore } from "react";
import { api } from "../../api/client";
import { pullAll } from "../backup/job";
import { SyncCancelled, UploadRefused, makeCancelToken, precheckFiles, uploadFiles } from "../deviceSync";
import { expandSources } from "./sources";
import { ZipWriter } from "./zip";

// /fit/precheck takes at most 10,000 names a request.
const PRECHECK_CHUNK = 5000;
// Without a save picker the zip is assembled in memory and handed to the
// browser's download, so a long history is split into parts this size, each
// downloaded (and freed) as it fills — as Garmin splits its own export.
const PART_BYTES = 512 * 1024 * 1024;
// The result lists this many failures by name; past that, a count says the rest.
const MAX_LISTED_FAILURES = 200;

// ── State ───────────────────────────────────────────────────────────────────

let state = { running: null, result: null };
let cancelToken = null;
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
 * `running` is { kind: "import" | "export", done, total } while a job runs —
 * total null until it is known — and `result` is { ok, message, failures }
 * once one ends.
 */
export function useFitFileJob() {
  return useSyncExternalStore(subscribe, () => state);
}

export function fitJobLabel({ kind, done, total }) {
  const n = new Intl.NumberFormat();
  if (kind === "export") return total == null ? "Preparing export" : `Exporting ${n.format(done)} of ${n.format(total)} files`;
  return total == null ? "Reading files" : `Importing ${n.format(done)} of ${n.format(total)} files`;
}

/** Stop the running job after the file (or batch) in hand. */
export function cancelFitFileJob() {
  if (cancelToken) cancelToken.cancelled = true;
}

function warnOnLeave(e) {
  e.preventDefault();
  e.returnValue = "";
}

function begin(kind) {
  if (state.running) return false;
  cancelToken = makeCancelToken();
  set({ running: { kind, done: 0, total: null }, result: null });
  window.addEventListener("beforeunload", warnOnLeave);
  return true;
}

function end(result) {
  window.removeEventListener("beforeunload", warnOnLeave);
  cancelToken = null;
  set({ running: null, result: { failures: [], ...result } });
}

function progress(done, total) {
  set({ running: { ...state.running, done, total } });
}

const count = (n, one, many = `${one}s`) => `${new Intl.NumberFormat().format(n)} ${n === 1 ? one : many}`;

function listFailures(failures) {
  if (failures.length <= MAX_LISTED_FAILURES) return failures;
  return [...failures.slice(0, MAX_LISTED_FAILURES), { name: `…and ${failures.length - MAX_LISTED_FAILURES} more`, error: "" }];
}

// ── Import ──────────────────────────────────────────────────────────────────

/** Import `files` — Files from a picker (files or a folder) or a drop. */
export async function runImport(files) {
  if (!begin("import")) return;
  let sent = 0;
  try {
    const { entries, ignored, failures: unopened } = await expandSources(files);
    if (entries.length === 0) {
      end({ ok: false, message: "No .fit files found in what you picked.", failures: unopened });
      return;
    }

    const known = new Set();
    for (let i = 0; i < entries.length; i += PRECHECK_CHUNK) {
      cancelToken.throwIfCancelled();
      // null means the check is unavailable; everything is then sent, and
      // the server's own dedupe catches what it already has.
      const k = await precheckFiles(entries.slice(i, i + PRECHECK_CHUNK));
      k?.forEach((name) => known.add(name));
    }
    const toSend = entries.filter((e) => !known.has(e.name));
    const alreadyThere = entries.length - toSend.length;
    let done = alreadyThere;
    progress(done, entries.length);

    const totals = await uploadFiles(toSend, {
      cancelToken,
      onFile: (_f, phase) => {
        if (phase === "done" || phase === "error") {
          sent++;
          progress(++done, entries.length);
        }
      },
    });

    const parts = [`Imported ${count(totals.saved, "file")}.`];
    const skipped = alreadyThere + totals.skipped;
    if (skipped) parts.push(`${new Intl.NumberFormat().format(skipped)} ${skipped === 1 ? "was" : "were"} already in Tracks.`);
    if (totals.errors) parts.push(`${count(totals.errors, "file")} could not be imported.`);
    if (ignored) parts.push(`${count(ignored, "other file")} skipped (not .fit).`);
    api.clearCache();
    end({
      ok: totals.errors === 0 && unopened.length === 0,
      message: parts.join(" "),
      failures: listFailures([...unopened, ...totals.failures]),
    });
  } catch (e) {
    api.clearCache();
    if (e instanceof SyncCancelled) {
      end({ ok: true, message: `Import stopped. The ${count(sent, "file")} already sent stay imported; importing the same files again picks up where this left off.` });
    } else if (e instanceof UploadRefused) {
      end({ ok: false, message: `The server refused the upload: ${e.message}` });
    } else {
      end({ ok: false, message: `Could not import: ${e?.message ?? e}` });
    }
  }
}

// ── Export ──────────────────────────────────────────────────────────────────

/**
 * Ask where to save, and return a target that opens one sink per zip part.
 * Call it straight from a click: the save picker needs the click's user
 * activation. Returns null when the person cancels the picker.
 *
 * Where the File System Access API exists (Chromium) the zip streams to the
 * chosen file, whatever its size, in one part. Elsewhere each part is held in
 * memory until it reaches PART_BYTES and is then downloaded: the first as
 * `name.zip`, any after it as `name-2.zip`, `name-3.zip`…
 */
export async function chooseExportTarget(filename) {
  if (typeof window.showSaveFilePicker === "function") {
    let handle;
    try {
      handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{ description: "Zip archive", accept: { "application/zip": [".zip"] } }],
      });
    } catch (e) {
      if (e?.name === "AbortError") return null;
      throw e;
    }
    return {
      partBytes: Infinity,
      open: async () => {
        const writable = await handle.createWritable();
        return {
          write: (chunk) => writable.write(chunk),
          close: () => writable.close(),
          // A failed export must not leave a file that looks like one.
          abort: async () => {
            await writable.abort().catch(() => {});
            await handle.remove?.().catch(() => {});
          },
        };
      },
    };
  }
  const stem = filename.replace(/\.zip$/i, "");
  let part = 0;
  return {
    partBytes: PART_BYTES,
    open: async () => {
      const name = ++part === 1 ? `${stem}.zip` : `${stem}-${part}.zip`;
      let chunks = [];
      return {
        write: async (chunk) => { chunks.push(chunk); },
        close: async () => {
          const url = URL.createObjectURL(new Blob(chunks, { type: "application/zip" }));
          chunks = [];
          const a = Object.assign(document.createElement("a"), { href: url, download: name });
          document.body.appendChild(a);
          a.click();
          a.remove();
          setTimeout(() => URL.revokeObjectURL(url), 60_000);
        },
        abort: async () => { chunks = []; },
      };
    },
  };
}

function wireDate(v) {
  if (v == null) return undefined;
  const d = typeof v === "number" ? new Date(v < 1e12 ? v * 1000 : v) : new Date(v);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/**
 * The name each file gets in the zip: `<kind>/<its original name>`, so
 * activities, sleep and monitoring land in folders of their own. Two files
 * with the same name in one folder get the hash's head appended to the second.
 */
export function exportNames(rows) {
  const used = new Set();
  return rows.map((r) => {
    const sha = r.fields.sha256[0];
    const kind = String(r.fields.file_kind?.[0] || "other").replace(/[^\w-]/g, "_");
    const original = String(r.fields.filename?.[0] || "").replace(/^.*[\\/]/, "").replace(/[\x00-\x1f:*?"<>|]/g, "_");
    const stem = original.replace(/\.fit$/i, "") || sha;
    let name = `${kind}/${stem}.fit`;
    if (used.has(name.toLowerCase())) name = `${kind}/${stem}-${sha.slice(0, 8)}.fit`;
    used.add(name.toLowerCase());
    return name;
  });
}

/** Write every FIT file the account holds to `target` (see chooseExportTarget). */
export async function runExport(target) {
  if (!begin("export")) return;
  let sink = null;
  try {
    const { rows } = await pullAll();
    const seen = new Set();
    const files = rows.filter((r) => {
      const sha = r.entity === "fit_file" && r.deleted == null && r.fields?.sha256?.[0];
      if (!sha || seen.has(sha)) return false;
      seen.add(sha);
      return true;
    });
    if (files.length === 0) {
      end({ ok: false, message: "There are no files to export yet." });
      return;
    }
    const names = exportNames(files);
    const failures = [];
    let written = 0;
    let parts = 1;
    progress(0, files.length);

    sink = await target.open();
    let zip = new ZipWriter(sink.write);
    for (let i = 0; i < files.length; i++) {
      cancelToken.throwIfCancelled();
      const r = files[i];
      let bytes;
      try {
        bytes = await api.syncBlob(r.fields.sha256[0]);
      } catch (e) {
        // A missing or unreadable blob is one file lost from the export; a
        // refused session is every file lost, so that one stops it.
        if (e.status === 401 || e.status === 403) throw e;
        failures.push({ name: names[i], error: e.message || String(e) });
      }
      if (bytes === null) failures.push({ name: names[i], error: "The server holds no copy of this file" });
      if (bytes) {
        if (zip.entries.length > 0 && zip.bytesWritten + bytes.length > target.partBytes) {
          await zip.finish();
          await sink.close();
          sink = await target.open();
          zip = new ZipWriter(sink.write);
          parts++;
        }
        await zip.add(names[i], bytes, wireDate(r.fields.started_at?.[0]));
        written++;
      }
      progress(i + 1, files.length);
    }
    await zip.finish();
    await sink.close();
    sink = null;
    const where = parts > 1 ? ` in ${parts} zip files` : "";
    const missed = failures.length ? ` ${count(failures.length, "file")} could not be read.` : "";
    end({ ok: failures.length === 0, message: `Exported ${count(written, "file")}${where}.${missed}`, failures: listFailures(failures) });
  } catch (e) {
    await sink?.abort();
    if (e instanceof SyncCancelled) end({ ok: true, message: "Export cancelled." });
    else end({ ok: false, message: `Could not export: ${e?.message ?? e}` });
  }
}
