// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step 3 — Training zones. Max HR, threshold HR (LTHR), and FTP, each with
// an Auto-detect/Manual toggle. In auto mode Tracks derives the value from
// activity history; manual mode reveals a number input. Reads/writes only the
// `zones` draft slice via onChange.
import { INPUT, ModeToggle } from "./primitives";

export default function StepZones({ data, onChange, onNext, onBack }) {
  function handleNext(e) {
    e.preventDefault();
    onNext();
  }

  return (
    <form onSubmit={handleNext} className="space-y-4">
      <p className="text-xs text-slate-500 dark:text-slate-400 leading-snug">
        These values are used to calculate your training zones. Choose <strong>Auto-detect</strong> to let Tracks derive them from your activity history, or enter values manually.
      </p>

      {/* Max HR */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Max Heart Rate</span>
          <ModeToggle value={data.maxHrMode} onChange={v => onChange("maxHrMode", v)} />
        </div>
        {data.maxHrMode === "manual" && (
          <input
            className={INPUT}
            type="number" min={100} max={250}
            placeholder="e.g. 185"
            value={data.maxHrManual}
            onChange={e => onChange("maxHrManual", e.target.value)}
          />
        )}
        {data.maxHrMode === "auto" && (
          <p className="text-xs text-slate-400 dark:text-slate-500">Will be derived from your highest recorded heart rate.</p>
        )}
      </div>

      {/* Threshold HR */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Threshold HR (LTHR)</span>
          <ModeToggle value={data.thresholdHrMode} onChange={v => onChange("thresholdHrMode", v)} />
        </div>
        {data.thresholdHrMode === "manual" && (
          <input
            className={INPUT}
            type="number" min={80} max={250}
            placeholder="e.g. 162"
            value={data.thresholdHrManual}
            onChange={e => onChange("thresholdHrManual", e.target.value)}
          />
        )}
        {data.thresholdHrMode === "auto" && (
          <p className="text-xs text-slate-400 dark:text-slate-500">Will be calculated as the best 20-minute average heart rate from your activities.</p>
        )}
      </div>

      {/* FTP */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">FTP (Functional Threshold Power)</span>
          <ModeToggle value={data.ftpMode} onChange={v => onChange("ftpMode", v)} />
        </div>
        {data.ftpMode === "manual" && (
          <input
            className={INPUT}
            type="number" min={1} max={2000}
            placeholder="e.g. 250 watts"
            value={data.ftpManual}
            onChange={e => onChange("ftpManual", e.target.value)}
          />
        )}
        {data.ftpMode === "auto" && (
          <p className="text-xs text-slate-400 dark:text-slate-500">Will be estimated as 95% of your best 20-minute power effort from cycling.</p>
        )}
      </div>

      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack}
          className="btn btn-neutral flex-1">
          Back
        </button>
        <button type="submit"
          className="btn btn-primary flex-1">
          Continue
        </button>
      </div>
    </form>
  );
}
