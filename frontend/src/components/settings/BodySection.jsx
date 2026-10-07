// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Body stats section: weight, height (shown in metric or imperial per the user's
// unit setting), age and biological sex. Weight/height/age save on blur; sex on
// click. Until there are runs to measure, these set the estimate running paces
// start from (backend calculators/plan/running_fitness.py).
//
// Age is required: it is asked at setup, and a blank here is not saved — the
// stored value stays — so it cannot be cleared from Settings either.
import { useCallback, useEffect, useState } from "react";
import { api } from "../../api/client";
import { ageFromBirthYear, birthYearFromAge, MAX_AGE, MIN_AGE } from "../../lib/age";
import { INPUT, Section, FieldRow, InlineError, useSaveStatus } from "./primitives";
import Tabs from "../ui/Tabs";
import InfoTooltip from "../ui/InfoTooltip";

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
  const [age, setAge] = useState("");
  const [ageError, setAgeError] = useState("");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setWeight(toDisplay(settings?.weight_kg));
    setHeight(toDisplayHeight(settings?.height_cm));
    setSex(settings?.sex || "");
    setAge(settings?.birth_year != null ? String(ageFromBirthYear(settings.birth_year)) : "");
    setAgeError("");
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

  async function saveAge() {
    const stored = settings?.birth_year;
    const year = birthYearFromAge(age);
    if (year == null) {
      setAgeError(`Enter an age from ${MIN_AGE} to ${MAX_AGE}.`);
      return;
    }
    setAgeError("");
    if (year === stored) return;
    startSave();
    try {
      await api.updateSettings({ birth_year: year });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  async function saveSex(v) {
    setSex(v);
    startSave();
    try { await api.updateSettings({ sex: v }); onSaved(); markSaved(); } catch { markError(); }
  }

  return (
    <Section title="Body Stats" status={status}>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
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
        {/* Accounts made before age was required may have none yet; say so
            here, since nothing else will ask again. */}
        <FieldRow label="Age" hint={settings?.birth_year == null ? "(required)" : null}>
          <input className={INPUT} type="number" step="1" min={MIN_AGE} max={MAX_AGE} required
            placeholder="35"
            value={age}
            aria-invalid={!!ageError}
            onChange={e => setAge(e.target.value)}
            onBlur={saveAge} />
        </FieldRow>
      </div>
      <InlineError msg={ageError} />
      <p className="text-xs text-slate-400 mt-2">
        Until you have runs recorded, these set the paces a running plan starts from. After that, your runs do.
      </p>
      {/* Two buttons rather than a dropdown, as in setup and on the phone:
          both choices are visible without opening anything. */}
      <FieldRow label={<span className="inline-flex items-center gap-1.5">Biological sex
        <InfoTooltip label="What biological sex is used for">Chooses which anatomy model the muscle diagrams draw, and sets starting run paces. Sorry — there are only male and female models for now; a more androgynous one isn't available yet.</InfoTooltip></span>}>
        <Tabs stretch tabs={[{ key: "male", label: "Male" }, { key: "female", label: "Female" }]} value={sex} onChange={saveSex} />
      </FieldRow>
    </Section>
  );
}
