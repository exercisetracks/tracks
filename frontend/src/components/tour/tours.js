// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Onboarding tutorial content — pure data, no React. Each tour is an ordered
// list of steps shown as small non-blocking tooltips. A step's `anchor` is a
// CSS selector for the `data-tour="…"` element it points at; omit `anchor`
// (or if the element isn't on the page) and the tip renders centered.
//
// `placement` is the preferred side of the anchor to show the card on
// ("top" | "bottom" | "left" | "right" | "center"); TourTooltip flips it when
// there isn't room. Copy uses the app's user-facing names — note the sidebar
// quirk: the "/goals" route is labeled "Training" and "/training" is "Strength".

export const TOURS = {
  dashboard: [
    {
      title: "Welcome to Tracks",
      body: "This is your fitness command center. Here's a quick tour of what each part does — you can replay it any time from Settings.",
      placement: "center",
    },
    {
      anchor: '[data-tour="nav"]',
      title: "Your sections",
      body: "Everything lives in this sidebar: Dashboard, Activities, Training goals, Strength, Flexibility, Race Plans, Health, and Maps. Each one shows its own quick tips the first time you open it.",
      placement: "right",
    },
    {
      anchor: '[data-tour="dashboard-period"]',
      title: "Pick a time window",
      body: "Every stat and chart on this page follows this selector — from the last week all the way to your lifetime totals.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="dashboard-overview"]',
      title: "Your headline numbers",
      body: "Totals and per-activity averages for the selected period: activity count, distance, time, and more.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="dashboard-upcoming"]',
      title: "What's next",
      body: "Today's recommended or planned workout and your latest VO₂ max estimate — a quick read on where you stand and what to do next.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="dashboard-fitness"]',
      title: "Your fitness trend",
      body: "Fitness (CTL), Fatigue (ATL), and Form over time. It climbs as you train and dips when you rest.",
      placement: "top",
    },
    {
      anchor: '[data-tour="sync"]',
      title: "Sync your watch",
      body: "Plug in your Garmin and hit Sync to pull in new activities, sleep, and readiness — then everything here updates automatically.",
      placement: "right",
    },
    {
      anchor: '[data-tour="settings-link"]',
      title: "Settings & replay",
      body: "Profile, training zones, devices, appearance — and the button to replay this tutorial — all live in Settings.",
      placement: "right",
    },
  ],

  activities: [
    {
      anchor: '[data-tour="activities-filters"]',
      title: "Find any activity",
      body: "Search by name, filter by sport, or narrow to a date range to zero in on the workouts you want.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="activities-sort"]',
      title: "Sort your list",
      body: "Order by date, distance, pace, heart rate, elevation, or calories — ascending or descending.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="activities-table"]',
      title: "Open the details",
      body: "Click any row to open the full activity — maps, splits, and charts. You can also preview a route on the map from here.",
      placement: "top",
    },
  ],

  goals: [
    {
      title: "Training goals",
      body: "Goals shape your coaching. Set what you're working toward and Tracks tailors its recommendations to match.",
      placement: "center",
    },
    {
      anchor: '[data-tour="goals-new"]',
      title: "Create a goal",
      body: "Add a weekly volume target, a general fitness push, or a specific race/event you're training for.",
      placement: "left",
    },
    {
      anchor: '[data-tour="goals-active"]',
      title: "One active goal",
      body: "You keep one goal active at a time. Choose a race with a date and Tracks builds a full plan through Base, Build, Peak, and Taper phases.",
      placement: "top",
    },
  ],

  training: [
    {
      anchor: '[data-tour="strength-tabs"]',
      title: "Strength training",
      body: "Two tabs: Exercises to browse and log lifts (with 1-rep-max tracking), and Workouts to build or run a guided strength session.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="strength-content"]',
      title: "Log & progress",
      body: "Open an exercise for how-to guidance, log your sets, or add custom movements. Logged sessions feed the progression engine so your weights climb over time.",
      placement: "top",
    },
  ],

  flexibility: [
    {
      anchor: '[data-tour="flex-tabs"]',
      title: "Flexibility & mobility",
      body: "Stretches to browse and log individual mobility work, and Flows to string stretches into a guided routine.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="flex-content"]',
      title: "Guided & custom",
      body: "Open any stretch for instructions, or create your own custom stretches and flows.",
      placement: "top",
    },
  ],

  "race-plans": [
    {
      anchor: '[data-tour="raceplans-intro"]',
      title: "Race pacing plans",
      body: "Pacing guides for your events — built from your VDOT fitness, freshness, the course profile, and race-day weather.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="raceplans-card"]',
      title: "Generate & sync",
      body: "Each event goal gets a plan you can generate, review split-by-split, and sync to your watch. No events yet? Create one under Training.",
      placement: "top",
    },
  ],

  health: [
    {
      anchor: '[data-tour="health-vitals"]',
      title: "Every reading on its scale",
      body: "Each dial shows today's reading against its range — green is good, red is worth a look. Click any dial to see its history and what the number means.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="health-range"]',
      title: "Pick the window",
      body: "How far back every history on the page looks, from the last week to your lifetime.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="health-sleep"]',
      title: "Your nights on a clock",
      body: "Each night is drawn when it happened, split into deep, REM and light sleep. Click a night to see it stage by stage.",
      placement: "top",
    },
    {
      anchor: '[data-tour="health-log"]',
      title: "Log an entry",
      body: "Add weight, hydration, or calories for any date. Meals and medications have their own sections below.",
      placement: "top",
    },
    {
      anchor: '[data-tour="health-injuries"]',
      title: "Injury tracking",
      body: "Log injuries onto a timeline and see the activities that may have contributed to each one.",
      placement: "top",
    },
  ],

  maps: [
    {
      anchor: '[data-tour="map-toolbar"]',
      title: "Navigate in 3D",
      body: "Drag the compass to rotate, drag up to tilt into 3D, tap the 2D/3D pip to switch views, and use −/+ to zoom.",
      placement: "left",
    },
    {
      anchor: '[data-tour="map-tools"]',
      title: "Map tools",
      body: "A Legend, Layers to toggle trails and overlays, Areas to download high-resolution offline tiles, and Merge to combine tracks.",
      placement: "right",
    },
    {
      anchor: '[data-tour="map-tracks"]',
      title: "Build & save routes",
      body: "Click points to build a route (snapped to trails), check its elevation profile, and save it to My Tracks.",
      placement: "left",
    },
  ],

  settings: [
    {
      anchor: '[data-tour="settings-tutorial"]',
      title: "Tutorial controls",
      body: "Toggle the tips on or off, or replay the whole tutorial from the start. The rest of this page tunes your profile, zones, devices, and appearance.",
      placement: "top",
    },
  ],
};

// Exact pathname → tourId. Detail routes (e.g. /activities/:id) are intentionally
// omitted so tips only fire on the top-level section pages.
export const ROUTE_TOURS = {
  "/": "dashboard",
  "/activities": "activities",
  "/goals": "goals",
  "/training": "training",
  "/flexibility": "flexibility",
  "/race-plans": "race-plans",
  "/health": "health",
  "/maps": "maps",
  "/settings": "settings",
};

// Resolve a pathname to its tourId (or null). Trailing slashes are tolerated.
export function tourIdForPath(pathname) {
  if (!pathname) return null;
  const clean = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  return ROUTE_TOURS[clean] ?? null;
}
