// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Danger zone section: clear all user data (activities, health, goals, metrics)
// behind a type-DELETE confirmation modal. Account/settings/devices are kept.
// Uses its own red-bordered card + modal rather than the shared Section wrapper.
import { useState } from "react";
import { api } from "../../api/client";
import { INPUT } from "./primitives";
import { Section as SharedSection } from "../ui/Section";
import ConfirmDialog from "../ConfirmDialog";

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
      {/* The shared section, its title in red and the card edged in red —
          the one place in Settings where the colour is the message. */}
      <SharedSection title={<span className="text-red-600 dark:text-red-400">Danger zone</span>}>
        <div className="card border-red-200 dark:border-red-900">
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
      </SharedSection>

      {open && (
        <ConfirmDialog
          title="Clear all user data"
          confirmLabel={deleting ? "Deleting…" : "Delete all data"}
          danger
          busy={deleting}
          confirmDisabled={input !== "DELETE"}
          onConfirm={confirm}
          onCancel={closeModal}
        >
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
            <label className="field-label">
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
            {error && <p className="field-hint !text-red-600 dark:!text-red-400">{error}</p>}
          </div>
        </ConfirmDialog>
      )}
    </>
  );
}
