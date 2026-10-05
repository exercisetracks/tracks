// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Calendar-subscription controls: a "copy subscription link" button that fetches
// the user's personal .ics token on mount, plus a hover help tooltip. Fully
// self-contained (talks to the api client directly); renders nothing until the
// token loads.

import { useEffect, useState } from "react";
import { api } from "../../api/client";

function IcsTooltip() {
  return (
    <div className="relative group inline-flex items-center">
      <button
        type="button"
        className="w-4 h-4 rounded-full text-xs font-bold bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 flex items-center justify-center leading-none hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
        aria-label="About calendar sync"
      >
        ?
      </button>
      <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-64 bg-slate-900 dark:bg-slate-700 text-white text-xs rounded-lg px-2.5 py-2 shadow-xl z-20 pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity">
        Subscribe in Google Calendar, Apple Calendar, or Outlook to see your training schedule. The link stays the same as your goals change — set it up once.
        <div className="absolute top-full left-1/2 -translate-x-1/2 border-4 border-transparent border-t-slate-900 dark:border-t-slate-700" />
      </div>
    </div>
  );
}

export default function UserIcsCopy() {
  const [token,  setToken]  = useState(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.getUserIcsToken().then(setToken).catch(() => {});
  }, []);

  if (!token) return null;

  const copy = () => {
    navigator.clipboard.writeText(token.ics_url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="flex items-center gap-1.5">
      <button
        onClick={copy}
        className="btn btn-neutral btn-sm gap-1.5"
      >
        <svg className="w-3 h-3 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
        </svg>
        {copied ? "Copied!" : "Copy subscription link"}
      </button>
      <IcsTooltip />
    </div>
  );
}
