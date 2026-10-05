// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Body stats section: weight, height (shown in metric or imperial per the user's
// unit setting), birth year and biological sex. Weight/height/birth year save on
// blur; sex on change. Until there are runs to measure, these set the estimate
// running paces start from (backend calculators/plan/running_fitness.py).
import { useCallback, useEffect, useState } from "react";
import { api } from "../../api/client";
import { INPUT, SELECT, Section, FieldRow, useSaveStatus } from "./primitives";

export default function BodySection({ settings, onSaved }) {
  const imperial = settings?.units === "imperial";

  const toDisplay = useCallback((kg) => {
    if (kg == null) return "";
    return imperial ? (kg * 2.20462).toFixed(1) : kg.toFixed(1);
  }, [imperial]);

  const toDisplayHeight = useCallback((cm) => {
    if (cm == null) return "";
    return imperial ? (cm / 2.54).toFixed(1) : cm.toFixed(1);
  }, [imperial]);

  const [weight, setWeight] = useState("");
  const [height, setHeight] = useState("");
  const [sex, setSex] = useState("");
  const [birthYear, setBirthYear] = useState("");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setWeight(toDisplay(settings?.weight_kg));
    setHeight(toDisplayHeight(settings?.height_cm));
    setSex(settings?.sex || "");
    setBirthYear(settings?.birth_year != null ? String(settings.birth_year) : "");
  }, [settings, toDisplay, toDisplayHeight]);

  async function saveWeight() {
    if (weight === "") return;
    const val = parseFloat(weight);
    if (isNaN(val)) return;
    startSave();
    try {
      await api.updateSettings({ weight_kg: imperial ? val / 2.20462 : val });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  async function saveHeight() {
    if (height === "") return;
    const val = parseFloat(height);
    if (isNaN(val)) return;
    startSave();
    try {
      await api.updateSettings({ height_cm: imperial ? val * 2.54 : val });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  async function saveBirthYear() {
    if (birthYear === "") return;
    const val = parseInt(birthYear, 10);
    if (isNaN(val) || val < 1900 || val > new Date().getFullYear()) return;
    startSave();
    try {
      await api.updateSettings({ birth_year: val });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  return (
    <Section title="Body Stats" status={status}>
      <div className="grid grid-cols-2 gap-4">
        <FieldRow label="Weight" hint={imperial ? "(lbs)" : "(kg)"}>
          <input className={INPUT} type="number" step="0.1" min={0}
            placeholder={imperial ? "165" : "75"}
            value={weight}
            onChange={e => setWeight(e.target.value)}
            onBlur={saveWeight} />
        </FieldRow>
        <FieldRow label="Height" hint={imperial ? "(in)" : "(cm)"}>
          <input className={INPUT} type="number" step="0.5" min={0}
            placeholder={imperial ? "70" : "175"}
            value={height}
            onChange={e => setHeight(e.target.value)}
            onBlur={saveHeight} />
        </FieldRow>
        <FieldRow label="Birth year" hint="(optional)">
          <input className={INPUT} type="number" step="1" min={1900} max={new Date().getFullYear()}
            placeholder="1990"
            value={birthYear}
            onChange={e => setBirthYear(e.target.value)}
            onBlur={saveBirthYear} />
        </FieldRow>
      </div>
      <p className="text-xs text-slate-400 mt-2">
        Until you have runs recorded, these set the paces a running plan starts from. After that, your runs do.
      </p>
      <div className="mt-4">
        <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">Biological sex</p>
        <p className="text-xs text-slate-400 mb-2">Used for the muscle anatomy model, and for starting paces before you have runs recorded.</p>
        <select
          value={sex}
          onChange={async (e) => {
            const v = e.target.value;
            setSex(v);
            if (!v) return;
            startSave();
            try { await api.updateSettings({ sex: v }); onSaved(); markSaved(); } catch { markError(); }
          }}
          className={SELECT}
        >
          <option value="">Select...</option>
          <option value="male">Male</option>
          <option value="female">Female</option>
        </select>
      </div>
    </Section>
  );
}
