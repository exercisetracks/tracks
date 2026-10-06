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
import PageHeader from "../components/ui/PageHeader";

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
    <div className="p-5 max-w-7xl mx-auto space-y-6">
      <PageHeader title="Strength" />
      <Tabs dataTour="strength-tabs" tabs={TABS} value={tab} onChange={setTab} stretch />

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
