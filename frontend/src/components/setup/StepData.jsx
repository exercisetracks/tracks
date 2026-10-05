// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step — Map & weather data. Four independent opt-in toggles: worldwide
// map tiles, race weather forecasts, live wildfire/smoke overlays, and USGS
// GNIS POI data. Reads/writes mapEnabled/weatherEnabled/wildfireEnabled/
// gnisEnabled on the shared `agps` draft slice.
import { ToggleCard } from "./primitives";

export default function StepData({ data, onChange, onNext, onBack }) {
  return (
    <div className="space-y-3">
      <ToggleCard
        checked={data.mapEnabled}
        onChange={v => onChange("mapEnabled", v)}
        title="Map tiles"
        hint="Change any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z" />
          </svg>
        }
        description="Download the worldwide basemap for the interactive map view."
        points={[
          ["-", "Large download (~3.5 GB) — needs a stable connection"],
          ["-", "Fetches tiles from Protomaps and Mapterhorn — not fully offline"],
        ]}
      />

      <ToggleCard
        checked={data.weatherEnabled}
        onChange={v => onChange("weatherEnabled", v)}
        title="Race weather data"
        hint="Change any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 15a4.5 4.5 0 0 0 4.5 4.5H18a3.75 3.75 0 0 0 1.332-7.257 3 3 0 0 0-3.758-3.848 5.25 5.25 0 0 0-10.233 2.33A4.502 4.502 0 0 0 2.25 15Z" />
          </svg>
        }
        description="Automatically fetch forecast and historical weather for race plans."
        points={[
          ["+", "Free, no API key needed (Open-Meteo)"],
          ["-", "Sends your race location (lat/lon) to Open-Meteo's servers"],
        ]}
      />

      <ToggleCard
        checked={data.wildfireEnabled}
        onChange={v => onChange("wildfireEnabled", v)}
        title="Live wildfire & smoke"
        hint="Change any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M15.362 5.214A8.252 8.252 0 0 1 12 21 8.25 8.25 0 0 1 6.038 7.047 8.287 8.287 0 0 0 9 9.601a8.983 8.983 0 0 1 3.361-6.867 8.21 8.21 0 0 0 3 2.48Z" />
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 18a3.75 3.75 0 0 0 .495-7.468 5.99 5.99 0 0 0-1.925 3.547 5.975 5.975 0 0 1-2.133-1.001A3.75 3.75 0 0 0 12 18Z" />
          </svg>
        }
        description="Active wildfires (size, containment, perimeter) and smoke plumes as a map layer — US and Canada."
        points={[
          ["+", "Your location and map view are never sent"],
          ["-", "Polls NIFC/NRCan/NOAA every few minutes while on"],
        ]}
      />

      <ToggleCard
        checked={data.gnisEnabled}
        onChange={v => onChange("gnisEnabled", v)}
        title="USGS POI data"
        hint="Downloads in the background after setup — trigger again any time in Settings."
        icon={
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
            <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1 1 15 0Z" />
          </svg>
        }
        description="~2 million US peaks, streams, lakes, and parks (USGS Geographic Names Information System)."
        points={[
          ["+", "Public domain — accurate, authoritative coordinates"],
          ["-", "US coverage only · ~300 MB · requires internet"],
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
