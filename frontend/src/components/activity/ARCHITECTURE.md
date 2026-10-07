# Activity Components Architecture

## Overview

The fitness/activity screens use a modular, component-based architecture. Each sport layout has full control over its display while sharing common utilities and visualization components. The system supports 19 sport types with dedicated layouts plus a fallback.

## Directory Structure

```
frontend/src/components/activity/
├── layouts/                       # Sport-specific layout components
│   ├── ActivityView.jsx           # Main dispatcher - fetches data & delegates to layouts
│   ├── ActivityHeader.jsx         # Activity title, date, sport badge (SPORT_CONFIG map)
│   ├── RunningLayout.jsx
│   ├── HikingLayout.jsx
│   ├── CyclingLayout.jsx
│   ├── IndoorCyclingLayout.jsx
│   ├── StrengthLayout.jsx
│   ├── ClimbingLayout.jsx         # Rope/sport/indoor climbing
│   ├── BoulderingLayout.jsx       # Bouldering (separate from rope climbing)
│   ├── SwimmingLayout.jsx
│   ├── RowingLayout.jsx
│   ├── TriathlonLayout.jsx
│   ├── SkiingLayout.jsx           # Alpine skiing & snowboarding
│   ├── NordicSkiingLayout.jsx
│   ├── PaddlingLayout.jsx         # Kayak, SUP, canoe, etc.
│   ├── GolfLayout.jsx             # Golf with per-hole scorecard
│   ├── TeamSportsLayout.jsx
│   ├── FitnessEquipmentLayout.jsx
│   ├── MindBodyLayout.jsx
│   └── FallbackLayout.jsx         # Generic display for unrecognised sports
│
├── graphs/                        # Time-series graph components
│   ├── HeartRateGraph.jsx         # Heart rate over time
│   ├── PaceGraph.jsx              # Pace over time (standalone card)
│   ├── PaceSpeedGraph.jsx         # Dual-axis pace (left, reversed) + speed (right)
│   ├── SpeedGraph.jsx             # Speed over time
│   ├── ElevationGraph.jsx         # Elevation profile
│   ├── PowerGraph.jsx             # Power output (cycling/rowing)
│   ├── CadenceGraph.jsx           # Cadence / RPM / SPM
│   ├── GritFlowGraph.jsx          # Grit/Flow (mountain biking)
│   ├── WeightGraph.jsx            # Weight per set (strength)
│   ├── VolumeGraph.jsx            # Volume = weight × reps (strength)
│   ├── RepGraph.jsx               # Reps per set (strength)
│   ├── OneRMGraph.jsx             # Estimated 1-rep max (strength)
│   └── StrengthHRGraph.jsx        # Heart rate during strength workout
│
├── charts/                        # Histogram and summary charts
│   ├── HRHistogram.jsx            # HR distribution + zone legend (Z1–Z5)
│   ├── RestHistogram.jsx          # Rest period distribution (strength)
│   ├── EffortZoneChart.jsx        # Time in power zones (cycling)
│   ├── ClimbEffortChart.jsx       # Climb difficulty vs HR (auto-detects mode)
│   ├── ClimbGradeHistogram.jsx    # Grade distribution: sends vs attempts (bouldering)
│   └── GolfScorecard.jsx          # Strokes-per-hole bar chart (golf)
│
├── tables/
│   ├── ActivityTable.jsx
│   └── CompactTable.jsx
│
├── pills/
│   ├── StatPills.jsx              # StatPill, StatPillsStrip, StatPillsGrid
│   ├── DurationDisplay.jsx
│   ├── WeightDisplay.jsx
│   └── GradeDisplay.jsx
│
├── timelines/
│   └── ActivityTimeline.jsx       # Horizontal phase timeline (accepts getBgColor prop)
│
├── cards/
│   ├── ExerciseCard.jsx
│   ├── ExerciseCardGroup.jsx
│   └── ResultBadge.jsx
│
└── utils/
    └── chartHelpers.jsx           # ChartCard, InfoTooltip, axis helpers
```

## Component Hierarchy & Data Flow

```
URL Route (/activity/:id)
      ↓
ActivityView.jsx
  useActivityData(id) → parallel fetch: activity, track, laps, sets, climbs, golfHoles, settings
      ↓
Loading/Error States → Sport Type Detection (getSportType)
      ↓
Appropriate Sport Layout Component
      ↓
Layout Receives ALL Raw Data:
   activity, track, laps, sets, climbs, golfHoles, imperial, sportType
      ↓
Layout Computes Derived Data Internally (useMemo):
   hrData, speedData, elevData, cadenceData, lapTimes, hrHistogram, …
      ↓
Two-Column Grid Layout (lg:grid-cols-2):
   LEFT:  Sport-specific graphs (HR, pace/speed, elevation, cadence, power, …)
   RIGHT: Map (if GPS), HRHistogram, optional lap/climb/hole tables
   BOTTOM (full-width): Lap table for running, cycling, hiking (outside grid)
      ↓
Final Rendered View
```

## Sport Type Routing

Sport types are detected in `utils/sportUtils.js` via `getSportType(activity)`. Detection order matters — more specific types are checked before broad ones.

| Sport Type       | Key Signal(s)                                      | Layout              |
|------------------|----------------------------------------------------|---------------------|
| `golf`           | `golf` in sport/sub_sport                          | GolfLayout          |
| `bouldering`     | `bouldering` in sport/sub_sport                    | BoulderingLayout    |
| `climbing`       | `rock_climbing`, `floor_climbing`, `indoor_climbing`| ClimbingLayout      |
| `strength`       | sport=`training`, `strength_training`, `crossfit`  | StrengthLayout      |
| `mind_body`      | `yoga`, `pilates`, `breath`, `meditat`             | MindBodyLayout      |
| `hiking`         | `hik`, `walk` in sport                             | HikingLayout        |
| `running`        | `run` in sport or sub_sport=`treadmill`            | RunningLayout       |
| `indoor_cycling` | `indoor_cycling`, `virtual`, `spin`                | IndoorCyclingLayout |
| `mtb`            | `mountain_bik`, `trail_bik`, `mtb`                 | CyclingLayout (MTB) |
| `cycling`        | `cycl`, `bik`, `ride`                              | CyclingLayout       |
| `swimming`       | `swim`, `open_water`                               | SwimmingLayout      |
| `rowing`         | `row`                                              | RowingLayout        |
| `triathlon`      | `triathlon`, `multisport`, `duathlon`              | TriathlonLayout     |
| `skiing`         | `alpine_ski`, `downhill_ski`, `snowboard`          | SkiingLayout        |
| `nordic_skiing`  | `cross_country`, `nordic_ski`, `snowshoe`          | NordicSkiingLayout  |
| `paddling`       | `kayak`, `canoe`, `stand_up`, `surfing`, …         | PaddlingLayout      |
| `fitness_equipment` | `elliptical`, `stair`, `fitness_equipment`      | FitnessEquipmentLayout |
| `team_sports`    | ball sports, combat sports                         | TeamSportsLayout    |
| `other`          | fallback                                           | FallbackLayout      |

## ActivityTimeline Usage

The timeline bar (`ActivityTimeline`) is **only shown** for activities where phases matter:

- **Strength**: active sets vs rest sets (colored by HR via default getBgColor)
- **Climbing / Bouldering**: climb_active vs climb_rest segments
- **Skiing**: on-run (sky-400 blue) vs lift/rest (slate-400/25) via custom `getBgColor`

All other layouts (running, cycling, hiking, swimming, rowing, paddling, etc.) **do not** render the timeline.

## HRHistogram Zone Legend

`HRHistogram` now includes a built-in zone legend below the chart (replaces the removed `IntensityZoneChart`):

- Zones: Z1 Recovery (< 60%), Z2 Aerobic (60–70%), Z3 Tempo (70–80%), Z4 Threshold (80–90%), Z5 VO₂ Max (> 90% of maxHR)
- Each zone shows: colored indicator + label + formatted time-in-zone + mini progress bar
- `ReferenceLine` markers on the histogram delineate zone boundaries
- `IntensityZoneChart` is retained in `charts/` for legacy use but is **not imported** by any layout

## PaceSpeedGraph

Dual-axis chart for pace + speed on the same ComposedChart:

- **Left axis**: Pace (min/km or min/mi), reversed so faster = higher
- **Right axis**: Speed (km/h or mph), normal orientation
- Filters out points with speed < 0.3 m/s (stops/GPS errors)
- Caps pace at 30 min/km to hide extreme spikes
- Used by RunningLayout and HikingLayout; regular SpeedGraph used for cycling/paddling/skiing

## Golf Data Pipeline

Golf activities use Garmin FIT `lap` messages as holes. Backend flow:

1. `_parse_lap()` in `backend/app/parsers/activity.py` extracts `total_strokes`, `total_putts`, `avg_stroke_distance`, `hole_time_in_zone` from each lap frame
2. These are stored in `laps.total_strokes` etc. (added by migration `016_golf_hole_columns.sql`)
3. `GET /activities/{id}/golf-holes` endpoint returns `list[GolfHoleSummary]` (returns 404 for non-golf)
4. `useActivityData` fetches golf holes in parallel with other data (silently swallowed on 404)
5. `GolfLayout` displays GolfScorecard (strokes per hole bar chart) + hole-by-hole table + GPS route

## Adding a New Sport Type

1. **sportUtils.js**: Add to `SPORT_TYPES`, add detection rule in `getSportType`, add `isSport` helper
2. **Create layout**: `layouts/{Sport}Layout.jsx` — two-column grid, compute derived data with useMemo
3. **ActivityHeader.jsx**: Add entry to `SPORT_CONFIG` (label + badge color)
4. **StatPills.jsx**: Add gradient to `SPORT_GRADIENTS`
5. **ActivityView.jsx**: Import layout, import `isSport` helper, add routing line
6. **index.js**: Export layout and `isSport` helper

## Shared Components Reference

### StatPillsStrip
Renders a sport-specific gradient header bar with horizontally scrollable stat pills. Accepts `sportType` prop for gradient selection.

### HeartRateGraph
Synchronized with other charts via `syncId="activity-time"`. Accepts `lapTimes` (array of elapsed seconds) for vertical lap markers.

### ActivityTimeline
Horizontal bar divided into segments. `getBgColor(split) => cssColor` prop allows custom coloring per segment. Default coloring is HR-based.

## Key Constraints

- **Tailwind**: Version 3.4.0, `darkMode: "media"` (follows system preference — no class-based toggle)
- **Recharts**: Version 2.12.0; `syncId="activity-time"` on all charts enables synchronized hover
- **Units**: `imperial` prop propagates from user settings; layouts handle conversion locally
- **No IntensityZoneChart in layouts**: Zone information lives in HRHistogram legend instead
