// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Tutorial controls: master on/off for the guided in-app tips and a button to
// replay the whole tour. State lives in TourContext (synced to the account), so
// this section takes no props. Also the anchor for the Settings page's own tip.
import { useNavigate } from "react-router-dom";
import { Section } from "./primitives";
import { useTour } from "../tour/TourContext";

// Small accent-coloured on/off switch — matches PrivacySummarySection.
function Switch({ on, onClick, label }) {
  return (
    <button
      type="button" role="switch" aria-checked={on} aria-label={label}
      onClick={onClick}
      className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${
        on ? "bg-accent-500" : "bg-slate-300 dark:bg-slate-600"
      }`}
    >
      <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
        on ? "translate-x-4" : "translate-x-0.5"
      }`} />
    </button>
  );
}

export default function TutorialSection() {
  const navigate = useNavigate();
  const { enabled, setToursEnabled, restartTutorial } = useTour();

  function handleRestart() {
    restartTutorial();
    navigate("/"); // Dashboard, so the first tour fires immediately.
  }

  return (
    <Section title="Tutorial">
      <div data-tour="settings-tutorial" className="space-y-4">
        <div className="flex items-center justify-between gap-4">
          <div className="min-w-0">
            <p className="text-sm font-medium text-slate-700 dark:text-slate-300">Show tutorial tips</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              Guided tips appear the first time you open each section.
            </p>
          </div>
          <Switch on={enabled} onClick={() => setToursEnabled(!enabled)} label="Show tutorial tips" />
        </div>

        <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-100 dark:border-slate-800">
          <div className="min-w-0 pt-2.5">
            <p className="text-sm font-medium text-slate-700 dark:text-slate-300">Replay the tutorial</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              Start over from the Dashboard and see every section's tips again.
            </p>
          </div>
          <button
            type="button"
            onClick={handleRestart}
            className="btn btn-primary btn-sm shrink-0 mt-3"
          >
            Restart tutorial
          </button>
        </div>
      </div>
    </Section>
  );
}
