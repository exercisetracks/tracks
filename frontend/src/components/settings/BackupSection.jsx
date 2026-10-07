// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Backup and restore — the phone's Settings backup, on the desktop, writing
// and reading the same files (see lib/backup/). A backup made here restores
// on a phone and the other way round.
import { useRef, useState } from "react";
import { useAuth } from "../../auth/AuthContext";
import ConfirmDialog from "../ConfirmDialog";
import { Section } from "./primitives";
import { todayIso } from "../../lib/today";
import {
  checkRestore, chooseBackupTarget, lastBackupAt, runBackup, runRestore, useBackupJob,
} from "../../lib/backup/job";

/**
 * The shortest passphrase accepted, as on the phone. The file is only as
 * strong as this — the key derivation slows guessing, it cannot make "1234"
 * hard — and the file may sit in someone's cloud folder for years.
 */
const MIN_PASSPHRASE = 10;

export default function BackupSection() {
  const { user } = useAuth();
  const { running, result } = useBackupJob();
  const [asking, setAsking] = useState(null); // null | { restoring, file? }
  const [otherServer, setOtherServer] = useState(null); // { file, passphrase } awaiting a yes
  const picker = useRef(null);
  const last = lastBackupAt();

  async function confirmPassphrase(passphrase) {
    const ask = asking;
    setAsking(null);
    if (!ask.restoring) {
      // Straight from the click: the save picker needs its user activation.
      const target = await chooseBackupTarget(`tracks-${todayIso()}.tracksbackup`);
      if (target) runBackup({ user, passphrase, target });
      return;
    }
    const check = await checkRestore({ user, file: ask.file, passphrase });
    if (check.error) return;
    if (check.differentServer) setOtherServer({ file: ask.file, passphrase });
    else runRestore({ file: ask.file, passphrase });
  }

  const status = running
    ? running.kind === "backup"
      ? "Writing backup… it carries on if you open another page, but not if you close this tab."
      : "Restoring…"
    : result?.message;

  return (
    <Section title="Backup" dataTour="settings-backup">
      <div className="space-y-1">
        <p className="text-sm text-slate-700 dark:text-slate-300">
          {last ? `Last backup from this browser: ${new Date(last).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" })}` : "No backup from this browser yet"}
        </p>
        <p className="field-hint">
          An encrypted file of everything in your account — the same file the phone makes,
          so either can restore the other's.
        </p>
      </div>
      <div className="flex gap-2">
        <button type="button" className="btn btn-primary btn-sm" disabled={!!running}
          onClick={() => setAsking({ restoring: false })}>
          Back up now
        </button>
        <button type="button" className="btn btn-tonal btn-sm" disabled={!!running}
          onClick={() => picker.current?.click()}>
          Restore
        </button>
        <input ref={picker} type="file" className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = "";
            if (file) setAsking({ restoring: true, file });
          }} />
      </div>
      {status && (
        <p className={result && !result.ok && !running ? "alert-error" : "text-sm text-slate-600 dark:text-slate-400"}>
          {status}
        </p>
      )}

      {asking && (
        <PassphraseDialog restoring={asking.restoring} onCancel={() => setAsking(null)} onConfirm={confirmPassphrase} />
      )}
      {otherServer && (
        <ConfirmDialog
          title="Restore from another server?"
          message="This backup was made on a different Tracks server — usually one that has since been rebuilt. Its data will be merged into your account here."
          confirmLabel="Restore"
          onCancel={() => setOtherServer(null)}
          onConfirm={() => { runRestore(otherServer); setOtherServer(null); }}
        />
      )}
    </Section>
  );
}

function PassphraseDialog({ restoring, onCancel, onConfirm }) {
  const [pass, setPass] = useState("");
  const [again, setAgain] = useState("");
  const ok = restoring ? pass.length > 0 : pass.length >= MIN_PASSPHRASE && pass === again;
  return (
    <ConfirmDialog
      title={restoring ? "Backup passphrase" : "Choose a passphrase"}
      message={restoring ? null : `At least ${MIN_PASSPHRASE} characters. Without it the backup cannot be opened — not by you, and not by anyone else.`}
      confirmLabel={restoring ? "Restore" : "Back up"}
      confirmDisabled={!ok}
      onCancel={onCancel}
      onConfirm={() => onConfirm(pass)}
    >
      <label className="field-label">
        Passphrase
        <input className="field" type="password" autoFocus autoComplete={restoring ? "current-password" : "new-password"}
          value={pass} onChange={(e) => setPass(e.target.value)} />
      </label>
      {!restoring && (
        <label className="field-label">
          Again
          <input className="field" type="password" autoComplete="new-password"
            value={again} onChange={(e) => setAgain(e.target.value)} />
          {again && again !== pass && <span className="field-hint !text-red-600 dark:!text-red-400">The two do not match.</span>}
        </label>
      )}
    </ConfirmDialog>
  );
}
