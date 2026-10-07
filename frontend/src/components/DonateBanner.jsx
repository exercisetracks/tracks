// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A gentle, occasional nudge to support the project: first shown a week
// after someone starts using Tracks (not on day one, which would read as
// asking for money before earning any trust), then at most once a month
// after that a dismissal has been recorded.
//
// Entirely client-side — nothing about this is sent to the server or
// tracked anywhere but this browser's own storage. localStorage being
// unavailable just means the banner never shows, the same safe failure
// ServerUpdateBanner uses for its own dismissal state.
import { useEffect, useState } from "react";

const FIRST_SEEN_KEY = "tracks:donate-first-seen";
const LAST_SHOWN_KEY = "tracks:donate-last-dismissed";
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;
const MONTH_MS = 30 * 24 * 60 * 60 * 1000;

function readTimestamp(key) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? Number(raw) : null;
  } catch {
    return null;
  }
}

function writeTimestamp(key, value) {
  try { localStorage.setItem(key, String(value)); } catch { /* see header */ }
}

export default function DonateBanner() {
  const [firstSeen, setFirstSeen] = useState(() => readTimestamp(FIRST_SEEN_KEY));
  const [lastShown, setLastShown] = useState(() => readTimestamp(LAST_SHOWN_KEY));

  useEffect(() => {
    if (firstSeen == null) {
      const now = Date.now();
      writeTimestamp(FIRST_SEEN_KEY, now);
      setFirstSeen(now);
    }
  }, [firstSeen]);

  const due = firstSeen != null
    && Date.now() - firstSeen >= WEEK_MS
    && (lastShown == null || Date.now() - lastShown >= MONTH_MS);
  if (!due) return null;

  function dismiss() {
    const now = Date.now();
    writeTimestamp(LAST_SHOWN_KEY, now);
    setLastShown(now);
  }

  return (
    <div className="border-b border-accent-200 bg-accent-50/70 px-6 py-4 text-base text-slate-700 dark:border-accent-800 dark:bg-accent-900/20 dark:text-slate-200">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-5 gap-y-3">
        <span className="flex-1 min-w-0">
          Tracks is free, ad-free, and always will be. If it's useful to you, a donation helps keep it going.
        </span>
        <a href="https://ko-fi.com/hawkf" target="_blank" rel="noreferrer" className="btn btn-primary">
          Donate
        </a>
        <button type="button" className="btn btn-neutral" onClick={dismiss}>Not now</button>
      </div>
    </div>
  );
}
