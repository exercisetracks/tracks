// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Danger zone section: clear all user data (activities, health, goals, metrics)
// behind a type-DELETE confirmation modal. Account/settings/devices are kept.
// Uses its own red-bordered card + modal rather than the shared Section wrapper.
import { useState } from "react";
import { api } from "../../api/client";
import { INPUT } from "./primitives";

export default function DangerZoneSection() {
  const [open,     setOpen]     = useState(false);
  const [input,    setInput]    = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error,    setError]    = useState("");

  function openModal() { setOpen(true); setInput(""); setError(""); }
  function closeModal() { if (!deleting) { setOpen(false); setInput(""); setError(""); } }

  async function confirm() {
    if (input !== "DELETE") { setError('Type DELETE (all caps) to confirm.'); return; }
    setDeleting(true);
    setError("");
    try {
      await api.clearAllData();
      // Redirect to home — all data is gone
      window.location.href = "/";
    } catch (e) {
      setError(e.message ?? "Failed to clear data.");
      setDeleting(false);
    }
  }

  return (
    <>
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-red-200 dark:border-red-900 overflow-hidden">
        <div className="px-4 py-2.5 border-b border-red-100 dark:border-red-900">
          <h2 className="text-sm font-semibold text-red-700 dark:text-red-400 uppercase tracking-wide">Danger Zone</h2>
        </div>
        <div className="p-4">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="text-sm font-medium text-slate-800 dark:text-slate-200">Clear all user data</p>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                Permanently deletes all activities, health records, training goals, and metrics.
                Your account and settings are preserved. This cannot be undone.
              </p>
            </div>
            <button
              type="button"
              onClick={openModal}
              className="btn btn-danger btn-sm shrink-0"
            >
              Clear data
            </button>
          </div>
        </div>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-3.5 bg-black/50 backdrop-blur-sm">
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-red-200 dark:border-red-800 shadow-xl w-full max-w-md">
            <div className="px-4 py-3.5 border-b border-red-100 dark:border-red-900">
              <h3 className="text-base font-semibold text-red-700 dark:text-red-400">Clear all user data</h3>
            </div>
            <div className="p-4 space-y-4">
              <p className="text-sm text-slate-700 dark:text-slate-300">
                This will permanently delete:
              </p>
              <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-1 list-disc list-inside">
                <li>All activities and their GPS/sensor data</li>
                <li>Daily health metrics</li>
                <li>Injuries log</li>
                <li>Training goals and race plans</li>
                <li>Coaching history and fitness fingerprints</li>
              </ul>
              <p className="text-sm text-slate-700 dark:text-slate-300">
                Your account, settings, and devices are <strong>not</strong> affected.
              </p>
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  Type <span className="font-mono font-bold text-red-600 dark:text-red-400">DELETE</span> to confirm
                </label>
                <input
                  className={INPUT}
                  type="text"
                  placeholder="DELETE"
                  value={input}
                  onChange={e => setInput(e.target.value)}
                  onKeyDown={e => e.key === "Enter" && confirm()}
                  autoFocus
                  disabled={deleting}
                />
                {error && <p className="text-xs text-red-600 dark:text-red-400 mt-1">{error}</p>}
              </div>
            </div>
            <div className="px-4 py-3.5 border-t border-slate-100 dark:border-slate-800 flex justify-end gap-3">
              <button
                type="button"
                onClick={closeModal}
                disabled={deleting}
                className="btn btn-neutral btn-sm"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={confirm}
                disabled={deleting || input !== "DELETE"}
                className="btn btn-danger btn-sm"
              >
                {deleting ? "Deleting…" : "Delete all data"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
