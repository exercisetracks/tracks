// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One-button watch sync. Connection methods, in order of least effort:
//
//  1. Host: the garmin-sync trigger — if the watch is plugged into the
//     server, that container does everything. Always probed silently in the
//     background; it only takes over the UI when it actually finds a watch.
//  2. WebUSB/MTP: a previously-authorised watch plugged into THIS computer
//     connects silently. Skipped on Windows (its MTP driver blocks browsers).
//  3. Mounted folder: mass-storage watches (e.g. Instinct 2). The picked
//     folder handle is remembered (IndexedDB) for one-click re-syncs.
//  4. Manual .fit upload for files that aren't on any device.
//
// The last successful method is remembered and tried first on the next open.
//
// Garmin watches enumerate twice when plugged in: first as a generic GPS
// device for ~10 s, then re-appear as the named MTP device. The connect
// retry loop rides that out, re-querying authorised devices between attempts
// because re-enumeration invalidates old USBDevice handles.
import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import {
  MtpDevice,
  getAuthorizedGarminDevices,
  isWebUsbSupported,
  requestGarminDevice,
} from "../../lib/mtp";
import {
  SyncCancelled,
  isFsAccessSupported,
  isWindows,
  makeCancelToken,
  makeWriteTarget,
  pushMusic,
  runFullSync,
  uploadFiles,
} from "../../lib/deviceSync";
import { loadDeviceFolderHandle, saveDeviceFolderHandle } from "../../lib/handleStore";

const METHOD_KEY = "tracks_sync_method"; // "host" | "mtp" | "fs"
const HOST_WAIT_MS = 9000; // foreground wait when host was the last method
const HOST_WATCH_MS = 35000; // background probe window (trigger polls at 10-20s)
const CONNECT_RETRY_MS = 2500;
const CONNECT_ATTEMPTS = 8; // ~20 s: covers the double-enumeration dance

function formatMb(bytes) {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// After a successful sync the MTP device stays HELD — open, interface
// claimed — across modal closes. Releasing it invites whatever else wants the
// watch (a desktop's GVFS, or the server's own cable sync when Tracks runs on
// this machine) to claim the interface, which then blocks every further
// browser sync until replug. Holding also makes re-syncs instant. The claim
// dies naturally on unplug or tab close; on failure we close eagerly because
// the protocol state is unknown.
//
// The MTP *session*, however, is closed after every successful sync (and
// reopened on the next one): the session close is what tells the watch the
// sync is over, so it processes GARMIN/NewFiles — the cable sync gets the
// same signal from libmtp's device release.
let heldMtp = null;

function releaseHeldMtp() {
  const dev = heldMtp;
  heldMtp = null;
  return dev ? dev.close().catch(() => {}) : Promise.resolve();
}

if (typeof navigator !== "undefined" && navigator.usb) {
  navigator.usb.addEventListener("disconnect", (e) => {
    if (heldMtp && heldMtp.usb === e.device) heldMtp = null; // gone; nothing to close
  });
}

async function connectMtp(usbDevice) {
  if (heldMtp && heldMtp.usb === usbDevice) {
    try {
      // Reopens the session (or no-ops with SessionAlreadyOpen if one survived).
      await heldMtp.openSession();
      const storageId = (await heldMtp.getStorageIds())[0];
      if (storageId != null) {
        return { mtp: heldMtp, storageId, label: heldMtp.deviceLabel };
      }
    } catch { /* stale (e.g. watch rebooted) — reconnect fresh below */ }
  }
  await releaseHeldMtp();
  const mtp = new MtpDevice(usbDevice);
  await mtp.open();
  try {
    await mtp.openSession();
    const storageId = (await mtp.getStorageIds())[0];
    if (storageId == null) throw new Error("No storage found on the device");
    const info = await mtp.getDeviceInfo().catch(() => null);
    const label = info
      ? `${info.manufacturer} ${info.model}`.trim()
      : usbDevice.productName || "Garmin device";
    mtp.deviceLabel = label;
    heldMtp = mtp;
    return { mtp, storageId, label };
  } catch (e) {
    await mtp.close().catch(() => {});
    throw e;
  }
}

// `mode` picks what the connection is used for once it is established. The
// connection machinery — double-enumeration retries, the held claim, the
// remembered method — is identical either way; only the payload differs.
//   "sync"  full two-way sync (activities in, workouts/courses out)
//   "music" push the music library only
export default function WatchSyncModal({ open, autoDevice, onClose, onSynced, mode = "sync" }) {
  const music = mode === "music";
  // phase: host-checking | choose | connecting | running | done | error
  const [phase, setPhase] = useState("host-checking");
  const [statusLine, setStatusLine] = useState("");
  const [deviceLabel, setDeviceLabel] = useState(null);
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState(null);
  const [rememberedFolder, setRememberedFolder] = useState(null);
  const [hostProbing, setHostProbing] = useState(false);
  // Bytes sent / total for the music push, which runs long enough to need a bar.
  const [progress, setProgress] = useState(null);
  // Shown in the title so a remembered method announces itself, e.g.
  // "Sync watch via USB". Empty on the choose screen.
  const [methodLabel, setMethodLabel] = useState("");

  const cancelRef = useRef(null);
  const closedRef = useRef(false);
  const fileInputRef = useRef(null);
  const phaseRef = useRef(phase);
  useEffect(() => { phaseRef.current = phase; }, [phase]);

  // Cancels a running sync. The held MTP connection is NOT closed here — a
  // successful sync keeps it (see heldMtp); a cancelled one is closed by
  // runBrowserSync's failure path once runFullSync throws.
  const cleanup = () => {
    if (cancelRef.current) cancelRef.current.cancelled = true;
  };

  const close = () => {
    closedRef.current = true;
    cleanup();
    onClose();
  };

  // --- flow steps -----------------------------------------------------------

  const toChoose = () => {
    setMethodLabel("");
    setPhase("choose");
  };

  const finish = (result, method = null) => {
    if (method) localStorage.setItem(METHOD_KEY, method);
    setSummary(result);
    setPhase("done");
    onSynced?.();
  };

  const fail = (e) => {
    if (e instanceof SyncCancelled || closedRef.current) return;
    setError(e.message || String(e));
    setPhase("error");
  };

  const runBrowserSync = async (connection, label, method) => {
    setDeviceLabel(label);
    setPhase("running");
    const token = makeCancelToken();
    cancelRef.current = token;
    window.dispatchEvent(new CustomEvent("fitimport:start"));
    let ok = false;
    try {
      const result = music
        ? await pushMusic(await makeWriteTarget(connection), {
            onStatus: setStatusLine,
            onProgress: (sent, total) => setProgress({ sent, total }),
            cancelToken: token,
          })
        : await runFullSync(connection, {
            onStatus: setStatusLine,
            cancelToken: token,
          });
      ok = true;
      finish({ kind: music ? "music" : "browser", ...result }, method);
    } catch (e) {
      fail(e);
    } finally {
      window.dispatchEvent(new CustomEvent("fitimport:end"));
      if (connection.kind === "mtp") {
        if (ok) {
          // Keep the device held (claim included) but end the session so the
          // watch processes NewFiles. If even that fails, release fully.
          try { await connection.mtp.closeSession(); } catch { releaseHeldMtp(); }
        } else {
          // Failure/cancel: protocol state unknown — close it all.
          releaseHeldMtp();
        }
      }
    }
  };

  // MTP with settle/retry: watches take ~10 s to re-enumerate as MTP after
  // plug-in, and a claim can fail while GVFS briefly probes the device.
  const startMtp = async (initialDevice = null) => {
    setPhase("connecting");
    setMethodLabel("via USB");
    setStatusLine("Connecting to watch…");
    let device = initialDevice;
    try {
      if (!device) {
        const authorized = await getAuthorizedGarminDevices();
        device = authorized[0] || (await requestGarminDevice());
      }
    } catch (e) {
      if (e.name === "NotFoundError") { toChoose(); return; } // picker cancelled
      fail(e);
      return;
    }
    for (let attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
      if (closedRef.current) return;
      try {
        const { mtp, storageId, label } = await connectMtp(device);
        if (closedRef.current) return; // connection stays held for next time
        await runBrowserSync({ kind: "mtp", mtp, storageId }, label, "mtp");
        return;
      } catch (e) {
        if (attempt === CONNECT_ATTEMPTS) { fail(e); return; }
        setStatusLine(`Waiting for the watch to get ready… (${attempt}/${CONNECT_ATTEMPTS})`);
        await sleep(CONNECT_RETRY_MS);
        // Re-enumeration invalidates the old handle; pick up the fresh one.
        const authorized = await getAuthorizedGarminDevices().catch(() => []);
        if (authorized.length > 0) device = authorized[0];
      }
    }
  };

  const startFolder = async () => {
    setMethodLabel("via device folder");
    let handle;
    try {
      // readwrite up front: permission prompts need user activation, which
      // is long gone by the time the push phase runs.
      handle = await window.showDirectoryPicker({ mode: "readwrite" });
    } catch (e) {
      if (e.name !== "AbortError") fail(e);
      return;
    }
    await saveDeviceFolderHandle(handle);
    setRememberedFolder(handle);
    await runBrowserSync({ kind: "fs", root: handle }, `Folder: ${handle.name}`, "fs");
  };

  // One-click re-sync of the remembered folder (button click = the user
  // activation that requestPermission needs).
  const resyncRememberedFolder = async () => {
    const handle = rememberedFolder;
    if (!handle) return;
    setMethodLabel("via device folder");
    try {
      let perm = await handle.queryPermission({ mode: "readwrite" });
      if (perm !== "granted") perm = await handle.requestPermission({ mode: "readwrite" });
      if (perm !== "granted") { setMethodLabel(""); return; }
    } catch {
      setRememberedFolder(null);
      setMethodLabel("");
      return;
    }
    await runBrowserSync({ kind: "fs", root: handle }, `Folder: ${handle.name}`, "fs");
  };

  const startManualUpload = async (fileList) => {
    const files = Array.from(fileList)
      .filter((f) => f.name.toLowerCase().endsWith(".fit"))
      .map((f) => ({
        name: f.name,
        size: f.size,
        read: async () => new Uint8Array(await f.arrayBuffer()),
      }));
    if (files.length === 0) return;
    setDeviceLabel("Manual upload");
    setMethodLabel("via manual upload");
    setPhase("running");
    const token = makeCancelToken();
    cancelRef.current = token;
    window.dispatchEvent(new CustomEvent("fitimport:start"));
    try {
      let done = 0;
      const totals = await uploadFiles(files, {
        cancelToken: token,
        onFile: (f, p) => {
          if (p === "done" || p === "error") done += 1;
          setStatusLine(`Uploading ${Math.min(done + 1, files.length)}/${files.length}: ${f.name}`);
        },
      });
      finish({
        kind: "browser",
        found: files.length,
        imported: totals.saved,
        duplicates: totals.skipped,
        pushed: 0,
        scheduled: false,
        cleaned: 0,
        failures: totals.failures,
      });
    } catch (e) {
      fail(e);
    } finally {
      window.dispatchEvent(new CustomEvent("fitimport:end"));
    }
  };

  // Background host probe: fire the trigger and watch for a sync starting.
  // Takes over the UI only while the user hasn't committed to another path.
  const startHostWatcher = async (foreground) => {
    let baseline = null;
    try {
      baseline = await api.getSyncStatus();
      await api.triggerGarminSync();
    } catch {
      if (foreground) toChoose();
      return;
    }
    setHostProbing(true);
    const foregroundDeadline = Date.now() + HOST_WAIT_MS;
    const deadline = Date.now() + HOST_WATCH_MS;
    try {
      while (Date.now() < deadline) {
        if (closedRef.current) return;
        if (foreground && phaseRef.current === "host-checking" && Date.now() > foregroundDeadline) {
          toChoose(); // stop blocking; keep probing quietly
        }
        await sleep(1750);
        let s;
        try { s = await api.getSyncStatus(); } catch { continue; }
        if (s.is_syncing) {
          if (phaseRef.current === "host-checking" || phaseRef.current === "choose") {
            await followHostSync(baseline);
          }
          return;
        }
      }
      if (foreground && phaseRef.current === "host-checking") toChoose();
    } finally {
      setHostProbing(false);
    }
  };

  const followHostSync = async (baseline) => {
    setPhase("host-syncing");
    setMethodLabel("via server");
    setStatusLine("Watch found on the server — syncing there…");
    const deadline = Date.now() + 5 * 60 * 1000;
    while (Date.now() < deadline) {
      if (closedRef.current) return;
      await sleep(2000);
      let s;
      try { s = await api.getSyncStatus(); } catch { continue; }
      if (s.last_synced_at && s.last_synced_at !== baseline?.last_synced_at) break;
      if (!s.is_syncing && !s.importing) break;
    }
    finish({ kind: "host" }, "host");
  };

  // --- lifecycle -------------------------------------------------------------

  useEffect(() => {
    if (!open) return;
    closedRef.current = false;
    setError(null);
    setSummary(null);
    setDeviceLabel(null);
    setStatusLine("");
    setHostProbing(false);
    setMethodLabel("");
    setProgress(null);

    loadDeviceFolderHandle().then((h) => { if (!closedRef.current) setRememberedFolder(h); });

    if (autoDevice) {
      // The watch was just plugged into THIS computer — skip the host probe.
      startMtp(autoDevice);
      return () => { closedRef.current = true; cleanup(); };
    }

    // The host probe fires the server's cable-sync trigger, so it only runs
    // when we are NOT committing to a browser sync: on a same-machine setup
    // the trigger would send the server chasing the very watch the browser
    // is about to claim, and the loser's retries block the winner.
    const lastMethod = localStorage.getItem(METHOD_KEY);
    if (lastMethod === "mtp" && isWebUsbSupported() && !isWindows()) {
      setPhase("connecting");
      setMethodLabel("via USB");
      setStatusLine("Connecting to watch…");
      getAuthorizedGarminDevices()
        .then((devices) => {
          if (closedRef.current) return;
          if (devices.length > 0) {
            startMtp(devices[0]);
          } else {
            if (!music) startHostWatcher(false);
            toChoose();
          }
        })
        .catch(() => { if (!music) startHostWatcher(false); toChoose(); });
    } else if (lastMethod === "fs" && isFsAccessSupported()) {
      setPhase("connecting");
      setMethodLabel("via device folder");
      setStatusLine("Checking the remembered device folder…");
      loadDeviceFolderHandle().then(async (h) => {
        if (closedRef.current) return;
        if (h) {
          setRememberedFolder(h);
          try {
            const perm = await h.queryPermission({ mode: "readwrite" });
            if (perm === "granted") {
              await runBrowserSync({ kind: "fs", root: h }, `Folder: ${h.name}`, "fs");
              return;
            }
          } catch { /* stale handle */ }
        }
        if (!music) startHostWatcher(false);
        toChoose(); // one click on "Sync <name>" re-grants
      });
    } else if (music) {
      // The server's cable sync has no music phase, so probing for it would
      // fire a trigger that cannot do the job and would claim the watch the
      // browser is about to use.
      toChoose();
    } else {
      setPhase("host-checking");
      setMethodLabel("via server");
      setStatusLine("Checking for a watch on the server…");
      startHostWatcher(true);
    }
    return () => { closedRef.current = true; cleanup(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open) return null;

  const usbOk = isWebUsbSupported() && !isWindows();
  const fsOk = isFsAccessSupported();
  const insecure = typeof window !== "undefined" && !window.isSecureContext;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-3.5" onClick={close}>
      <div
        className="w-full max-w-md bg-white dark:bg-slate-900 rounded-2xl shadow-xl p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
            {music ? "Send music to watch" : "Sync watch"}{methodLabel ? ` ${methodLabel}` : ""}
          </h2>
          <button onClick={close} className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">×</button>
        </div>

        {deviceLabel && (
          <p className="text-sm font-medium text-slate-600 dark:text-slate-300">{deviceLabel}</p>
        )}

        {music && phase === "running" && progress && progress.total > 0 && (
          <div className="space-y-1">
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
              <div
                className="h-full rounded-full bg-accent-500 transition-all duration-300"
                style={{ width: `${Math.min(100, (progress.sent / progress.total) * 100)}%` }}
              />
            </div>
            <p className="text-xs text-slate-400 dark:text-slate-500">
              {formatMb(progress.sent)} of {formatMb(progress.total)}
            </p>
          </div>
        )}

        {(phase === "host-checking" || phase === "host-syncing" || phase === "connecting" || phase === "running") && (
          <div className="space-y-3">
            <div className="flex items-center gap-3">
              <div className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-accent-500 border-t-transparent" />
              <p className="text-sm text-slate-600 dark:text-slate-400 truncate">{statusLine || "Working…"}</p>
            </div>
            {phase === "host-checking" && (
              <button
                type="button"
                onClick={toChoose}
                className="btn btn-neutral btn-sm"
              >
                Skip — the watch isn't on the server
              </button>
            )}
            {phase === "connecting" && (
              <p className="text-xs text-slate-400 dark:text-slate-500">
                Watches take ~10 seconds after plug-in to switch into MTP mode
                (they first appear as a generic GPS device, then reconnect with
                their real name). Make sure USB mode is set to MTP on the watch.
              </p>
            )}
            {phase === "host-syncing" && (
              <p className="text-xs text-slate-400 dark:text-slate-500">
                The server is pulling activities and pushing workouts over its
                own USB connection. You can close this — it finishes on its own.
              </p>
            )}
          </div>
        )}

        {phase === "choose" && (
          <div className="space-y-3">
            {insecure && (
              <p className="text-xs text-red-600 dark:text-red-400">
                This page isn't served over HTTPS, so the browser's USB and
                file APIs are unavailable.
              </p>
            )}
            {fsOk && rememberedFolder && (
              <button
                type="button"
                onClick={resyncRememberedFolder}
                className="btn btn-primary w-full"
              >
                Sync "{rememberedFolder.name}" again
              </button>
            )}
            {usbOk && (
              <button
                type="button"
                onClick={() => startMtp()}
                className={`btn w-full ${rememberedFolder ? "btn-tonal" : "btn-primary"}`}
              >
                Connect watch (USB)
              </button>
            )}
            {!usbOk && !insecure && isWindows() && (
              <p className="text-xs text-slate-400 dark:text-slate-500">
                Windows blocks browser access to MTP watches — use the mounted
                folder below, or plug the watch into the server.
              </p>
            )}
            {fsOk && (
              <button
                type="button"
                onClick={startFolder}
                className="btn btn-tonal w-full"
              >
                {rememberedFolder ? "Pick a different device folder" : "Open mounted device folder"}
              </button>
            )}
            {!music && (
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="btn btn-neutral w-full"
              >
                Upload .fit files manually
              </button>
            )}
            <input
              ref={fileInputRef}
              type="file"
              accept=".fit,.FIT"
              multiple
              className="hidden"
              onChange={(e) => { startManualUpload(e.target.files); e.target.value = ""; }}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">
              USB works for MTP watches (Fenix, Epix, FR 9xx — set USB mode to
              MTP first). Watches that mount as a drive (e.g. Instinct 2) use
              the folder option.
              {hostProbing && " Still watching for a watch on the server, too."}
            </p>
          </div>
        )}

        {phase === "done" && summary && (
          <div className="space-y-3">
            {summary.kind === "music" ? (
              <div className="text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <p className="font-medium text-accent-700 dark:text-accent-400">Music sent</p>
                <p>
                  {summary.added} track{summary.added !== 1 ? "s" : ""} added
                  {summary.bytes > 0 && ` (${formatMb(summary.bytes)})`}
                </p>
                {summary.removed > 0 && <p>{summary.removed} removed</p>}
                {summary.playlists > 0 && (
                  <p>{summary.playlists} playlist{summary.playlists !== 1 ? "s" : ""} written</p>
                )}
                {(summary.added > 0 || summary.playlists > 0) && (
                  <p className="pt-1 text-xs text-amber-600 dark:text-amber-400">
                    On the watch, set Music Providers to "My Music" — tracks are
                    on the device but stay hidden until you do.
                  </p>
                )}
                {summary.failures.length > 0 && (
                  <div className="text-xs text-red-600 dark:text-red-400 space-y-0.5 pt-1">
                    {summary.failures.slice(0, 8).map((f, i) => (
                      <p key={i}>{f.filename}: {f.error}</p>
                    ))}
                  </div>
                )}
              </div>
            ) : summary.kind === "host" ? (
              <p className="text-sm text-accent-700 dark:text-accent-400">
                Synced on the server — activities import automatically and
                pending workouts/courses were pushed over the cable.
              </p>
            ) : (
              <div className="text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <p className="font-medium text-accent-700 dark:text-accent-400">Sync complete</p>
                <p>{summary.imported} new file{summary.imported !== 1 ? "s" : ""} imported ({summary.duplicates} already known)</p>
                {summary.pushed > 0 && (
                  <p>{summary.pushed} file{summary.pushed !== 1 ? "s" : ""} sent to watch</p>
                )}
                {summary.scheduled && <p>Training calendar updated on watch</p>}
                {summary.cleaned > 0 && <p>{summary.cleaned} old file{summary.cleaned !== 1 ? "s" : ""} removed</p>}
                {summary.agps && <p>GPS prediction data updated</p>}
                {summary.failures.length > 0 && (
                  <div className="text-xs text-red-600 dark:text-red-400 space-y-0.5 pt-1">
                    {summary.failures.slice(0, 8).map((f, i) => (
                      <p key={i}>{f.name || f.filename}: {f.error}</p>
                    ))}
                  </div>
                )}
              </div>
            )}
            <button
              type="button"
              onClick={close}
              className="btn btn-neutral w-full"
            >
              Done
            </button>
          </div>
        )}

        {phase === "error" && (
          <div className="space-y-3">
            <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
            <button
              type="button"
              onClick={() => { setError(null); toChoose(); }}
              className="btn btn-neutral w-full"
            >
              Try another way
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
