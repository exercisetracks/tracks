// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Strength Training page
 */
import { useState, useCallback } from "react";
import ExercisesTab          from "../components/ExercisesTab";
import WorkoutsTab          from "../components/WorkoutsTab";
import CustomExerciseModal   from "../components/CustomExerciseModal";
import Tabs from "../components/ui/Tabs";

const TABS = [
  { key: "Exercises", label: "Exercises" },
  { key: "Workouts",  label: "Workouts" },
];

export default function Training() {
  const [tab, setTab] = useState("Exercises");
  const [customModalExercise, setCustomModalExercise] = useState(undefined);
  const [customModalReload, setCustomModalReload]     = useState(null);

  const handleOpenCustomModal = useCallback((exercise, reloadFn) => {
    setCustomModalExercise(exercise ?? null);
    setCustomModalReload(() => reloadFn);
  }, []);

  const handleCloseModal = useCallback(() => {
    setCustomModalExercise(undefined);
  }, []);

  const handleSaved = useCallback(() => {
    if (customModalReload) customModalReload();
  }, [customModalReload]);

  return (
    <div className="max-w-7xl mx-auto px-5 pt-5 pb-1.5">
      <Tabs dataTour="strength-tabs" tabs={TABS} value={tab} onChange={setTab} stretch className="mb-6" />

      <div data-tour="strength-content">
        {tab === "Exercises" && <ExercisesTab onOpenCustomModal={handleOpenCustomModal} />}
        {tab === "Workouts"  && <WorkoutsTab />}
      </div>

      {customModalExercise !== undefined && (
        <CustomExerciseModal
          exercise={customModalExercise}
          onClose={handleCloseModal}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}
