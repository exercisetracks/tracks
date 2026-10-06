// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Privacy & connectivity: the central place to see — and toggle — every opt-in
// feature that can talk to an external service. Each boolean (AGPS, map tiles,
// weather) has a live switch wired to updateSettings. AI coaching and AGPS
// carry their full configuration in an expandable panel under their row, so
// they don't need standalone sections; the rest expand to explain what they
// send.
import { useState } from "react";
import { api } from "../../api/client";
import { Section } from "./primitives";
import Switch from "../ui/Switch";
import AgpsConfigPanel from "./AgpsConfigPanel";
import AiConfigPanel from "./AiConfigPanel";

// Chevron that expands a row's configuration panel.
function ExpandButton({ open, onClick, label }) {
  return (
    <button
      type="button" onClick={onClick} aria-label={label} aria-expanded={open}
      className="shrink-0 p-1 rounded text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
    >
      <svg className={`w-4 h-4 transition-transform ${open ? "rotate-90" : ""}`}
           fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
      </svg>
    </button>
  );
}

export default function PrivacySummarySection({ settings, onSaved }) {
  const [busy, setBusy] = useState(null);       // which field is mid-save
  const [expanded, setExpanded] = useState(null); // which row's config panel is open

  const aiProvider = settings?.ai_provider;
  const aiIsLocal = aiProvider === "ollama";
  const aiIsRemote = aiProvider && !aiIsLocal;
  const agpsEnabled = settings?.agps_enabled ?? false;
  const weatherEnabled = settings?.weather_enabled ?? true;
  const mapEnabled = settings?.map_enabled ?? false;
  const wildfireEnabled = settings?.wildfire_enabled ?? false;

  async function toggle(field, next) {
    setBusy(field);
    try {
      await api.updateSettings({ [field]: next });
      await onSaved?.();
    } finally {
      setBusy(null);
    }
  }

  // Each toggle row: label, live data-usage description, and a switch.
  // Rows with a `panel` get a chevron that expands their configuration.
  const rows = [
    {
      key: "ai", name: "AI Coaching", on: !!aiProvider, control: "status",
      tone: aiIsLocal ? "local" : aiIsRemote ? "warn" : "off",
      desc: aiIsRemote
        ? `Sends training summaries to ${aiProvider === "anthropic" ? "Anthropic" : "OpenAI"}`
        : aiIsLocal ? "Runs locally via Ollama — data never leaves"
        : "Not configured — expand to choose a provider",
      panel: <AiConfigPanel settings={settings} onSaved={onSaved} />,
    },
    {
      key: "agps_enabled", name: "AGPS Sync", on: agpsEnabled, control: "toggle", tone: agpsEnabled ? "warn" : "off",
      desc: agpsEnabled
        ? "Downloads GPS predictions from Garmin and writes them to your watch on sync"
        : "No external requests",
      panel: agpsEnabled ? <AgpsConfigPanel settings={settings} onSaved={onSaved} /> : null,
    },
    {
      key: "map_enabled", name: "Map Tiles", on: mapEnabled, control: "toggle", tone: mapEnabled ? "ok" : "off",
      desc: mapEnabled
        ? "Basemap downloaded to your server once; tiles then served locally"
        : "No external requests (enabling downloads ~3.5 GB once)",
      panel: (
        <div className="px-2.5 py-2.5 bg-slate-50/70 dark:bg-slate-800/30">
          <div className="rounded-lg bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 p-2.5">
            <p className="text-xs font-medium text-emerald-700 dark:text-emerald-400">Tiles are served locally</p>
            <p className="text-xs text-emerald-600 dark:text-emerald-500 mt-0.5 leading-relaxed">
              Enabling downloads the worldwide basemap and elevation data from
              Protomaps and Mapterhorn to <span className="font-medium">your server</span>, once
              (~3.5 GB, internet required). After that every map tile is served
              from your own server — tile coordinates and your location never
              leave your network as you browse.
            </p>
          </div>
        </div>
      ),
    },
    {
      key: "weather_enabled", name: "Weather", on: weatherEnabled, control: "toggle", tone: weatherEnabled ? "info" : "off",
      desc: weatherEnabled ? "Sends race and map locations (lat/lon) to Open-Meteo for forecasts" : "Locations stay local",
      panel: (
        <div className="px-2.5 py-2.5 bg-slate-50/70 dark:bg-slate-800/30">
          <div className="rounded-lg bg-sky-50 dark:bg-sky-900/20 border border-sky-200 dark:border-sky-800 p-2.5">
            <p className="text-xs font-medium text-sky-700 dark:text-sky-400">One switch for every forecast</p>
            <p className="text-xs text-sky-600 dark:text-sky-500 mt-0.5 leading-relaxed">
              Your server asks Open-Meteo (free, no account) for weather at three
              kinds of place: a race plan's location, for weather-adjusted pacing;
              a point you tap on the map; and where your phone last was, for your
              watch's forecast. That last one is stored on your server, rounded
              to about a kilometre, and cleared when this is off. Each request sends <span className="font-medium">those coordinates</span>,
              nothing else. Turning this off stops all three — race plans pace
              without a weather adjustment and the map panel shows no forecast.
            </p>
          </div>
        </div>
      ),
    },
    {
      key: "wildfire_enabled", name: "Live Wildfire & Smoke", on: wildfireEnabled, control: "toggle", tone: wildfireEnabled ? "warn" : "off",
      desc: wildfireEnabled
        ? "Fetches US/Canada fire (NIFC, NRCan) + smoke (NOAA) feeds while the map overlay is on — no location sent"
        : "No external requests",
      panel: (
        <div className="px-2.5 py-2.5 bg-slate-50/70 dark:bg-slate-800/30">
          <div className="rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 p-2.5">
            <p className="text-xs font-medium text-amber-700 dark:text-amber-400">Live data — not fully offline</p>
            <p className="text-xs text-amber-600 dark:text-amber-500 mt-0.5 leading-relaxed">
              While the Wildfires &amp; Smoke layer is on, your server fetches the
              US fire feed from NIFC, the Canadian satellite fire feed from NRCan,
              and the smoke analysis from NOAA every few minutes. The feeds are
              country-wide, so <span className="font-medium">your location and map view are never sent</span> —
              only your server's requests reach those services. Turn the layer off
              (or this setting) to stop all requests.
            </p>
          </div>
        </div>
      ),
    },
  ];

  const toneCls = {
    local: "text-emerald-600 dark:text-emerald-400",
    ok:    "text-emerald-600 dark:text-emerald-400",
    info:  "text-sky-600 dark:text-sky-400",
    warn:  "text-amber-600 dark:text-amber-400",
    off:   "text-slate-400 dark:text-slate-500",
  };

  return (
    <Section title="Privacy &amp; Connectivity">
      <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
        Tracks is self-hosted and privacy-first. All data stays on your server unless you turn on
        one of the opt-in features below. Tracks itself never sends telemetry, analytics, or usage
        data anywhere — no cookies, no tracking.
      </p>

      <div className="mt-3 rounded-lg border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800">
        {rows.map((r) => (
          <div key={r.key}>
            <div className="flex items-center justify-between gap-4 px-2.5 py-2">
              <div className="min-w-0">
                <p className="text-xs font-medium text-slate-700 dark:text-slate-300">{r.name}</p>
                <p className={`text-[11px] mt-0.5 leading-snug ${toneCls[r.tone]}`}>{r.desc}</p>
              </div>
              <div className="flex items-center gap-1.5 shrink-0">
                {r.control === "toggle" ? (
                  <Switch checked={r.on} disabled={busy === r.key} label={`Toggle ${r.name}`}
                          onChange={v => toggle(r.key, v)} />
                ) : (
                  <span className={`text-[11px] font-medium ${toneCls[r.tone]}`}>
                    {r.on ? (aiIsLocal ? "Local" : "Connected") : "Off"}
                  </span>
                )}
                {/* A row without a panel (AGPS while off) keeps the chevron's
                    width, so every switch lines up in one column. */}
                {r.panel ? (
                  <ExpandButton open={expanded === r.key} label={`Configure ${r.name}`}
                                onClick={() => setExpanded(expanded === r.key ? null : r.key)} />
                ) : (
                  <span className="w-6 shrink-0" aria-hidden="true" />
                )}
              </div>
            </div>
            {r.panel && expanded === r.key && r.panel}
          </div>
        ))}
      </div>
    </Section>
  );
}
