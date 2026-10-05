// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useState } from "react";
import { api } from "../../../api/client";
import { fmtDate } from "../../../utils/formatUtils";

const SPORT_CONFIG = {
  running:           { label: "Running",          color: "bg-blue-500/20 text-blue-300 border-blue-500/30" },
  hiking:            { label: "Hiking",            color: "bg-green-500/20 text-green-300 border-green-500/30" },
  cycling:           { label: "Cycling",           color: "bg-amber-500/20 text-amber-300 border-amber-500/30" },
  mtb:               { label: "Mountain Biking",   color: "bg-amber-500/20 text-amber-300 border-amber-500/30" },
  indoor_cycling:    { label: "Indoor Cycling",    color: "bg-orange-500/20 text-orange-300 border-orange-500/30" },
  strength:          { label: "Strength Training", color: "bg-purple-500/20 text-purple-300 border-purple-500/30" },
  bouldering:        { label: "Bouldering",         color: "bg-accent-500/20 text-accent-300 border-accent-500/30" },
  climbing:          { label: "Climbing",          color: "bg-accent-500/20 text-accent-300 border-accent-500/30" },
  swimming:          { label: "Swimming",          color: "bg-cyan-500/20 text-cyan-300 border-cyan-500/30" },
  rowing:            { label: "Rowing",            color: "bg-sky-500/20 text-sky-300 border-sky-500/30" },
  triathlon:         { label: "Triathlon",         color: "bg-violet-500/20 text-violet-300 border-violet-500/30" },
  skiing:            { label: "Skiing",            color: "bg-sky-400/20 text-sky-200 border-sky-400/30" },
  nordic_skiing:     { label: "Nordic Skiing",     color: "bg-indigo-500/20 text-indigo-300 border-indigo-500/30" },
  paddling:          { label: "Paddling",          color: "bg-teal-500/20 text-teal-300 border-teal-500/30" },
  golf:              { label: "Golf",               color: "bg-green-500/20 text-green-300 border-green-500/30" },
  team_sports:       { label: "Sport",             color: "bg-rose-500/20 text-rose-300 border-rose-500/30" },
  fitness_equipment: { label: "Fitness",           color: "bg-orange-400/20 text-orange-200 border-orange-400/30" },
  mind_body:         { label: "Yoga & Wellness",   color: "bg-violet-400/20 text-violet-200 border-violet-400/30" },
  other:             { label: "Activity",          color: "bg-slate-500/20 text-slate-300 border-slate-500/30" },
};

function getSportLabel(activity, sportType) {
  const sport = (activity?.sport || "").toLowerCase();
  const sub = (activity?.sub_sport || "").toLowerCase();

  if (sportType === "hiking" && /walk/i.test(sport)) return "Walking";
  if (sportType === "hiking") return "Hiking";
  if (sportType === "running") {
    if (/trail/i.test(sport + sub)) return "Trail Running";
    if (/treadmill/i.test(sub)) return "Treadmill";
    if (/track/i.test(sub)) return "Track Running";
    if (/virtual/i.test(sport + sub)) return "Virtual Running";
  }
  if (sportType === "cycling") {
    if (/gravel/i.test(sport + sub)) return "Gravel Cycling";
    if (/road/i.test(sub)) return "Road Cycling";
    if (/e.bik/i.test(sport)) return "E-Biking";
  }
  if (sportType === "swimming" && /open.water/i.test(sport + sub)) return "Open Water Swim";
  if (sportType === "rowing" && /indoor/i.test(sport + sub)) return "Indoor Rowing";
  if (sportType === "skiing") {
    if (/snowboard/i.test(sport + sub)) return "Snowboarding";
    return "Alpine Skiing";
  }
  if (sportType === "nordic_skiing") {
    if (/snowshoe/i.test(sport + sub)) return "Snowshoeing";
    return "Nordic Skiing";
  }
  if (sportType === "paddling") {
    if (/kayak/i.test(sport + sub)) return "Kayaking";
    if (/surf/i.test(sport + sub)) return "Surfing";
    if (/sail/i.test(sport + sub)) return "Sailing";
    if (/wind/i.test(sport + sub)) return "Windsurfing";
    if (/stand.up|sup/i.test(sport + sub)) return "Stand Up Paddling";
    return "Paddling";
  }
  if (sportType === "team_sports") {
    const sportMap = {
      basketball: "Basketball", soccer: "Soccer", football: "Football",
      tennis: "Tennis", volleyball: "Volleyball", baseball: "Baseball",
      softball: "Softball", hockey: "Hockey", lacrosse: "Lacrosse",
      rugby: "Rugby", handball: "Handball", squash: "Squash",
      racquetball: "Racquetball", pickleball: "Pickleball", badminton: "Badminton",
      boxing: "Boxing", wrestling: "Wrestling",
    };
    for (const [key, val] of Object.entries(sportMap)) {
      if (sport.includes(key)) return val;
    }
    return "Team Sport";
  }
  if (sportType === "fitness_equipment") {
    if (/elliptical/i.test(sport + sub)) return "Elliptical";
    if (/stair/i.test(sport + sub)) return "Stair Climbing";
    if (/treadmill/i.test(sport + sub)) return "Treadmill";
    return "Fitness Equipment";
  }
  if (sportType === "mind_body") {
    if (/yoga/i.test(sport + sub)) return "Yoga";
    if (/pilates/i.test(sport + sub)) return "Pilates";
    if (/breath/i.test(sport + sub)) return "Breathwork";
    if (/meditat/i.test(sport + sub)) return "Meditation";
    return "Wellness";
  }

  return SPORT_CONFIG[sportType]?.label ?? "Activity";
}

export function ActivityHeader({ activity, sportType = "other" }) {
  const [editing, setEditing] = useState(false);
  const [localName, setLocalName] = useState(null);
  const [draft, setDraft] = useState("");

  if (!activity) return null;

  const baseTitle = activity.name || activity.title || getSportLabel(activity, sportType);
  const displayTitle = localName ?? baseTitle;
  const dateStr = fmtDate(activity.started_at);
  const cfg = SPORT_CONFIG[sportType] ?? SPORT_CONFIG.other;
  const sportLabel = getSportLabel(activity, sportType);

  const startEdit = () => {
    setDraft(displayTitle);
    setEditing(true);
  };

  const commit = async () => {
    const trimmed = draft.trim();
    if (trimmed && trimmed !== displayTitle) {
      try {
        await api.patchActivity(activity.id, { name: trimmed });
        setLocalName(trimmed);
      } catch (e) {
        console.error("Failed to rename activity", e);
      }
    }
    setEditing(false);
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter") commit();
    if (e.key === "Escape") setEditing(false);
  };

  return (
    <div className="flex flex-col items-center text-center pt-3.5 mb-6">
      {editing ? (
        <input
          autoFocus
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={handleKeyDown}
          className="text-2xl font-bold text-slate-900 dark:text-white bg-transparent border-b-2 border-blue-500 outline-none text-center w-full max-w-xl"
        />
      ) : (
        <h1
          className="text-2xl font-bold text-slate-900 dark:text-white cursor-pointer hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
          onClick={startEdit}
          title="Click to rename"
        >
          {displayTitle}
        </h1>
      )}
      <div className="flex items-center gap-2 mt-1">
        <span className="text-sm text-slate-500 dark:text-slate-400">{dateStr}</span>
        <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-full border ${cfg.color}`}>
          {sportLabel}
        </span>
      </div>
    </div>
  );
}

export default ActivityHeader;
