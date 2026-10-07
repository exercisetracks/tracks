// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The "Backing up 412 of 2,994 files" pill at the bottom of the screen — the
// phone's ProgressPill, on the desktop. In the app shell rather than on the
// Settings page because the job outlives the page (see lib/backup/job.js).
// Shown only once a job has run for SHOW_AFTER_MS, so a quick one does not
// flash it.
import { useEffect, useState } from "react";
import ProgressRing from "../pages/maps/components/ProgressRing";
import { jobLabel, useBackupJob } from "../lib/backup/job";

const SHOW_AFTER_MS = 700;

export default function BackupProgressPill() {
  const { running } = useBackupJob();
  const [visible, setVisible] = useState(false);
  const active = running != null;
  useEffect(() => {
    if (!active) { setVisible(false); return undefined; }
    const t = setTimeout(() => setVisible(true), SHOW_AFTER_MS);
    return () => clearTimeout(t);
  }, [active]);

  if (!running || !visible) return null;
  const percent = running.total ? Math.round((running.done / running.total) * 100) : null;
  return (
    <div role="status" aria-live="polite"
      className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 flex items-center gap-3 px-4 py-2.5 rounded-full shadow-lg bg-slate-900 text-slate-50 dark:bg-slate-100 dark:text-slate-900">
      <ProgressRing percent={percent} size={18} stroke={2.5} className="text-accent-400 dark:text-accent-600" />
      <span className="text-sm">{jobLabel(running)}</span>
      {percent != null && <span className="text-sm text-accent-400 dark:text-accent-600">{percent}%</span>}
    </div>
  );
}
