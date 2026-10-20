// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Activity and health files: bulk FIT import and a plain export of every
// file (see lib/fitFiles/job.js). Import offers a folder first because
// selecting thousands of files in a file dialog does not work (sources.js
// says why); a drop or a zip works for any number too.
import { useRef, useState } from "react";
import { Section } from "./primitives";
import { todayIso } from "../../lib/today";
import {
  cancelFitFileJob, chooseExportTarget, fitJobLabel, runExport, runImport, useFitFileJob,
} from "../../lib/fitFiles/job";
import { filesFromDrop } from "../../lib/fitFiles/sources";

export default function FitFilesSection() {
  const { running, result } = useFitFileJob();
  const [over, setOver] = useState(false);
  const folderPicker = useRef(null);
  const filePicker = useRef(null);

  function picked(e) {
    const files = [...(e.target.files ?? [])];
    e.target.value = "";
    if (files.length) runImport(files);
  }

  async function dropped(e) {
    e.preventDefault();
    setOver(false);
    if (running) return;
    const files = await filesFromDrop(e.dataTransfer);
    if (files.length) runImport(files);
  }

  async function exportAll() {
    // Straight from the click: the save picker needs its user activation.
    const target = await chooseExportTarget(`tracks-fit-files-${todayIso()}.zip`);
    if (target) runExport(target);
  }

  const percent = running?.total ? Math.round((running.done / running.total) * 100) : null;

  return (
    <Section title="Activity & health files" dataTour="settings-fit-files"
      info="The original FIT files from your watches, as files any app can open. Import takes a folder (a whole Garmin Connect export works), .fit files, or .zip archives of them; files already in Tracks are skipped, so an import that was stopped can simply be run again.">
      <div
        onDragOver={(e) => { e.preventDefault(); if (!running) setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={dropped}
        className={`rounded-lg border-2 border-dashed px-4 py-5 text-center text-sm transition-colors ${
          over
            ? "border-accent-500 bg-accent-50 text-accent-700 dark:bg-accent-900/20 dark:text-accent-300"
            : "border-slate-300 text-slate-500 dark:border-slate-700 dark:text-slate-400"
        }`}
      >
        Drop .fit files, folders or .zip archives here
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-primary btn-sm" disabled={!!running}
          onClick={() => folderPicker.current?.click()}>
          Import a folder
        </button>
        <button type="button" className="btn btn-tonal btn-sm" disabled={!!running}
          onClick={() => filePicker.current?.click()}>
          Import files
        </button>
        <button type="button" className="btn btn-neutral btn-sm" disabled={!!running} onClick={exportAll}>
          Export all files
        </button>
        {/* webkitdirectory: one name in the dialog, however many files are
            under it. Every current browser supports it, despite the prefix. */}
        <input ref={folderPicker} type="file" className="hidden" webkitdirectory="" onChange={picked} />
        <input ref={filePicker} type="file" accept=".fit,.FIT,.zip" multiple className="hidden" onChange={picked} />
      </div>

      {running && (
        <div className="space-y-2">
          <div className="flex items-center justify-between gap-3 text-sm text-slate-600 dark:text-slate-400">
            <span>
              {fitJobLabel(running)}{percent != null && ` — ${percent}%`}
            </span>
            <button type="button" className="btn btn-neutral btn-sm" onClick={cancelFitFileJob}>Stop</button>
          </div>
          <div className="h-1.5 rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden">
            <div className="h-full bg-accent-500 transition-[width]" style={{ width: `${percent ?? 0}%` }} />
          </div>
          <p className="field-hint">It carries on if you open another page, but not if you close this tab.</p>
        </div>
      )}

      {!running && result && (
        <div className="space-y-1">
          <p className={result.ok ? "text-sm text-slate-600 dark:text-slate-400" : "alert-error"}>{result.message}</p>
          {result.failures.length > 0 && (
            <details className="text-xs text-slate-500 dark:text-slate-400">
              <summary className="cursor-pointer">{result.failures.length === 1 ? "1 file" : `${result.failures.length} files`} with a problem</summary>
              <ul className="mt-1 max-h-48 overflow-y-auto space-y-0.5">
                {result.failures.map((f, i) => (
                  <li key={i}><span className="font-mono">{f.name}</span>{f.error && ` — ${f.error}`}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </Section>
  );
}
