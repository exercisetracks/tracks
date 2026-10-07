// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Devices section: list of known devices with claim/unclaim and a "primary"
// toggle. Only claimed devices feed metrics/charts/coaching; the primary watch
// drives which workout animations the planner expects.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { Section, InlineError } from "./primitives";

export default function DevicesSection() {
  const [devices,  setDevices]  = useState(null);
  const [toggling, setToggling] = useState(null);
  const [primarySaving, setPrimarySaving] = useState(null);
  const [error,    setError]    = useState("");
  // Garmin devices list (`/garmin/devices`) — same set as /devices but with
  // an is_primary flag indicating the user's main watch. We load both
  // endpoints and merge on serial_number; allows the existing claim flow
  // to stay unchanged while adding the primary toggle.
  const [garminDevices, setGarminDevices] = useState([]);

  useEffect(() => {
    Promise.all([
      api.getDevices().catch(() => []),
      api.getGarminDevices().catch(() => []),
    ]).then(([d, g]) => {
      setDevices(d || []);
      setGarminDevices(g || []);
    });
  }, []);

  async function toggle(device) {
    setToggling(device.id);
    setError("");
    try {
      const updated = device.claimed
        ? await api.unclaimDevice(device.id)
        : await api.claimDevice(device.id);
      setDevices(prev => prev.map(d => d.id === updated.id ? updated : d));
    } catch (e) {
      setError(e.message ?? "Failed to update device.");
    } finally {
      setToggling(null);
    }
  }

  async function makePrimary(device) {
    setPrimarySaving(device.id);
    setError("");
    try {
      await api.setPrimaryDevice(device.id);
      // Re-fetch garmin devices to reflect the new is_primary flag.
      const g = await api.getGarminDevices().catch(() => []);
      setGarminDevices(g || []);
    } catch (e) {
      setError(e.message ?? "Failed to set primary device.");
    } finally {
      setPrimarySaving(null);
    }
  }

  // Match a /devices row to its /garmin/devices counterpart via serial.
  function isPrimary(device) {
    return !!garminDevices.find(g =>
      g.serial_number === device.serial_number && g.is_primary
    );
  }

  function label(d) {
    const parts = [d.manufacturer, d.product_name].filter(Boolean);
    return parts.length ? parts.map(p => p.replace(/_/g, " ")).join(" · ") : `Device #${d.id}`;
  }

  return (
    <Section title="Devices" dataTour="settings-devices">
      <p className="text-xs text-slate-400 dark:text-slate-500">
        Only activities from claimed devices are included in metrics, charts, and coaching.
        Mark one as <span className="font-semibold">Primary</span> to record which animations
        play on it — the planner will skip movements you've marked as not animating on your
        primary watch.
      </p>

      {devices === null ? (
        <div className="flex items-center gap-2 text-xs text-slate-400">
          <div className="w-3.5 h-3.5 border-2 border-slate-300 border-t-transparent rounded-full animate-spin" />
          Loading…
        </div>
      ) : devices.length === 0 ? (
        <p className="text-sm text-slate-400 dark:text-slate-500 italic">No devices found. Import a FIT file to register a device.</p>
      ) : (
        <div className="divide-y divide-slate-100 dark:divide-slate-800">
          {devices.map(d => (
            <div key={d.id} className="flex items-center justify-between py-2.5 gap-4">
              <div className="min-w-0">
                <p className="text-sm font-medium text-slate-800 dark:text-slate-200 capitalize truncate">{label(d)}</p>
                <p className="text-xs text-slate-400 dark:text-slate-500 font-mono">{d.serial_number}</p>
                {d.software_version && (
                  <p className="text-xs text-slate-400 dark:text-slate-500">fw {d.software_version}</p>
                )}
              </div>
              <div className="flex items-center gap-2 shrink-0">
                {d.claimed && (
                  <button
                    type="button"
                    onClick={() => makePrimary(d)}
                    disabled={primarySaving === d.id || isPrimary(d)}
                    title={isPrimary(d) ? "Your primary device" : "Set as primary device"}
                    className={`btn btn-sm ${isPrimary(d) ? "btn-tonal" : "btn-neutral"}`}
                  >
                    {primarySaving === d.id ? "…" : (isPrimary(d) ? "★ Primary" : "☆ Set primary")}
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => toggle(d)}
                  disabled={toggling === d.id}
                  className={`btn btn-sm ${d.claimed ? "btn-tonal" : "btn-neutral"}`}
                >
                  {toggling === d.id ? "…" : d.claimed ? "Claimed" : "Claim"}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      <InlineError msg={error} />
    </Section>
  );
}
