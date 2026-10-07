// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The sidebar's status rows: what the server is doing for this user right now.
//
// One row per thing actually under way — importing files, a watch syncing
// over USB, the phone syncing — each from a heartbeat the server keeps for
// that user (backend services/activity_status.py), so a row disappears by
// itself when the work stops. It replaced a spinner driven by "any import row
// unprocessed" and one instance-wide "a watch is docked" flag, which spun for
// days over files waiting on a locked vault and spun every account's sidebar
// for anyone's watch.
//
// Files the server cannot open yet are shown as waiting, without a spinner,
// because nothing is happening to them until the next sign-in unlocks them.
// A finished watch or phone sync leaves a tick for a few seconds.

import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";

const BUSY_MS = 2_000;
const IDLE_MS = 6_000;
const DONE_MS = 12_000;

/** Poll the status: quickly while something is happening, gently otherwise,
 *  and not at all while the tab is hidden. */
export function useServerActivity() {
  const [status, setStatus] = useState(null);
  useEffect(() => {
    let cancelled = false;
    let timer;
    const tick = async () => {
      let busy = false;
      if (!document.hidden) {
        try {
          const s = await api.getSyncStatus();
          if (cancelled) return;
          setStatus(s);
          const a = s.activity ?? {};
          busy = Boolean(a.importing || a.watch || a.phone);
        } catch { /* keep the last answer */ }
      }
      if (!cancelled) timer = setTimeout(tick, busy ? BUSY_MS : IDLE_MS);
    };
    tick();
    const onVisible = () => { if (!document.hidden) { clearTimeout(timer); tick(); } };
    document.addEventListener("visibilitychange", onVisible);
    return () => { cancelled = true; clearTimeout(timer); document.removeEventListener("visibilitychange", onVisible); };
  }, []);
  return status;
}

/** True for DONE_MS after `value` changes from a previous non-null value. */
function useJustChanged(value, storageKey) {
  const [fresh, setFresh] = useState(false);
  const last = useRef(undefined);
  useEffect(() => {
    if (value == null) return;
    if (last.current === undefined) {
      // First sight this page load: compare with what an earlier load saw, so
      // a reload does not replay "synced" for something old.
      let seen = null;
      try { seen = localStorage.getItem(storageKey); } catch { /* blocked storage */ }
      last.current = seen;
    }
    if (value === last.current) return;
    const replay = last.current !== null;
    last.current = value;
    try { localStorage.setItem(storageKey, value); } catch { /* blocked storage */ }
    if (!replay) return;
    setFresh(true);
    const t = setTimeout(() => setFresh(false), DONE_MS);
    return () => clearTimeout(t);
  }, [value, storageKey]);
  return fresh;
}

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

function Tick() {
  return (
    <svg className="h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  );
}

function Lock() {
  return (
    <svg className="h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="11" width="14" height="10" rx="2" />
      <path strokeLinecap="round" d="M8 11V7a4 4 0 118 0v4" />
    </svg>
  );
}

const TONE = {
  busy: "bg-sky-50 dark:bg-sky-900/20 text-sky-700 dark:text-sky-300",
  done: "bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-300",
  wait: "bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-300",
};

function Row({ tone, icon, children, title, onClick }) {
  const cls = `w-full flex items-center gap-2 px-2.5 py-1.5 mb-1 rounded-lg text-xs font-medium text-left ${TONE[tone]}`;
  return onClick
    ? <button type="button" className={cls} title={title} onClick={onClick}>{icon}<span>{children}</span></button>
    : <div className={cls} title={title} role="status">{icon}<span>{children}</span></div>;
}

export default function SidebarStatus({ status, onUnlock }) {
  const a = status?.activity;
  const watchDone = useJustChanged(status?.last_synced_at ?? null, "tracks_last_synced_at");
  const phoneDone = useJustChanged(a && !a.phone ? a.phone_last_at : null, "tracks_last_phone_sync_at");
  if (!a) return null;

  const imp = a.importing;
  return (
    <>
      {imp && (
        <Row tone="busy" icon={<Spinner />}>
          {imp.total > 1 ? `Importing ${Math.min(imp.done + 1, imp.total)} of ${imp.total}…` : "Importing…"}
        </Row>
      )}
      {!imp && a.waiting > 0 && (
        <Row
          tone="wait"
          icon={<Lock />}
          onClick={onUnlock}
          title="These files are stored encrypted and can only be read once you sign in again. Click to sign in."
        >
          {a.waiting} {a.waiting === 1 ? "file" : "files"} waiting — sign in to import
        </Row>
      )}
      {a.watch
        ? <Row tone="busy" icon={<Spinner />}>Watch syncing over USB…</Row>
        : watchDone && <Row tone="done" icon={<Tick />}>Watch synced</Row>}
      {a.phone
        ? <Row tone="busy" icon={<Spinner />}>Phone syncing…</Row>
        : phoneDone && <Row tone="done" icon={<Tick />}>Phone synced</Row>}
    </>
  );
}
