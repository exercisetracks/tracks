// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useState, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useActivityData } from "../../../hooks/useActivityData";
import { api } from "../../../api/client";
import {
  isBouldering,
  isClimbing,
  isStrength,
  isGolf,
  isRunning,
  isHiking,
  isCycling,
  isIndoorCycling,
  isSwimming,
  isRowing,
  isTriathlon,
  isSkiing,
  isNordicSkiing,
  isPaddling,
  isTeamSports,
  isFitnessEquipment,
  isMindBody,
  getSportType,
} from "../";
import RunningLayout from "./RunningLayout";
import HikingLayout from "./HikingLayout";
import CyclingLayout from "./CyclingLayout";
import IndoorCyclingLayout from "./IndoorCyclingLayout";
import StrengthLayout from "./StrengthLayout";
import ClimbingLayout from "./ClimbingLayout";
import BoulderingLayout from "./BoulderingLayout";
import GolfLayout from "./GolfLayout";
import SwimmingLayout from "./SwimmingLayout";
import RowingLayout from "./RowingLayout";
import TriathlonLayout from "./TriathlonLayout";
import SkiingLayout from "./SkiingLayout";
import NordicSkiingLayout from "./NordicSkiingLayout";
import PaddlingLayout from "./PaddlingLayout";
import TeamSportsLayout from "./TeamSportsLayout";
import FitnessEquipmentLayout from "./FitnessEquipmentLayout";
import MindBodyLayout from "./MindBodyLayout";
import FallbackLayout from "./FallbackLayout";
import MergedTripLayout from "./MergedTripLayout";
import { HowItFelt } from "../cards/HowItFelt";
import ConfirmDialog from "../../ConfirmDialog";
import Checkbox from "../../ui/Checkbox";

function DeleteDialog({ onConfirm, onCancel, deleting }) {
  const [allowReimport, setAllowReimport] = useState(false);

  return (
    <ConfirmDialog
      title="Delete activity?"
      message="This permanently removes the activity and all its data."
      confirmLabel={deleting ? "Deleting…" : "Delete"}
      danger
      busy={deleting}
      onConfirm={() => onConfirm(allowReimport)}
      onCancel={onCancel}
    >
      <Checkbox checked={allowReimport} onChange={setAllowReimport}>Allow re-import of this file</Checkbox>
    </ConfirmDialog>
  );
}

export function ActivityView() {
  const { id } = useParams();
  const navigate = useNavigate();

  const { activity, track, laps, sets, climbs, golfHoles, settings, loading } = useActivityData(id);

  const [showDelete, setShowDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = useCallback(async (allowReimport) => {
    setDeleting(true);
    try {
      await api.deleteActivity(id, allowReimport);
      navigate("/activities");
    } catch {
      setDeleting(false);
    }
  }, [id, navigate]);

  const imperial = settings?.units === "imperial";
  const sportType = getSportType(activity);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-sm text-slate-400">
        <div className="flex items-center gap-2">
          <div className="spinner" />
          Loading activity...
        </div>
      </div>
    );
  }

  if (!activity) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-3">
        <p className="text-sm text-slate-400">Activity not found</p>
        <button
          onClick={() => navigate("/activities")}
          className="btn btn-neutral"
        >
          ← Back to activities
        </button>
      </div>
    );
  }

  const sharedProps = { activity, track, laps, sets, climbs, golfHoles, imperial, sportType };

  const deleteButton = (
    <button
      onClick={() => setShowDelete(true)}
      className="btn btn-danger btn-sm absolute top-4 right-4 z-10"
    >
      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
      </svg>
      Delete
    </button>
  );

  let layout;
  if (activity.is_merged)                           layout = <MergedTripLayout activity={activity} imperial={imperial} />;
  else if (isGolf(activity))                        layout = <GolfLayout {...sharedProps} />;
  else if (isBouldering(activity) && climbs?.length > 0) layout = <BoulderingLayout {...sharedProps} />;
  else if (isClimbing(activity) && climbs?.length > 0)   layout = <ClimbingLayout {...sharedProps} />;
  else if (isStrength(activity) && sets?.length > 0)     layout = <StrengthLayout {...sharedProps} />;
  else if (isMindBody(activity))                         layout = <MindBodyLayout {...sharedProps} />;
  else if (isHiking(activity))                           layout = <HikingLayout {...sharedProps} />;
  else if (isRunning(activity))                          layout = <RunningLayout {...sharedProps} />;
  else if (isIndoorCycling(activity))                    layout = <IndoorCyclingLayout {...sharedProps} />;
  else if (isCycling(activity))                          layout = <CyclingLayout {...sharedProps} />;
  else if (isSwimming(activity))                         layout = <SwimmingLayout {...sharedProps} />;
  else if (isRowing(activity))                           layout = <RowingLayout {...sharedProps} />;
  else if (isTriathlon(activity))                        layout = <TriathlonLayout {...sharedProps} />;
  else if (isSkiing(activity))                           layout = <SkiingLayout {...sharedProps} />;
  else if (isNordicSkiing(activity))                     layout = <NordicSkiingLayout {...sharedProps} />;
  else if (isPaddling(activity))                         layout = <PaddlingLayout {...sharedProps} />;
  else if (isTeamSports(activity))                       layout = <TeamSportsLayout {...sharedProps} />;
  else if (isFitnessEquipment(activity))                 layout = <FitnessEquipmentLayout {...sharedProps} />;
  else                                                   layout = <FallbackLayout {...sharedProps} />;

  return (
    <>
      {showDelete && (
        <DeleteDialog
          onConfirm={handleDelete}
          onCancel={() => setShowDelete(false)}
          deleting={deleting}
        />
      )}
      <div className="relative pr-3.5">
        {deleteButton}
        {layout}
        {/* Here rather than per layout: the watch asks after any sport. */}
        {!activity.is_merged && <HowItFelt activity={activity} />}
      </div>
    </>
  );
}

export default ActivityView;
