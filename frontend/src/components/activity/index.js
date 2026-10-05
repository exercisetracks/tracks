// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Barrel exports for activity components

// Layouts
export { ActivityView } from "./layouts/ActivityView";
export { ActivityHeader } from "./layouts/ActivityHeader";
export { ClimbingLayout } from "./layouts/ClimbingLayout";
export { BoulderingLayout } from "./layouts/BoulderingLayout";
export { GolfLayout } from "./layouts/GolfLayout";
export { StrengthLayout } from "./layouts/StrengthLayout";
export { RunningLayout } from "./layouts/RunningLayout";
export { HikingLayout } from "./layouts/HikingLayout";
export { CyclingLayout } from "./layouts/CyclingLayout";
export { IndoorCyclingLayout } from "./layouts/IndoorCyclingLayout";
export { SwimmingLayout } from "./layouts/SwimmingLayout";
export { RowingLayout } from "./layouts/RowingLayout";
export { TriathlonLayout } from "./layouts/TriathlonLayout";
export { SkiingLayout } from "./layouts/SkiingLayout";
export { NordicSkiingLayout } from "./layouts/NordicSkiingLayout";
export { PaddlingLayout } from "./layouts/PaddlingLayout";
export { TeamSportsLayout } from "./layouts/TeamSportsLayout";
export { FitnessEquipmentLayout } from "./layouts/FitnessEquipmentLayout";
export { MindBodyLayout } from "./layouts/MindBodyLayout";
export { FallbackLayout } from "./layouts/FallbackLayout";

// Graphs
export { HeartRateGraph } from "./graphs/HeartRateGraph";
export { PaceGraph } from "./graphs/PaceGraph";
export { SpeedGraph } from "./graphs/SpeedGraph";
export { ElevationGraph } from "./graphs/ElevationGraph";
export { PowerGraph } from "./graphs/PowerGraph";
export { CadenceGraph } from "./graphs/CadenceGraph";
export { GritFlowGraph } from "./graphs/GritFlowGraph";
export { WeightGraph } from "./graphs/WeightGraph";
export { VolumeGraph } from "./graphs/VolumeGraph";
export { RepGraph } from "./graphs/RepGraph";
export { StrengthHRGraph } from "./graphs/StrengthHRGraph";

// Charts
export { HRHistogram } from "./charts/HRHistogram";
export { ClimbEffortChart } from "./charts/ClimbEffortChart";
export { IntensityZoneChart } from "./charts/IntensityZoneChart";
export { EffortZoneChart } from "./charts/EffortZoneChart";
export { RestHistogram } from "./charts/RestHistogram";
export { OneRMGraph } from "./charts/OneRMGraph";

// Pills/Displays
export { StatPill, StatPillsStrip, StatPillsGrid } from "./pills/StatPills";
export { DurationDisplay, DurationBar } from "./pills/DurationDisplay";
export { WeightDisplay, WeightPill, WeightRange } from "./pills/WeightDisplay";
export { GradeDisplay, GradePill, GradeRange } from "./pills/GradeDisplay";

// Timelines
export { ActivityTimeline, SimpleTimeline } from "./timelines/ActivityTimeline";

// Tables
export { ActivityTable, CompactTable } from "./tables/ActivityTable";

// Cards
export { ResultBadge, StatusBadge, ResultIcon } from "./cards/ResultBadge";
export { ExerciseCard } from "./cards/ExerciseCard";
export { ExerciseCardGroup } from "./cards/ExerciseCardGroup";

// Utils
export { HRColorLine, TimeTooltip, ChartCard, InfoTooltip, xAxisProps, yAxisProps, gridProps } from "./utils/chartHelpers.jsx";

// Sport utilities
export {
  SPORT_TYPES,
  getSportType,
  isSportType,
  isBouldering,
  isClimbing,
  isGolf,
  isStrength,
  isRunning,
  isHiking,
  isCycling,
  isMTB,
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
} from "../../utils/sportUtils";
