// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shown exactly once, right after account creation succeeds. The server
// generates this key to unlock the account's data if the password is ever
// forgotten, but never stores it in any recoverable form and can never show
// it again — losing both the password and this key means the account's data
// is permanently unrecoverable, by design (see backend/app/services/user_crypto.py).
// The wizard withholds `onContinue` (which flips the app into "authed" and
// navigates away) until the user explicitly confirms they've saved it.
import { useState } from "react";

export default function RecoveryKeyReveal({ recoveryKey, onContinue }) {
  const [confirmed, setConfirmed] = useState(false);
  const [copied, setCopied]       = useState(false);

  function copy() {
    navigator.clipboard?.writeText(recoveryKey).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  function download() {
    const blob = new Blob(
      [`Tracks recovery key\n\n${recoveryKey}\n\nKeep this somewhere safe — it's the only way to recover your data if you forget your password. Tracks cannot recover it for you.\n`],
      { type: "text/plain" }
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "tracks-recovery-key.txt";
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-2.5 py-2">
        Your activity data is encrypted with a key only your password can unlock.
        If you forget your password, these 12 words are the <strong>only</strong> other
        way in — not even the server admin can reset it for you. They won't be shown again.
      </p>

      <div className="font-mono text-sm leading-relaxed break-words bg-slate-100 dark:bg-slate-800 border border-slate-300 dark:border-slate-700 rounded-lg px-3 py-2.5 text-slate-900 dark:text-white select-all">
        {recoveryKey}
      </div>
      <p className="text-xs text-slate-400 dark:text-slate-500 -mt-2">Write these down in order, somewhere safe.</p>

      <div className="flex gap-2">
        <button type="button" onClick={copy}
          className="btn btn-neutral flex-1">
          {copied ? "Copied!" : "Copy"}
        </button>
        <button type="button" onClick={download}
          className="btn btn-neutral flex-1">
          Download
        </button>
      </div>

      <label className="flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300 cursor-pointer">
        <input
          type="checkbox"
          checked={confirmed}
          onChange={e => setConfirmed(e.target.checked)}
          className="mt-0.5"
        />
        I've saved my recovery key somewhere safe
      </label>

      <button type="button" disabled={!confirmed} onClick={onContinue}
        className="btn btn-primary w-full">
        Continue
      </button>
    </div>
  );
}
