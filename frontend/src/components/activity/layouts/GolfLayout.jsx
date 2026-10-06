// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useRef } from "react";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { ActivityTable } from "../tables/ActivityTable";
import { GolfScorecard } from "../charts/GolfScorecard";
import ActivityRoute from "../../ActivityRoute";

export function GolfLayout({
  activity,
  golfHoles,
  track,
  imperial = false,
  sportType = "golf",
}) {
  const routeRef = useRef(null);
  if (!activity) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  const holes = golfHoles || [];

  const totalStrokes = holes.reduce((s, h) => s + (h.total_strokes ?? 0), 0);
  const totalPutts = holes.reduce((s, h) => s + (h.total_putts ?? 0), 0);
  const holesPlayed = holes.filter((h) => h.total_strokes > 0).length;

  const columns = [
    { key: "hole_number", label: "Hole", align: "left" },
    {
      key: "total_strokes",
      label: "Strokes",
      align: "right",
      formatter: (v) => v ?? "—",
    },
    {
      key: "total_putts",
      label: "Putts",
      align: "right",
      formatter: (v) => v ?? "—",
    },
    {
      key: "distance_meters",
      label: imperial ? "Yardage" : "Distance (m)",
      align: "right",
      formatter: (v) => {
        if (v == null) return "—";
        return imperial ? `${Math.round(v * 1.09361)} yd` : `${Math.round(v)} m`;
      },
    },
    {
      key: "duration_seconds",
      label: "Time",
      align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="mmss" />,
    },
  ];

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        {holesPlayed > 0 && <StatPill value={holesPlayed} label="Holes" />}
        {totalStrokes > 0 && <StatPill value={totalStrokes} label="Total Strokes" />}
        {totalPutts > 0 && <StatPill value={totalPutts} label="Total Putts" />}
        <StatPill
          value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />}
          label="Duration"
        />
        {activity.total_calories > 0 && (
          <StatPill value={`${activity.total_calories} kcal`} label="Calories" />
        )}
      </StatPillsStrip>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
        <div className="space-y-4">
          {holes.length > 0 && <GolfScorecard holes={holes} imperial={imperial} />}

          {holes.length > 0 && (
            <div className="card">
              <h3 className="section-title mb-3">
                Hole-by-Hole
              </h3>
              <ActivityTable
                columns={columns}
                data={holes}
                emptyMessage="No hole data available"
              />
            </div>
          )}
        </div>

        <div className="space-y-4">
          {gpsTrack.length > 0 && (
            <div className="rounded-xl overflow-hidden bg-slate-900" style={{ height: 450 }}>
              <ActivityRoute ref={routeRef} track={gpsTrack} height={450} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default GolfLayout;
