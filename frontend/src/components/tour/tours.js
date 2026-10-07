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
      body: "Fitness (CTL), Fatigue (ATL), and Form over time. It climbs as you train and dips when you rest. Hover the ⓘ beside any title for what its number means.",
      placement: "top",
    },
    {
      anchor: '[data-tour="dashboard-volume"]',
      title: "Week by week",
      body: "How much you trained each week — distance and time side by side, so a slow week on the bike and a long week on foot read fairly.",
      placement: "top",
    },
    {
      anchor: '[data-tour="dashboard-history"]',
      title: "Filter by sport",
      body: "Every day you trained, and your mix of sports. Click a sport to narrow the volume chart and the map to just that one; click it again to see everything.",
      placement: "top",
    },
    {
      anchor: '[data-tour="dashboard-locations"]',
      title: "Where you train",
      body: "A heatmap of every route you've recorded. The full map, with route building and offline areas, is under Maps.",
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
      body: "Click a column to order by it — date, distance, pace, heart rate, elevation or calories — and again to flip the direction.",
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
    {
      anchor: '[data-tour="goals-plan"]',
      title: "Your plan, day by day",
      body: "Each workout sits on its day. Click one for its steps and targets, and send the plan to your watch with Sync to Watch. The plan rebuilds itself when your goal or settings change — there's nothing to regenerate.",
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
      title: "Log today",
      body: "Weight, water and food in one form. Tick “Remember this meal” and it becomes a one-click chip for next time; pick a date to fill in a day you missed.",
      placement: "top",
    },
    {
      anchor: '[data-tour="health-injuries"]',
      title: "Injury tracking",
      body: "Log injuries onto a timeline and see the activities that may have contributed to each one.",
      placement: "top",
    },
    {
      anchor: '[data-tour="health-meds"]',
      title: "Medications",
      body: "Add what you take and tick off each dose. The phone app reminds you when one is due, and the history shows what was taken when.",
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

  activity: [
    {
      anchor: '[data-tour="activity-header"]',
      title: "One activity, laid out for its sport",
      body: "A run shows pace and splits, a ride power, a climb its routes — each sport gets the cards that matter for it. Click the name to rename it.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="activity-delete"]',
      title: "Remove a recording",
      body: "Delete an activity you don't want — a test recording, a duplicate. It leaves your stats and your fitness trend with it.",
      placement: "left",
    },
  ],

  music: [
    {
      title: "Music on your watch",
      body: "Two ways to get music onto a Garmin: over the cable from a library kept here, or straight from your own music server through the Tracks Music watch app.",
      placement: "center",
    },
    {
      anchor: '[data-tour="music-server"]',
      title: "Your music server",
      body: "Connect Navidrome or any server speaking the Subsonic API. Tracks keeps references, not copies — audio is fetched only when a watch needs it.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="music-watchapp"]',
      title: "No cable needed",
      body: "Tracks Music on the watch downloads playlists from that server over the watch's own Wi-Fi, on the charger. The phone app installs it and signs it in.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="music-add"]',
      title: "Add music",
      body: "Upload mp3, m4a, flac or wav files. Anything that isn't already a watch-friendly mp3 is converted on upload.",
      placement: "bottom",
    },
    {
      anchor: '[data-tour="music-playlists"]',
      title: "Choose what to carry",
      body: "Tick the playlists and tracks the watch should hold. Carrying a playlist carries its tracks too.",
      placement: "top",
    },
    {
      anchor: '[data-tour="music-send"]',
      title: "Send to watch",
      body: "Plug the watch in and send: the files to add and remove are worked out for you.",
      placement: "bottom",
    },
  ],

  settings: [
    {
      anchor: '[data-tour="settings-tutorial"]',
      title: "Tutorial controls",
      body: "Toggle the tips on or off, or replay the whole tutorial from the start. The rest of this page tunes your profile, zones, devices, and appearance.",
      placement: "top",
    },
    {
      anchor: '[data-tour="settings-devices"]',
      title: "Your devices",
      body: "Every watch that has sent Tracks a file. Claim the ones that are yours — only claimed devices feed your stats and coaching — and mark your main watch as primary.",
      placement: "top",
    },
    {
      anchor: '[data-tour="settings-backup"]',
      title: "Keep a backup",
      body: "Download an encrypted file of everything in your account. It is the same file the phone app makes, so either can restore the other's.",
      placement: "top",
    },
  ],
};

// Exact pathname → tourId. Detail routes are left out, so tips fire on the
// section pages — with one exception, the activity page (ACTIVITY_PATH): it is
// where people spend the most time, and the one detail page whose controls
// (rename by clicking the title) nobody finds unprompted.
export const ROUTE_TOURS = {
  "/": "dashboard",
  "/activities": "activities",
  "/goals": "goals",
  "/training": "training",
  "/flexibility": "flexibility",
  "/race-plans": "race-plans",
  "/health": "health",
  "/maps": "maps",
  "/music": "music",
  "/settings": "settings",
};

const ACTIVITY_PATH = /^\/activities\/\d+$/;

// Resolve a pathname to its tourId (or null). Trailing slashes are tolerated.
export function tourIdForPath(pathname) {
  if (!pathname) return null;
  const clean = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  if (ACTIVITY_PATH.test(clean)) return "activity";
  return ROUTE_TOURS[clean] ?? null;
}
