// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Copy .ics link" control for the selected goal. Self-contained: fetches the
// goal's calendar-subscription token from the api client on mount/goal-change,
// and copies the resulting ics_url to the clipboard (with a transient "Copied!"
// confirmation). Renders nothing until the token has loaded. Talks to the api
// directly rather than via props, so it can be dropped in wherever a goalId
// is available.

import { useEffect, useState } from "react";
import { api } from "../../api/client";

export default function IcsExport({ goalId }) {
  const [token, setToken] = useState(null);
  const [copied, setCopied] = useState(false);

  // (Re)load the subscription token whenever the target goal changes.
  useEffect(() => {
    if (!goalId) return;
    api.getIcsToken(goalId).then(setToken).catch(() => {});
  }, [goalId]);

  if (!token) return null;

  const copy = () => {
    navigator.clipboard.writeText(token.ics_url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
      <svg className="w-3.5 h-3.5 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
      </svg>
      <button onClick={copy} className="btn btn-tonal btn-sm">
        {copied ? "Copied!" : "Copy .ics link"}
      </button>
      <span className="text-slate-300 dark:text-slate-600 truncate max-w-48 hidden sm:block">{token.ics_url}</span>
    </div>
  );
}
