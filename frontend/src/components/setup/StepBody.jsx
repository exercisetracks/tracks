// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step 2 — Body stats. Units (metric/imperial, with live weight/height
// conversion on toggle), weight, height, birth year, biological sex, and
// timezone. Reads and writes only the `body` draft slice via onChange.
import { INPUT, SELECT, FieldRow } from "./primitives";

const TIMEZONES_COMMON = [
  "UTC",
  "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
  "America/Anchorage", "America/Honolulu",
  "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Stockholm",
  "Europe/Helsinki", "Europe/Moscow",
  "Asia/Dubai", "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai",
  "Asia/Tokyo", "Asia/Seoul",
  "Australia/Sydney", "Australia/Melbourne", "Pacific/Auckland",
];

export default function StepBody({ data, onChange, onNext, onBack }) {
  const imperial = data.units === "imperial";

  function handleUnitsChange(newUnits) {
    const toImperial = newUnits === "imperial";
    const w = parseFloat(data.weight);
    const h = parseFloat(data.height);
    onChange("units", newUnits);
    if (data.weight !== "" && !isNaN(w))
      onChange("weight", toImperial ? (w * 2.20462).toFixed(1) : (w / 2.20462).toFixed(1));
    if (data.height !== "" && !isNaN(h))
      onChange("height", toImperial ? (h / 2.54).toFixed(1) : (h * 2.54).toFixed(1));
  }

  function handleNext(e) {
    e.preventDefault();
    onNext();
  }

  return (
    <form onSubmit={handleNext} className="space-y-4">
      <FieldRow label="Units">
        <div className="flex rounded-lg overflow-hidden border border-slate-300 dark:border-slate-700 text-sm">
          {[["metric", "Metric (kg, cm)"], ["imperial", "Imperial (lbs, ft)"]].map(([v, l]) => (
            <button key={v} type="button"
              onClick={() => handleUnitsChange(v)}
              className={`flex-1 py-1.5 font-medium transition-colors ${
                data.units === v
                  ? "bg-accent-600 text-white"
                  : "bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
              }`}>
              {l}
            </button>
          ))}
        </div>
      </FieldRow>

      <div className="grid grid-cols-2 gap-4">
        <FieldRow label="Weight" hint={imperial ? "(lbs)" : "(kg)"}>
          <input
            className={INPUT}
            type="number"
            min={0}
            max={imperial ? 1100 : 500}
            step="0.1"
            placeholder={imperial ? "e.g. 165" : "e.g. 75"}
            value={data.weight}
            onChange={e => onChange("weight", e.target.value)}
          />
        </FieldRow>
        <FieldRow label="Height" hint={imperial ? "(in)" : "(cm)"}>
          <input
            className={INPUT}
            type="number"
            min={0}
            max={imperial ? 120 : 300}
            step="any"
            placeholder={imperial ? "e.g. 70" : "e.g. 175"}
            value={data.height}
            onChange={e => onChange("height", e.target.value)}
          />
        </FieldRow>
      </div>

      <FieldRow label="Birth year" hint="(optional)">
        <p className="text-[11px] text-slate-400 dark:text-slate-500 mb-2">
          With your height, weight and sex, sets starting run paces until you have runs recorded.
        </p>
        <input
          className={INPUT}
          type="number"
          min={1900}
          max={new Date().getFullYear()}
          step="1"
          placeholder="e.g. 1990"
          value={data.birthYear}
          onChange={e => onChange("birthYear", e.target.value)}
        />
      </FieldRow>

      <FieldRow label="Biological sex">
        <p className="text-[11px] text-slate-400 dark:text-slate-500 mb-2">
          Used for the muscle anatomy model and starting run paces.
        </p>
        <div className="flex rounded-lg overflow-hidden border border-slate-300 dark:border-slate-700 text-sm">
          {[["male", "Male"], ["female", "Female"]].map(([v, l]) => (
            <button key={v} type="button"
              onClick={() => onChange("sex", v)}
              className={`flex-1 py-1.5 font-medium transition-colors ${
                data.sex === v
                  ? "bg-accent-600 text-white"
                  : "bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
              }`}>
              {l}
            </button>
          ))}
        </div>
      </FieldRow>

      <FieldRow label="Timezone">
        <select className={SELECT} value={data.timezone} onChange={e => onChange("timezone", e.target.value)}>
          {TIMEZONES_COMMON.map(tz => (
            <option key={tz} value={tz}>{tz.replace(/_/g, " ")}</option>
          ))}
        </select>
      </FieldRow>

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
