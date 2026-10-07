// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Profile section: display name, unit system, and timezone.
// Name saves on blur; units/timezone save immediately on change.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { INPUT, SELECT, Section, FieldRow, useSaveStatus } from "./primitives";
import Tabs from "../ui/Tabs";
import { setAccountZone } from "../../lib/today";

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

export default function ProfileSection({ user, settings, onSaved }) {
  const { refreshUser } = useAuth();
  const [name, setName] = useState(user?.name ?? "");
  const [units, setUnits] = useState(settings?.units ?? "metric");
  const [tz, setTz] = useState(settings?.timezone ?? "UTC");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setName(user?.name ?? "");
    setUnits(settings?.units ?? "metric");
    setTz(settings?.timezone ?? "UTC");
  }, [user, settings]);

  async function saveName() {
    if (!name.trim()) return;
    startSave();
    try {
      await api.updateMe({ name: name.trim() });
      await refreshUser();
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  async function saveUnits(v) {
    setUnits(v);
    startSave();
    try {
      await api.updateSettings({ units: v });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  async function saveTz(v) {
    setTz(v);
    setAccountZone(v);
    startSave();
    try {
      await api.updateSettings({ timezone: v });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  return (
    <Section title="Profile" status={status}>
      <FieldRow label="Display name">
        <input className={INPUT} type="text" value={name}
          onChange={e => setName(e.target.value)}
          onBlur={saveName}
          placeholder="Your name" />
      </FieldRow>
      <FieldRow label="Units">
        <Tabs stretch tabs={[{ key: "metric", label: "Metric (kg, km)" }, { key: "imperial", label: "Imperial (lbs, mi)" }]}
          value={units} onChange={saveUnits} />
      </FieldRow>
      <FieldRow label="Timezone">
        <select className={SELECT} value={tz} onChange={e => saveTz(e.target.value)}>
          {TIMEZONES_COMMON.map(t => <option key={t} value={t}>{t.replace(/_/g, " ")}</option>)}
        </select>
      </FieldRow>
    </Section>
  );
}
