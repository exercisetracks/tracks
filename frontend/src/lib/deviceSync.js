// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Orchestration for browser-based Garmin device sync.
//
// Two connection kinds feed one pipeline (runFullSync):
//  - { kind: "mtp", mtp, storageId }  — WebUSB/MTP watch (newer/music devices)
//  - { kind: "fs", root }             — mass-storage watch mounted as a drive,
//    picked with the File System Access API (e.g. Instinct 2)
//
// Import is selective, mirroring the garmin-sync container's folder list —
// we deliberately do NOT trawl the whole device (that would re-import the
// workouts/courses we ourselves push).

import { BASE_URL } from "../apiBase";
import { PARENT_ROOT } from "./mtp";

// Mirrors garmin-sync's GARMIN_SYNC_FOLDER_PATH default. Non-recursive:
// these folders hold flat lists of FIT files.
export const SYNC_FOLDERS = [
  "GARMIN/Activity",
  "GARMIN/Sleep",
  "GARMIN/HRVStatus",
  "GARMIN/Monitor",
];

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function isFitFile(name) {
  return name.toLowerCase().endsWith(".fit");
}

export function isFsAccessSupported() {
  return typeof window !== "undefined" && typeof window.showDirectoryPicker === "function";
}

export function isWindows() {
  return typeof navigator !== "undefined" && /Windows/i.test(navigator.userAgent);
}

export class SyncCancelled extends Error {
  constructor() {
    super("Sync cancelled");
    this.name = "SyncCancelled";
  }
}

/** Cooperative cancellation token; UI sets .cancelled, walkers check it. */
export function makeCancelToken() {
  return {
    cancelled: false,
    throwIfCancelled() {
      if (this.cancelled) throw new SyncCancelled();
    },
  };
}

// --- scanning (selective, non-recursive) -------------------------------------

async function mtpChildByName(mtp, storageId, parentHandle, name) {
  const handles = await mtp.getObjectHandles(storageId, parentHandle);
  for (const h of handles) {
    const info = await mtp.getObjectInfo(h);
    if (info.filename.toLowerCase() === name.toLowerCase()) return { handle: h, info };
  }
  return null;
}

async function mtpResolveFolder(mtp, storageId, path, { create = false } = {}) {
  let parent = PARENT_ROOT;
  for (const segment of path.split("/")) {
    const child = await mtpChildByName(mtp, storageId, parent, segment);
    if (child && child.info.isFolder) {
      parent = child.handle;
    } else if (child) {
      throw new Error(`${segment} exists on the device but is not a folder`);
    } else if (create) {
      parent = await mtp.createFolder(storageId, parent, segment);
    } else {
      return null;
    }
  }
  return parent;
}

// Resolve a folder path under a picked directory handle. The user may have
// picked the GARMIN folder itself rather than the volume root; don't look
// for GARMIN inside GARMIN.
async function fsResolveFolder(rootHandle, folderPath, { create = false } = {}) {
  let segments = folderPath.split("/");
  if (rootHandle.name.toLowerCase() === segments[0].toLowerCase()) {
    segments = segments.slice(1);
  }
  let dir = rootHandle;
  for (const segment of segments) {
    try {
      dir = await dir.getDirectoryHandle(segment, { create });
    } catch (e) {
      if (e.name === "NotFoundError") return null;
      throw e;
    }
  }
  return dir;
}

/**
 * Collect .fit files from the SYNC_FOLDERS on the device. Descriptors:
 * { name, size, modified: Date|null, path, read(onProgress) → Uint8Array }.
 */
export async function scanForImport(connection, { onStatus = null, cancelToken = null } = {}) {
  const files = [];
  for (const folderPath of SYNC_FOLDERS) {
    cancelToken?.throwIfCancelled();
    onStatus?.(`Scanning ${folderPath}…`);

    if (connection.kind === "mtp") {
      const { mtp, storageId } = connection;
      const folder = await mtpResolveFolder(mtp, storageId, folderPath);
      if (folder === null) continue; // folder absent on this watch model
      for (const handle of await mtp.getObjectHandles(storageId, folder)) {
        cancelToken?.throwIfCancelled();
        const info = await mtp.getObjectInfo(handle);
        if (info.isFolder || !isFitFile(info.filename)) continue;
        files.push({
          name: info.filename,
          size: info.size,
          modified: info.modificationDate || info.captureDate,
          path: `${folderPath}/${info.filename}`,
          read: (onProgress) => mtp.getObject(handle, onProgress),
        });
      }
    } else {
      const dir = await fsResolveFolder(connection.root, folderPath);
      if (dir === null) continue;
      for await (const [name, entry] of dir.entries()) {
        cancelToken?.throwIfCancelled();
        if (entry.kind !== "file" || !isFitFile(name)) continue;
        const file = await entry.getFile();
        files.push({
          name,
          size: file.size,
          modified: file.lastModified ? new Date(file.lastModified) : null,
          path: `${folderPath}/${name}`,
          read: async () => new Uint8Array(await file.arrayBuffer()),
        });
      }
    }
    onStatus?.(`Scanning ${folderPath}… ${files.length} file(s) so far`);
  }
  return files;
}

// --- backend calls -----------------------------------------------------------

function authHeaders() {
  return { Authorization: `Bearer ${localStorage.getItem("tracks_token")}` };
}

async function apiJson(path, body = undefined) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { ...authHeaders(), ...(body !== undefined && { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} failed (${res.status})`);
  return res.json();
}

/** Fetch raw bytes (a music track) with the same auth as apiJson. */
async function apiBytes(path) {
  const res = await fetch(`${BASE_URL}${path}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`${path} failed (${res.status})`);
  return new Uint8Array(await res.arrayBuffer());
}

/**
 * Ask the backend which of these files it has already imported (matched on
 * filename + size). Returns a Set of known filenames, or null if the check
 * is unavailable — callers then upload everything; the server still dedupes
 * by content hash.
 */
export async function precheckFiles(files) {
  try {
    const data = await apiJson("/fit/precheck", {
      files: files.map((f) => ({ filename: f.name, size: f.size ?? null })),
    });
    return new Set(data.known || []);
  } catch {
    return null;
  }
}

/** Pending pushes and deletions for the watch. */
export async function fetchPushPlan() {
  const [up, del] = await Promise.all([
    apiJson("/device-sync/upload-list"),
    apiJson("/device-sync/delete-list"),
  ]);
  return { uploads: up.items || [], deletes: del.items || [] };
}

// --- import upload -----------------------------------------------------------

// Keep multipart POSTs comfortably sized. FIT files are small (activities
// < 1 MB) so the count cap usually binds.
const BATCH_MAX_FILES = 25;
const BATCH_MAX_BYTES = 32 * 1024 * 1024;

/**
 * Read each file from the device and upload to /fit/upload in batches.
 * onFile(file, phase, received, total) — phase: "reading" | "uploading" | "done" | "error"
 * Returns aggregate { saved, skipped, errors, failures: [{name, error}] }.
 */
export async function uploadFiles(files, { onFile = null, cancelToken = null } = {}) {
  const totals = { saved: 0, skipped: 0, errors: 0, failures: [] };

  const batches = [];
  let current = [];
  let currentBytes = 0;
  for (const f of files) {
    if (current.length >= BATCH_MAX_FILES || (currentBytes + (f.size || 0) > BATCH_MAX_BYTES && current.length > 0)) {
      batches.push(current);
      current = [];
      currentBytes = 0;
    }
    current.push(f);
    currentBytes += f.size || 0;
  }
  if (current.length > 0) batches.push(current);

  for (const batch of batches) {
    cancelToken?.throwIfCancelled();
    const form = new FormData();
    const batchFiles = [];

    for (const f of batch) {
      cancelToken?.throwIfCancelled();
      onFile?.(f, "reading", 0, f.size || 0);
      let bytes;
      try {
        bytes = await f.read((received, total) => onFile?.(f, "reading", received, total));
      } catch (e) {
        if (e instanceof SyncCancelled) throw e;
        totals.errors += 1;
        totals.failures.push({ name: f.name, error: e.message || String(e) });
        onFile?.(f, "error", 0, f.size || 0);
        continue;
      }
      form.append("files", new Blob([bytes]), f.name);
      batchFiles.push(f);
      onFile?.(f, "uploading", f.size || 0, f.size || 0);
    }
    if (batchFiles.length === 0) continue;

    try {
      const res = await fetch(`${BASE_URL}/fit/upload`, {
        method: "POST",
        headers: authHeaders(),
        body: form,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.detail?.message || data.detail || `Upload failed (${res.status})`);
      }
      totals.saved += data.saved || 0;
      totals.skipped += data.skipped || 0;
      totals.errors += data.errors || 0;
      for (const f of batchFiles) onFile?.(f, "done", f.size || 0, f.size || 0);
    } catch (e) {
      totals.errors += batchFiles.length;
      totals.failures.push({ name: `${batchFiles.length} file(s)`, error: e.message || String(e) });
      for (const f of batchFiles) onFile?.(f, "error", 0, f.size || 0);
    }
  }

  return totals;
}

// --- write targets -----------------------------------------------------------

/**
 * Write/remove files on an MTP device.
 *
 * Each folder is enumerated ONCE and its name→handle listing cached; without
 * this, every write/remove re-lists the whole folder (one GetObjectInfo
 * round-trip per file in it), which made pushing/deleting a batch of
 * workouts painfully slow — O(items × folder size) USB transactions.
 */
function makeMtpTarget(mtp, storageId) {
  const folders = new Map(); // path → { handle, children: Map<lowercase name, handle> } | null
  const loadFolder = async (path, create) => {
    if (folders.has(path) && (folders.get(path) !== null || !create)) {
      return folders.get(path);
    }
    const handle = await mtpResolveFolder(mtp, storageId, path, { create });
    if (handle === null) {
      folders.set(path, null);
      return null;
    }
    const children = new Map();
    for (const h of await mtp.getObjectHandles(storageId, handle)) {
      const info = await mtp.getObjectInfo(h);
      children.set(info.filename.toLowerCase(), h);
    }
    const entry = { handle, children };
    folders.set(path, entry);
    return entry;
  };
  return {
    async write(folderPath, filename, bytes, onProgress = null) {
      const folder = await loadFolder(folderPath, true);
      // MTP has no overwrite: delete any existing object with this name first.
      const existing = folder.children.get(filename.toLowerCase());
      if (existing !== undefined) await mtp.deleteObject(existing);
      const handle = await mtp.sendFile(storageId, folder.handle, filename, bytes, onProgress);
      folder.children.set(filename.toLowerCase(), handle);
    },
    async remove(folderPath, filename) {
      const folder = await loadFolder(folderPath, false);
      if (folder === null) return; // folder absent — nothing to delete
      const existing = folder.children.get(filename.toLowerCase());
      if (existing !== undefined) {
        await mtp.deleteObject(existing);
        folder.children.delete(filename.toLowerCase());
      }
    },
  };
}

/**
 * Build a write target for a connection, refusing one that cannot be written.
 *
 * A folder handle picked in an earlier session may no longer carry write
 * permission, and re-requesting it needs user activation that expired long ago.
 * This is the strict builder: the music push is the whole point of the
 * operation, so failing loudly beats writing nothing and reporting success.
 *
 * runFullSync deliberately does NOT use this — it still has activities to
 * import when the push is impossible, so it records the lost push as a failure
 * and carries on rather than aborting the sync.
 */
export async function makeWriteTarget(connection) {
  if (connection.kind === "mtp") {
    return makeMtpTarget(connection.mtp, connection.storageId);
  }
  const perm = await connection.root.queryPermission({ mode: "readwrite" });
  if (perm !== "granted") {
    throw new Error(
      "No write access to the device folder — re-pick it and allow editing"
    );
  }
  return makeFsTarget(connection.root);
}

/** Same interface over a mounted volume picked with showDirectoryPicker. */
function makeFsTarget(rootHandle) {
  return {
    async write(folderPath, filename, bytes, onProgress = null) {
      const dir = await fsResolveFolder(rootHandle, folderPath, { create: true });
      const fh = await dir.getFileHandle(filename, { create: true });
      const w = await fh.createWritable();
      await w.write(bytes);
      await w.close();
      // The FS path writes in one call, so progress is all-or-nothing; report
      // completion so callers can share one progress model across transports.
      if (onProgress) onProgress(bytes.byteLength, bytes.byteLength);
    },
    async remove(folderPath, filename) {
      const dir = await fsResolveFolder(rootHandle, folderPath);
      if (dir === null) return;
      await dir.removeEntry(filename).catch((e) => {
        if (e.name !== "NotFoundError") throw e;
      });
    },
  };
}

// --- push --------------------------------------------------------------------

/**
 * Push everything pending and record it server-side, mirroring the cable
 * sync's cycle order exactly (garmin-sync's _worker_main): deletions →
 * mark → uploads → mark → schedule. Order matters: marking a pending
 * deletion frees any workout that referenced that filename for re-upload,
 * so the upload list must be fetched AFTER deletions are recorded.
 * Returns { uploaded, deleted, scheduled, failures }.
 */
export async function pushToWatch(target, { onStatus = null, cancelToken = null } = {}) {
  const result = { uploaded: 0, deleted: 0, scheduled: false, failures: [] };

  // 1. Deletions (completed workouts, orphans from plan regeneration/removal).
  const deletes = (await apiJson("/device-sync/delete-list")).items || [];
  const deletedItems = [];
  for (const item of deletes) {
    cancelToken?.throwIfCancelled();
    onStatus?.(`Removing ${item.filename}…`);
    try {
      await target.remove(item.folder, item.filename);
      deletedItems.push({ type: item.type, id: item.id });
    } catch (e) {
      result.failures.push({ filename: item.filename, error: e.message || String(e) });
    }
  }
  if (deletedItems.length > 0) {
    await apiJson("/device-sync/mark-deleted", { items: deletedItems });
    result.deleted = deletedItems.length;
  }

  // 2. Uploads — fetched fresh so re-uploads freed by step 1 are included.
  const uploads = (await apiJson("/device-sync/upload-list")).items || [];
  const uploadedItems = [];
  for (const item of uploads) {
    cancelToken?.throwIfCancelled();
    onStatus?.(`Sending ${item.filename}…`);
    try {
      await target.write(item.folder, item.filename, b64ToBytes(item.fit_b64));
      // `ids` matters only for waypoints: Locations.fit carries every saved
      // place at once, so what was delivered is that list rather than the
      // item's own id. Passed through untouched for every other type.
      uploadedItems.push({ type: item.type, id: item.id, filename: item.filename,
                           ...(item.ids ? { ids: item.ids } : {}) });
    } catch (e) {
      result.failures.push({ filename: item.filename, error: e.message || String(e) });
    }
  }
  if (uploadedItems.length > 0) {
    await apiJson("/device-sync/mark-uploaded", { items: uploadedItems });
    result.uploaded = uploadedItems.length;
  }

  // 3. Training-calendar schedule: regenerated after the marks so it reflects
  // exactly what's on the watch now. Written even when empty — an empty
  // SCHEDULE.fit is how a deleted calendar gets cleared off the watch.
  try {
    const sched = await apiJson("/device-sync/schedule-fit");
    if (sched.fit_b64) {
      onStatus?.(`Sending ${sched.filename}…`);
      await target.write(sched.folder, sched.filename, b64ToBytes(sched.fit_b64));
      result.scheduled = true;
    }
  } catch (e) {
    result.failures.push({ filename: "SCHEDULE.fit", error: e.message || String(e) });
  }

  return result;
}

// --- music -------------------------------------------------------------------

/**
 * Push the music library to the watch.
 *
 * Deliberately NOT part of pushToWatch/runFullSync. Everything that pipeline
 * moves is kilobytes and can ride along with every routine sync; a music push
 * is megabytes per track and can run for minutes, so it is opt-in and driven
 * from its own button with its own progress.
 *
 * Order mirrors pushToWatch — removals, then additions, then playlists last so
 * every .m3u names files that are already on the device.
 *
 * Audio is streamed per track from /music/tracks/{id}/audio rather than
 * arriving inline in the plan: a library-sized JSON body of base64 would be a
 * third larger than the files themselves and buffered whole at both ends.
 *
 * Returns { added, removed, playlists, bytes, failures }.
 */
export async function pushMusic(target, { onStatus = null, onProgress = null, cancelToken = null } = {}) {
  const result = { added: 0, removed: 0, playlists: 0, bytes: 0, failures: [] };
  const plan = await apiJson("/music/device-plan");

  if (plan.on_device_after > plan.device_file_limit) {
    // Garmin's ceiling is device-wide. Overrunning it does not error — the
    // extra files are simply never indexed — so refuse before spending
    // minutes transferring tracks that would not play.
    throw new Error(
      `That would put ${plan.on_device_after} files on the watch, over the ` +
      `${plan.device_file_limit}-file limit. Unload some tracks first.`
    );
  }

  // 1. Removals.
  const removedIds = [];
  for (const item of plan.remove || []) {
    cancelToken?.throwIfCancelled();
    onStatus?.(`Removing ${item.filename}…`);
    try {
      await target.remove(item.folder, item.filename);
      removedIds.push(item.id);
    } catch (e) {
      result.failures.push({ filename: item.filename, error: e.message || String(e) });
    }
  }
  if (removedIds.length > 0) {
    await apiJson("/music/mark-deleted", { ids: removedIds });
    result.removed = removedIds.length;
  }

  // 2. Additions. Progress is reported across the whole push, not per file,
  // so the bar advances smoothly through a long queue instead of restarting.
  const total = plan.bytes_to_add || 0;
  let done = 0;
  const uploaded = [];
  for (const item of plan.add || []) {
    cancelToken?.throwIfCancelled();
    const label = item.artist ? `${item.artist} — ${item.title}` : item.title;
    onStatus?.(`Sending ${label}…`);
    try {
      const bytes = await apiBytes(item.url);
      await target.write(item.folder, item.filename, bytes, (sent) => {
        onProgress?.(done + sent, total);
      });
      done += bytes.byteLength;
      result.bytes += bytes.byteLength;
      onProgress?.(done, total);
      uploaded.push({ id: item.id, filename: item.filename });
    } catch (e) {
      result.failures.push({ filename: item.filename, error: e.message || String(e) });
    }
  }
  if (uploaded.length > 0) {
    await apiJson("/music/mark-uploaded", { items: uploaded });
    result.added = uploaded.length;
  }

  // 3. Playlists, written last so their entries resolve.
  for (const item of plan.playlists || []) {
    cancelToken?.throwIfCancelled();
    onStatus?.(`Writing ${item.filename}…`);
    try {
      await target.write(item.folder, item.filename, new TextEncoder().encode(item.content));
      result.playlists += 1;
    } catch (e) {
      result.failures.push({ filename: item.filename, error: e.message || String(e) });
    }
  }

  return result;
}

// --- the whole enchilada -------------------------------------------------------

/**
 * One-shot sync: import new files, push pending workouts/courses/schedule,
 * clean up files marked for deletion. onStatus(message) streams progress.
 *
 * Returns { found, imported, duplicates, pushed, scheduled, cleaned,
 *           failures: [{name?, filename?, error}] }.
 */
export async function runFullSync(connection, { onStatus = null, cancelToken = null } = {}) {
  const summary = {
    found: 0, imported: 0, duplicates: 0,
    pushed: 0, scheduled: false, cleaned: 0,
    agps: false,
    failures: [],
  };

  // 1. Import: scan the data folders, skip known files, upload the rest.
  const files = await scanForImport(connection, { onStatus, cancelToken });
  summary.found = files.length;

  const known = await precheckFiles(files);
  const toImport = known === null ? files : files.filter((f) => !known.has(f.name));
  summary.duplicates = files.length - toImport.length;

  if (toImport.length > 0) {
    let done = 0;
    const totals = await uploadFiles(toImport, {
      cancelToken,
      onFile: (f, phase) => {
        if (phase === "done" || phase === "error") done += 1;
        onStatus?.(`Importing ${Math.min(done + 1, toImport.length)}/${toImport.length}: ${f.name}`);
      },
    });
    summary.imported = totals.saved;
    summary.duplicates += totals.skipped;
    summary.failures.push(...totals.failures);
  }

  // 2. Push + cleanup. The write target is built even when nothing is
  // pending because the schedule (and a stale AGPS file) may still need
  // writing below.
  onStatus?.("Checking for workouts and courses to send…");
  let target = null;
  if (connection.kind === "mtp") {
    target = makeMtpTarget(connection.mtp, connection.storageId);
  } else {
    // The folder was picked with mode:"readwrite"; verify it stuck. A
    // requestPermission() here would need user activation, long expired.
    const perm = await connection.root.queryPermission({ mode: "readwrite" });
    if (perm === "granted") {
      target = makeFsTarget(connection.root);
    } else {
      const plan = await fetchPushPlan();
      if (plan.uploads.length > 0 || plan.deletes.length > 0) {
        summary.failures.push({
          filename: "(push skipped)",
          error: "No write access to the device folder — re-pick it and allow editing",
        });
      }
    }
  }
  if (target) {
    const push = await pushToWatch(target, { onStatus, cancelToken });
    summary.pushed = push.uploaded;
    summary.scheduled = push.scheduled;
    summary.cleaned = push.deleted;
    summary.failures.push(...push.failures);
  }

  // 3. AGPS: freshen the watch's GPS prediction file when due — same
  // conditions as the cable sync's agps_sync_cycle (enabled + older than
  // the refresh interval). The backend downloads; we just write.
  if (target) {
    cancelToken?.throwIfCancelled();
    try {
      const agps = await apiJson("/device-sync/agps");
      if (agps.due && agps.data_b64) {
        onStatus?.(`Updating GPS data (${agps.filename})…`);
        await target.write(agps.folder, agps.filename, b64ToBytes(agps.data_b64));
        await apiJson("/device-sync/agps-synced", {});
        summary.agps = true;
      }
    } catch (e) {
      if (e instanceof SyncCancelled) throw e;
      summary.failures.push({ filename: "AGPS", error: e.message || String(e) });
    }
  }

  // 4. Record completion so the sidebar indicator and /sync/status treat
  // browser syncs like cable syncs.
  try { await apiJson("/device-sync/synced", {}); } catch { /* cosmetic */ }

  return summary;
}
