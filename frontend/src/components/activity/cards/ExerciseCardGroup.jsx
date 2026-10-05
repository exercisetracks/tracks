// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { ExerciseCard } from "./ExerciseCard";

/**
 * ExerciseCardGroup - Groups and displays multiple exercise cards
 */
export function ExerciseCardGroup({ exercises, imperial = false }) {
  if (!exercises?.length) return null;

  return (
    <div className="space-y-3">
      {exercises.map((exercise, i) => (
        <ExerciseCard
          key={i}
          name={exercise.name}
          sets={exercise.sets}
          summary={exercise.summary}
          icon={exercise.icon}
          imperial={imperial}
        />
      ))}
    </div>
  );
}