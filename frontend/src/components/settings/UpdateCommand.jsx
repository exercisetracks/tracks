// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// How to update the server, as commands to paste — shown to admins only, by
// the update banner and the Version section.
//
// The server does not update itself, and that is deliberate: replacing its
// own container would need the Docker socket mounted inside it, which is
// root on the host for anything that compromises the web app. The commands
// are the same ones the release notes and deploy/compose.yaml give, backup
// first, because an update is the moment a database migration runs.
import { useState } from "react";

const STEPS = [
  ["Back up first", "docker exec tracks tracks-backup > tracks.tar.gz"],
  ["With compose.yaml, in its folder", "docker compose pull && docker compose up -d"],
  ["With docker run", "docker pull exercisetracks/tracks:latest\n# then remove the container and re-run your docker run command"],
];

function Command({ label, text }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* clipboard needs a secure context; the text is selectable */ }
  }
  return (
    <div>
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="field-label mb-0">{label}</span>
        <button type="button" className="btn-neutral btn-sm" onClick={copy}>
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-lg bg-slate-100 px-3 py-2 font-mono text-xs text-slate-700 select-all dark:bg-slate-800 dark:text-slate-200">
        {text}
      </pre>
    </div>
  );
}

export default function UpdateCommand() {
  return (
    <div className="space-y-3">
      {STEPS.map(([label, text]) => <Command key={label} label={label} text={text} />)}
      <p className="text-xs text-slate-500 dark:text-slate-400">
        Phones keep working through the update; install the matching app once the server is on the new version.
        If your container is not named <span className="font-mono">tracks</span>, use its name instead.
      </p>
    </div>
  );
}
