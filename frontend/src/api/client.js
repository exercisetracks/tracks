// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { BASE_URL } from "../apiBase";
export const TOKEN_KEY = "tracks_token";

// In-memory response cache for idempotent GET requests.
// Keys are method+path; entries auto-expire after their TTL.
const _cache = new Map();
const _CACHE_TTL_MS = 60_000; // 1 minute default

function _cacheKey(method, path) {
  return `${method}:${path}`;
}

function _cacheGet(method, path) {
  const entry = _cache.get(_cacheKey(method, path));
  if (entry && Date.now() - entry.time < entry.ttl) return entry.data;
  _cache.delete(_cacheKey(method, path));
  return undefined;
}

function _cacheSet(method, path, data, ttl = _CACHE_TTL_MS) {
  _cache.set(_cacheKey(method, path), { data, time: Date.now(), ttl });
}

function _cacheDel(method, path) {
  _cache.delete(_cacheKey(method, path));
}

// Delete all cache entries whose key starts with `method:pathPrefix`.
// Needed for endpoints with query-string variants (e.g. ?days=7, ?days=30).
function _cacheDelPrefix(method, pathPrefix) {
  const prefix = `${method}:${pathPrefix}`;
  for (const key of _cache.keys()) {
    if (key.startsWith(prefix)) _cache.delete(key);
  }
}

async function request(method, path, body, { signal, ttl, noCache } = {}) {
  const token = localStorage.getItem(TOKEN_KEY);
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers["Authorization"] = `Bearer ${token}`;

  // Serve from cache for idempotent GETs when no signal is attached
  // (signalled requests are by definition fresh-fetch).
  if (method === "GET" && !signal && !noCache) {
    const cached = _cacheGet(method, path);
    if (cached !== undefined) return cached;
  }

  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal,
  });

  if (res.status === 204) return null;

  const data = await res.json().catch(() => ({}));

  if (!res.ok) {
    const err = new Error(data.detail ?? `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }

  // Cache successful GET responses (no signal/forced refresh to avoid caching stale data).
  if (method === "GET" && !signal && !noCache) {
    _cacheSet(method, path, data, ttl);
  }

  return data;
}

const get   = (path, opts)       => request("GET",    path, undefined, opts);
const post  = (path, body, opts) => request("POST",   path, body, opts);
const patch = (path, body, opts) => request("PATCH",  path, body, opts);
const put   = (path, body, opts) => request("PUT",    path, body, opts);
const del   = (path, opts)       => request("DELETE", path, undefined, opts);

function qs(params) {
  const q = new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return q ? `?${q}` : "";
}

export const api = {
  /** Forget every cached response — after a restore has changed everything at once. */
  clearCache: () => _cache.clear(),

  // Replica sync, as the phone speaks it (spec/sync.yaml) — used by backups.
  syncPull: (since, limit) => get(`/sync/pull?since=${since}&limit=${limit}`, { noCache: true }),
  syncPush: (body) => post("/sync/push", body),
  /** A FIT file's bytes by hash, or null if the account has no such file. */
  syncBlob: async (sha256) => {
    const token = localStorage.getItem(TOKEN_KEY);
    const r = await fetch(`${BASE_URL}/sync/blobs/${sha256}`, { headers: { Authorization: `Bearer ${token}` } });
    if (r.status === 404) return null;
    if (!r.ok) {
      const data = await r.json().catch(() => ({}));
      throw Object.assign(new Error(data.detail ?? `HTTP ${r.status}`), { status: r.status });
    }
    return new Uint8Array(await r.arrayBuffer());
  },

  // Auth
  getAuthStatus:  ()                         => get("/auth/status"),
  login:          (username, password)       => post("/auth/login",  { username, password }),
  setup:          (username, name, password, enableGarminSync = true) =>
                    post("/auth/setup", { username, name, password, enable_garmin_sync: enableGarminSync }),
  syncPendingImports: () => post("/auth/sync-pending-imports"),

  // Current user
  getMe:           ()     => get("/users/me"),
  updateMe:        (data) => { _cacheDel("GET", "/users/me"); return patch("/users/me", data); },
  changePassword:  (data) => post("/users/me/password", data),
  getSettings:     ()     => get("/users/me/settings"),
  getVersionStatus: ()    => get("/version", { noCache: true }),
  getSessions:     ()     => get("/auth/sessions", { noCache: true }),
  updateSettings:  (data) => { _cacheDel("GET", "/users/me/settings"); return patch("/users/me/settings", data); },
  recalculate:     ()     => { _cacheDel("GET", "/users/me/settings"); return post("/users/me/settings/recalculate"); },
  clearAllData:    ()     => del("/users/me/data"),

  // Admin: user management
  listUsers:   (opts) => get("/users/", opts),
  createUser:  (data) => { _cacheDel("GET", "/users/"); return post("/users/", data); },
  deleteUser:  (id)   => { _cacheDel("GET", "/users/"); return del(`/users/${id}`); },
  toggleAdmin: (id)   => { _cacheDel("GET", "/users/"); return patch(`/users/${id}/admin`); },

  // Devices
  getDevices:    ()   => get("/devices/"),
  claimDevice:   (id) => { _cacheDel("GET", "/devices/"); return post(`/devices/${id}/claim`); },
  unclaimDevice: (id) => { _cacheDel("GET", "/devices/"); return del(`/devices/${id}/claim`); },

  // Activities
  getActivities:  (p = {}) => get(`/activities/${qs(p)}`),
  getActivity:    (id)     => get(`/activities/${id}`),
  patchActivity:  (id, d)  => {
    _cacheDel("GET", `/activities/${id}`);
    _cacheDelPrefix("GET", "/activities/");
    return patch(`/activities/${id}`, d);
  },
  deleteActivity: (id, allowReimport = false) => {
    _cacheDel("GET", `/activities/${id}`);
    _cacheDelPrefix("GET", "/activities/");
    _cacheDelPrefix("GET", "/metrics/");
    return del(`/activities/${id}${allowReimport ? "?allow_reimport=true" : ""}`);
  },
  getTrack:       (id)     => get(`/activities/${id}/track`),
  getLaps:        (id)     => get(`/activities/${id}/laps`),
  getSets:        (id)     => get(`/activities/${id}/sets`),
  getClimbs:      (id)     => get(`/activities/${id}/climbs`),
  getGolfHoles:   (id)     => get(`/activities/${id}/golf-holes`),
  backfillClimbGrades: () => { _cacheDelPrefix("GET", "/activities/"); return post("/activities/backfill-climb-grades"); },
  getSports:      ()       => get("/activities/sports"),
  getHeatmap:     (p = {}) => get(`/activities/heatmap${qs(p)}`),
  getActivityTracks: (p = {}) => get(`/activities/tracks-geojson${qs(p)}`, { noCache: true }),
  backfill:       ()       => { _cacheDelPrefix("GET", "/activities/"); _cacheDelPrefix("GET", "/metrics/"); return post("/activities/backfill-metrics"); },
  backfillLaps:   ()       => { _cacheDelPrefix("GET", "/activities/"); return post("/activities/backfill-laps"); },

  // Tile version info (for cache-busting tile URLs after region merge)
  getTileVersion:  ()      => get("/tiles/version", { noCache: true }),

  // Map regions
  getRegions:      ()      => get("/maps/regions", { noCache: true }),
  getRegion:       (id)    => get(`/maps/regions/${id}`, { noCache: true }),
  getRegionProgress: (id)  => get(`/maps/regions/${id}/progress`, { noCache: true }),
  suggestRegionName: (bbox) => get(`/maps/regions/suggest-name?bbox=${bbox.join(",")}`, { noCache: true }),
  estimateRegion:  (bbox)  => get(`/maps/regions/estimate?bbox=${bbox.join(",")}`, { noCache: true }),
  downloadRegion:  (d)     => post("/maps/regions/download", d),
  deleteRegion:    (id)    => del(`/maps/regions/${id}`),
  snapRoute:       (d)     => post("/maps/route/snap", d),
  routeElevation:  (d)     => post("/maps/route/elevation", d),
  getRouteDetail:  (id, section = null) =>
    get(`/maps/route/${id}${section ? `?section=${encodeURIComponent(section)}` : ""}`),
  getPointInfo:    (lat, lon) => get(`/maps/point?lat=${lat}&lon=${lon}`, { noCache: true }),
  getGlobalDownloads:()    => get("/maps/global-downloads", { noCache: true }),
  searchPoi:       (q, limit = 10, bbox = null) => {
    let url = `/maps/poi/search?q=${encodeURIComponent(q)}&limit=${limit}`;
    if (bbox) url += `&bbox=${encodeURIComponent(bbox)}`;
    return get(url, { noCache: true });
  },
  getPoiFeatures:  (bbox, zoom = 12, { signal } = {}) => get(`/maps/poi/features?bbox=${encodeURIComponent(bbox)}&zoom=${zoom}`, { noCache: true, signal }),
  // Live wildfire + smoke overlays (opt-in; backend proxies NIFC / NOAA and
  // caches server-side — client cache only smooths quick toggle flips).
  getWildfires:     () => get("/maps/wildfires", { ttl: 300_000 }),
  getWildfireSmoke: () => get("/maps/wildfires/smoke", { ttl: 300_000 }),

  // Custom tracks (courses)
  getCourses:        ()      => get("/maps/courses", { noCache: true }),
  getCoursesGeojson: ()      => get("/maps/courses/geojson", { noCache: true }),
  getCourse:         (id)    => get(`/maps/courses/${id}`, { noCache: true }),
  createCourse:      (d)     => post("/maps/courses", d),
  updateCourse:      (id, d) => patch(`/maps/courses/${id}`, d),
  deleteCourse:      (id)    => del(`/maps/courses/${id}`),
  saveExternalCourse:(id)    => post(`/maps/courses/${id}/save-mine`, {}),
  courseFromActivity:(actId) => post(`/maps/courses/from-activity/${actId}`, {}),
  mergeCourses:      (d)     => post("/maps/courses/merge", d),
  // Track folders
  getCourseFolders:  ()      => get("/maps/folders", { noCache: true }),
  createCourseFolder:(d)     => post("/maps/folders", d),
  updateCourseFolder:(id, d) => patch(`/maps/folders/${id}`, d),
  deleteCourseFolder:(id)    => del(`/maps/folders/${id}`),
  checkCourseTurns:  (d, opts) => post("/maps/courses/check-turns", d, opts),
  importCourse:      (file)  => {
    const fd = new FormData();
    fd.append("file", file);
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch(`${BASE_URL}/maps/courses/import`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: fd,
    }).then(async r => {
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(data.detail ?? `HTTP ${r.status}`), { status: r.status });
      return data;
    });
  },

  // Metrics
  getSummary:          (p = {}) => get(`/metrics/summary${qs(p)}`),
  getTrainingLoad:     (p = {}) => get(`/metrics/training-load${qs(p)}`),
  getDailyMetrics:     (p = {}) => get(`/metrics/daily${qs(p)}`),
  getPowerCurve:       (p = {}) => get(`/metrics/power-curve${qs(p)}`),
  getPaceCurve:        (p = {}) => get(`/metrics/pace-curve${qs(p)}`),
  getRacePredictions:  (p = {}) => get(`/metrics/race-predictions${qs(p)}`),
  getReadinessHistory: (days = 30) => get(`/metrics/readiness-history?days=${days}`),
  getVo2MaxHistory:    (p = {})   => get(`/metrics/vo2max-history${qs(p)}`),
  getBySort:           (p = {}) => get(`/metrics/by-sport${qs(p)}`),
  getActivityCalendar: (p = {}) => get(`/metrics/activity-calendar${qs(p)}`),
  getTrends:           (p = {}) => get(`/metrics/trends${qs(p)}`),
  getWeeklyVolume:     (p = {}) => get(`/metrics/weekly-volume${qs(p)}`),

  // Health
  getHealthSummary:    (days = 30) => get(`/health/summary?days=${days}`),
  // One night's stage timeline (empty `stages` for nights recorded before the
  // importer kept them), and the stress curve behind the daily averages.
  getSleepNight:       (date)   => get(`/health/sleep/${date}`),
  getStressDetail:     (p = {}) => get(`/health/stress${qs(p)}`),
  patchDailyMetric:    (date, data) => { _cacheDelPrefix("GET", "/health/summary"); return patch(`/health/daily/${date}`, data); },
  getInjuries:         ()       => get("/health/injuries"),
  createInjury:        (data)   => { _cacheDel("GET", "/health/injuries"); return post("/health/injuries", data); },
  updateInjury:        (id, data) => { _cacheDel("GET", "/health/injuries"); return patch(`/health/injuries/${id}`, data); },
  deleteInjury:        (id)     => { _cacheDel("GET", "/health/injuries"); return del(`/health/injuries/${id}`); },
  getInjuryActivities: (id, days = 7) => get(`/health/injuries/${id}/activities?window_days=${days}`),

  // Meals
  getMeals:           ()         => get("/meals"),
  createMeal:         (data)     => { _cacheDel("GET", "/meals"); return post("/meals", data); },
  updateMeal:         (id, data) => { _cacheDel("GET", "/meals"); return patch(`/meals/${id}`, data); },
  deleteMeal:         (id)       => { _cacheDel("GET", "/meals"); return del(`/meals/${id}`); },
  getMealLog:         (days = 7) => get(`/meals/log?days=${days}`),
  logMeal:            (data)     => { _cacheDelPrefix("GET", "/meals/log"); return post("/meals/log", data); },
  deleteMealLog:      (id)       => { _cacheDelPrefix("GET", "/meals/log"); return del(`/meals/log/${id}`); },

  // Medications
  getMedications:     ()         => get("/medications"),
  createMedication:   (data)     => { _cacheDel("GET", "/medications"); return post("/medications", data); },
  updateMedication:   (id, data) => { _cacheDel("GET", "/medications"); return patch(`/medications/${id}`, data); },
  deleteMedication:   (id)       => { _cacheDel("GET", "/medications"); return del(`/medications/${id}`); },
  getMedicationLog:   (days = 30) => get(`/medications/log?days=${days}`),
  logDose:            (data)     => { _cacheDelPrefix("GET", "/medications/log"); _cacheDel("GET", "/medications/due"); return post("/medications/log", data); },
  deleteDoseLog:      (id)       => { _cacheDelPrefix("GET", "/medications/log"); _cacheDel("GET", "/medications/due"); return del(`/medications/log/${id}`); },
  getDueMedications:  ()         => get("/medications/due"),

  // Coaching
  getCoachingToday:   (force = false) =>
    get(`/coaching/today${force ? "?force_refresh=true" : ""}`),
  getReadiness:       () => get("/coaching/readiness"),
  getWeeklyPlan:      () => get("/coaching/plan"),
  getCoachingHistory: (days = 30) => get(`/coaching/history?days=${days}`),
  getGoals:           () => get("/coaching/goals"),
  listGoals:          () => get("/coaching/goals"),
  getGoal:            (id) => get(`/coaching/goals/${id}`),
  // When this person could be ready for an event: { date, weeks, basis, reasons }.
  recommendedEventDate: (sport, distanceM) => get(`/coaching/goals/recommended-date${qs({ sport, distance_m: distanceM })}`),
  createGoal:         (d)  => { _cacheDel("GET", "/coaching/goals"); return post("/coaching/goals", d); },
  updateGoal:         (id, d) => { _cacheDel("GET", "/coaching/goals"); _cacheDel("GET", `/coaching/goals/${id}`); return patch(`/coaching/goals/${id}`, d); },
  deleteGoal:         (id) => { _cacheDel("GET", "/coaching/goals"); _cacheDel("GET", `/coaching/goals/${id}`); return del(`/coaching/goals/${id}`); },

  // Race plan
  getPredictedTime:   (id) => get(`/coaching/goals/${id}/predicted-time`),
  getRacePlan:        (id) => get(`/coaching/goals/${id}/race-plan`),
  updateRacePlan:     (id, d) => { _cacheDelPrefix("GET", `/coaching/goals/${id}/race-plan`); return patch(`/coaching/goals/${id}/race-plan`, d); },
  generateRacePlan:   (id) => { _cacheDelPrefix("GET", `/coaching/goals/${id}/race-plan`); return post(`/coaching/goals/${id}/race-plan/generate`); },
  uploadCoursGpx:     (id, file) => {
    _cacheDelPrefix("GET", `/coaching/goals/${id}/race-plan`);
    const fd = new FormData();
    fd.append("file", file);
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch(`${BASE_URL}/coaching/goals/${id}/race-plan/course`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: fd,
    }).then(async r => {
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(data.detail ?? `HTTP ${r.status}`), { status: r.status });
      return data;
    });
  },
  uploadFitFiles: (files) => {
    _cacheDelPrefix("GET", "/activities/");
    _cacheDelPrefix("GET", "/metrics/");
    const fd = new FormData();
    files.forEach(f => fd.append('files', f));
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch(`${BASE_URL}/fit/upload`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: fd,
    }).then(async r => {
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(data.detail ?? `HTTP ${r.status}`), { status: r.status });
      return data;
    });
  },
  courseFromTrack:    (id, trackId) => { _cacheDelPrefix("GET", `/coaching/goals/${id}/race-plan`); return post(`/coaching/goals/${id}/race-plan/course/from-track/${trackId}`); },
  getFuelProducts:    ()      => get("/fuel/products", { noCache: true }),
  createFuelProduct:  (d)     => post("/fuel/products", d),
  updateFuelProduct:  (id, d) => put(`/fuel/products/${id}`, d),
  deleteFuelProduct:  (id)    => del(`/fuel/products/${id}`),
  getGutLogs:         ()      => get("/fuel/gut-logs", { noCache: true }),
  createGutLog:       (d)     => post("/fuel/gut-logs", d),
  getFuelPlan:        (goalId) => get(`/fuel/goals/${goalId}/plan`, { noCache: true }),
  deleteCourseGpx:    (id) => { _cacheDelPrefix("GET", `/coaching/goals/${id}/race-plan`); return del(`/coaching/goals/${id}/race-plan/course`); },

  // Garmin sync
  triggerGarminSync:       ()          => post("/training-plan/sync/trigger"),
  getSyncStatus:           ()          => get("/training-plan/sync/status"),

  // Training plans
  generatePlan:            (goalId, opts = {}) => {
    _cacheDel("GET", `/coaching/goals/${goalId}/plan`);
    _cacheDelPrefix("GET", "/coaching/workouts/upcoming");
    return post(`/coaching/goals/${goalId}/plan/generate${opts.scheduleTests ? "?schedule_tests=true" : ""}`);
  },
  getPlan:                 (goalId)    => get(`/coaching/goals/${goalId}/plan`),
  getUpcomingWorkouts:     (days = 14) => get(`/coaching/workouts/upcoming?days=${days}`),
  updateWorkout:           (id, d)     => { _cacheDelPrefix("GET", "/coaching/workouts/upcoming"); return patch(`/coaching/plan/workouts/${id}`, d); },
  deleteWorkout:           (id)        => { _cacheDelPrefix("GET", "/coaching/workouts/upcoming"); return del(`/coaching/plan/workouts/${id}`); },
  // User-level permanent ICS subscription
  getUserIcsToken:         ()          => get("/coaching/ics-token"),
  regenerateUserIcsToken:  ()          => del("/coaching/ics-token"),
  // Per-goal ICS (legacy)
  getIcsToken:             (goalId)    => get(`/coaching/goals/${goalId}/ics-token`),
  regenerateIcsToken:      (goalId)    => del(`/coaching/goals/${goalId}/ics-token`),

  // Strength training
  getExercises:            ()          => get("/strength/exercises"),
  // Log a completed session (guided runner / manual builder). Feeds the
  // progression loop; when `planned_workout_id` is set, completes that plan
  // workout. Invalidate everything a fresh session changes.
  logWorkoutSession:       (d)         => {
    _cacheDelPrefix("GET", "/coaching/workouts/upcoming");
    _cacheDel("GET", "/strength/progress");
    _cacheDel("GET", "/strength/exercises");
    _cacheDelPrefix("GET", "/strength/history");
    return post("/workouts/sessions", d);
  },
  getStrengthProgress:     ()          => get("/strength/progress"),
  getStrengthHistory:      (days = 90) => get(`/strength/history?days=${days}`),
  overrideOneRM:           (name, d)   => { _cacheDel("GET", "/strength/exercises"); _cacheDel("GET", "/strength/progress"); return put(`/strength/exercises/${encodeURIComponent(name)}/1rm`, d); },
  getWeeklySummary:        ()          => get("/strength/weekly-summary"),
  getEquipment:            ()          => get("/strength/equipment"),
  updateEquipment:         (d)         => { _cacheDel("GET", "/strength/equipment"); return put("/strength/equipment", d); },

  // Exercise preferences
  setExercisePreference:   (name, pref) => {
    _cacheDel("GET", "/strength/exercises");
    return put(`/strength/preferences/${encodeURIComponent(name)}`, { preference: pref });
  },
  deleteExercisePreference: (name) => {
    _cacheDel("GET", "/strength/exercises");
    return del(`/strength/preferences/${encodeURIComponent(name)}`);
  },

  // Custom exercises
  getCustomExercises:      ()    => get("/strength/custom-exercises"),
  createCustomExercise:    (d)   => { _cacheDel("GET", "/strength/exercises"); _cacheDel("GET", "/strength/custom-exercises"); return post("/strength/custom-exercises", d); },
  updateCustomExercise:    (id, d) => { _cacheDel("GET", "/strength/exercises"); _cacheDel("GET", "/strength/custom-exercises"); return put(`/strength/custom-exercises/${id}`, d); },
  deleteCustomExercise:    (id) => { _cacheDel("GET", "/strength/exercises"); _cacheDel("GET", "/strength/custom-exercises"); return del(`/strength/custom-exercises/${id}`); },

  // Flexibility training
  getStretches:            ()          => get("/flexibility/stretches"),
  getFlexibilityProgress:  ()          => get("/flexibility/progress"),
  getFlexibilityHistory:   (days = 90) => get(`/flexibility/history?days=${days}`),

  // Stretch preferences
  setStretchPreference:    (name, pref) => { _cacheDel("GET", "/flexibility/stretches"); return put(`/flexibility/preferences/${encodeURIComponent(name)}`, { preference: pref }); },
  deleteStretchPreference: (name) => { _cacheDel("GET", "/flexibility/stretches"); return del(`/flexibility/preferences/${encodeURIComponent(name)}`); },

  // Custom stretches
  getCustomStretches:      ()    => get("/flexibility/custom-stretches"),
  createCustomStretch:     (d)   => { _cacheDel("GET", "/flexibility/stretches"); _cacheDel("GET", "/flexibility/custom-stretches"); return post("/flexibility/custom-stretches", d); },
  updateCustomStretch:     (id, d) => { _cacheDel("GET", "/flexibility/stretches"); _cacheDel("GET", "/flexibility/custom-stretches"); return put(`/flexibility/custom-stretches/${id}`, d); },
  deleteCustomStretch:     (id) => { _cacheDel("GET", "/flexibility/stretches"); _cacheDel("GET", "/flexibility/custom-stretches"); return del(`/flexibility/custom-stretches/${id}`); },

  // Flows builder
  getFlows:                ()          => get("/flexibility/flows"),
  getFlow:                 (id)        => get(`/flexibility/flows/${id}`),
  createFlow:              (d)         => { _cacheDel("GET", "/flexibility/flows"); return post("/flexibility/flows", d); },
  updateFlow:              (id, d)     => { _cacheDel("GET", "/flexibility/flows"); return put(`/flexibility/flows/${id}`, d); },
  deleteFlow:              (id)        => { _cacheDel("GET", "/flexibility/flows"); return del(`/flexibility/flows/${id}`); },

  // Garmin animation manifest — used by custom-create UIs to scope category
  // pickers and live-check whether a chosen (cat, subtype) will animate.
  getGarminAnimations:     ({ category, app } = {}) => {
    const q = qs({ category, app });
    return get(`/garmin/animations${q}`);
  },
  checkGarminAnimation:    (category, subtype) =>
    get(`/garmin/animations/check?category=${encodeURIComponent(category)}&subtype=${subtype}`),

  // Devices + primary device selection
  getGarminDevices:        () => get("/garmin/devices"),
  setPrimaryDevice:        (deviceId) => {
    // Bust caches that surface animation_state — primary device flips it.
    _cacheDel("GET", "/strength/exercises");
    _cacheDel("GET", "/flexibility/stretches");
    _cacheDel("GET", "/garmin/devices");
    return put("/garmin/devices/primary", { device_id: deviceId });
  },

  // Animation confirmations: thumbs-up / thumbs-down on each exercise
  upsertAnimationConfirmation: (category, subtype, animates) => {
    _cacheDel("GET", "/strength/exercises");
    _cacheDel("GET", "/flexibility/stretches");
    return post("/garmin/confirmations", {
      garmin_category: category, garmin_subtype: subtype, animates,
    });
  },
  deleteAnimationConfirmation: (category, subtype) => {
    _cacheDel("GET", "/strength/exercises");
    _cacheDel("GET", "/flexibility/stretches");
    return del(`/garmin/confirmations?garmin_category=${encodeURIComponent(category)}&garmin_subtype=${subtype}`);
  },

  // Post-workout recap — bulk yes/no/idk verdicts on synced workouts.
  // After /recap submits or /recap/dismiss, the workout is removed from the
  // pending list and any verdicts cascade through strength/stretch state.
  getPendingRecaps: () => get("/garmin/recap/pending", { noCache: true }),
  getRecap:         (workoutId) => get(`/garmin/recap/${workoutId}`, { noCache: true }),
  submitRecap:      (workoutId, items) => {
    _cacheDel("GET", "/strength/exercises");
    _cacheDel("GET", "/flexibility/stretches");
    return post(`/garmin/recap/${workoutId}`, { items });
  },
  dismissRecap:     (workoutId) => post(`/garmin/recap/${workoutId}/dismiss`, {}),

  // Workouts builder
  getWorkouts:             ()          => get("/workouts"),
  getWorkout:              (id)        => get(`/workouts/${id}`),
  createWorkout:           (d)         => { _cacheDel("GET", "/workouts"); return post("/workouts", d); },
  updateWorkout:           (id, d)     => { _cacheDel("GET", "/workouts"); return put(`/workouts/${id}`, d); },
  deleteWorkout:           (id)        => { _cacheDel("GET", "/workouts"); return del(`/workouts/${id}`); },
  // Progressive overload
  getProgression:          (d)         => post("/workouts/progression", d),
  getWorkoutSessions:      (p = {})    => get(`/workouts/sessions${qs(p)}`),

  // Music library. Uploads are normalised server-side with ffmpeg into a form
  // the watch will actually index — see backend/app/services/music_transcode.py.
  // Nothing here is cached: the library changes from this page and a stale list
  // would show tracks that are no longer there.
  getMusicTracks:         ()          => get("/music/tracks", { noCache: true }),
  patchMusicTrack:        (id, d)     => patch(`/music/tracks/${id}`, d),
  deleteMusicTrack:       (id)        => del(`/music/tracks/${id}`),
  setMusicLoad:           (ids, load) => post("/music/tracks/load", { ids, load }),
  getMusicPlaylists:      ()          => get("/music/playlists", { noCache: true }),
  createMusicPlaylist:    (d)         => post("/music/playlists", d),
  patchMusicPlaylist:     (id, d)     => patch(`/music/playlists/${id}`, d),
  setMusicPlaylistTracks: (id, ids)   => put(`/music/playlists/${id}/tracks`, { track_ids: ids }),
  deleteMusicPlaylist:    (id)        => del(`/music/playlists/${id}`),
  getMusicDevicePlan:     ()          => get("/music/device-plan", { noCache: true }),
  // Music server (Subsonic API — Navidrome, Gonic, Airsonic…). Remote tracks
  // are references: no audio moves until something asks for it.
  getMusicServer:         ()          => get("/music/server", { noCache: true }),
  setMusicServer:         (d)         => put("/music/server", d),
  clearMusicServer:       ()          => del("/music/server"),
  setMusicServerOptions:  (d)         => patch("/music/server", d),
  getRemotePlaylists:     ()          => get("/music/server/playlists", { noCache: true }),
  importRemotePlaylist:   (id, load)  => post(`/music/server/playlists/${id}/import?load_to_device=${load ? "true" : "false"}`, {}),
  rotateMusic:            (limit)     => post("/music/server/rotate", limit ? { limit } : {}),

  uploadMusicFiles:       (files, loadToDevice = false) => {
    const fd = new FormData();
    files.forEach(f => fd.append("files", f));
    fd.append("load_to_device", String(loadToDevice));
    const token = localStorage.getItem(TOKEN_KEY);
    return fetch(`${BASE_URL}/music/tracks`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: fd,
    }).then(async r => {
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(data.detail ?? `HTTP ${r.status}`), { status: r.status });
      return data;
    });
  },
};
