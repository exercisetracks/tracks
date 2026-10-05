// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step — Garmin watch sync. Two independent toggles: USB plug-in
// sync (garminSyncEnabled, on by default — the host's garmin-sync container
// only ever runs co-located with this server, so it's pre-authorized for
// this account automatically, no separate pairing step) and AGPS (pre-
// loading satellite data for faster GPS lock, off by default). Reads/writes
// `garminSyncEnabled`/`agpsEnabled` on the shared `agps` draft slice.
import { ToggleCard } from "./primitives";

export default function StepAGPS({ data, onChange, onNext, onBack }) {
  return (
    <div className="space-y-3">
      <ToggleCard
        checked={data.garminSyncEnabled}
        onChange={v => onChange("garminSyncEnabled", v)}
        title="USB sync"
        hint="Change any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 12h14M12 5l7 7-7 7" />
          </svg>
        }
        description="If this server has a Garmin watch plugged in by USB, it automatically pulls new activities and pushes planned workouts back — sealed for your account before they ever leave this machine."
      />

      <ToggleCard
        checked={data.agpsEnabled}
        onChange={v => onChange("agpsEnabled", v)}
        title="AGPS sync"
        hint="Change any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        }
        description="Pre-loads satellite position data onto your watch each time it syncs, cutting GPS lock time from 2–5 minutes down to a few seconds."
        points={[
          ["+", "GPS lock in seconds instead of minutes"],
          ["-", "Needs internet each sync (~100 KB) — contacts Garmin's servers"],
        ]}
      />

      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack}
          className="btn btn-neutral flex-1">
          Back
        </button>
        <button type="button" onClick={onNext}
          className="btn btn-primary flex-1">
          Next
        </button>
      </div>
    </div>
  );
}
