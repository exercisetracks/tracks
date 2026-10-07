// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "A newer Tracks is out" across the top of every page — for admins only,
// since only they can act on it: updating is a command on the machine that
// runs the container (see UpdateCommand for why the server will not do it
// itself). Everyone else sees the same fact, quietly, in Settings › Version.
//
// Dismissed per release, in this browser: hiding 1.2.0 does not hide 1.3.0.
// localStorage only remembers that choice — if it is unavailable the banner
// simply comes back, which is the safe failure.
import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import UpdateCommand from "./settings/UpdateCommand";

const DISMISSED_KEY = "tracks:update-banner-dismissed";

function readDismissed() {
  try { return localStorage.getItem(DISMISSED_KEY); } catch { return null; }
}

export default function ServerUpdateBanner() {
  const { user } = useAuth();
  const [status, setStatus] = useState(null);
  const [open, setOpen] = useState(false);
  const [dismissed, setDismissed] = useState(readDismissed);

  useEffect(() => {
    if (!user?.is_admin) return;
    api.getVersionStatus().then(setStatus).catch(() => {});
  }, [user?.is_admin]);

  const latest = status?.latest?.version;
  if (!user?.is_admin || !status?.server_update_available || dismissed === latest) return null;

  function dismiss() {
    try { localStorage.setItem(DISMISSED_KEY, latest); } catch { /* see header */ }
    setDismissed(latest);
  }

  return (
    <div className="border-b border-amber-200 bg-amber-50 px-5 py-2.5 text-sm text-amber-900 dark:border-amber-900/50 dark:bg-amber-950/40 dark:text-amber-200">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2">
        <span className="flex-1 min-w-0">
          Tracks <span className="font-semibold">{latest}</span> is available — this server runs {status.server_version}.
        </span>
        <a href={status.latest.url} target="_blank" rel="noreferrer" className="btn-neutral btn-sm">Release notes</a>
        <button type="button" className="btn-tonal btn-sm" onClick={() => setOpen(o => !o)}>
          {open ? "Hide" : "How to update"}
        </button>
        <button type="button" className="btn-neutral btn-sm" onClick={dismiss}>Dismiss</button>
      </div>
      {open && (
        <div className="mx-auto mt-3 max-w-5xl">
          <div className="card"><UpdateCommand /></div>
        </div>
      )}
    </div>
  );
}
