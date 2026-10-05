// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ============================================================
// ACTIVITIES LIST PAGE
// ============================================================
// This page displays a searchable, filterable, sortable list of
// all recorded activities. It's the main entry point for viewing
// activity history.
//
// Features:
// - Search by activity name
// - Filter by sport type
// - Filter by date range
// - Sort by any column (date, distance, duration, etc.)
// - Pagination (20 activities per page)
// - All state is stored in URL params for bookmarking/back button
//
// STRUCTURE / WHY THIS FILE STAYS "MEDIUM":
// The tightly-coupled bits — URL-param state, the fetch effects,
// and the filter/sort/pagination handlers that mutate those
// params — all live inline here on purpose. They share the same
// searchParams state and pulling them into child components would
// only create prop-drilling. The genuinely separable, low-coupling
// pieces have been extracted into ./components/activities/:
//   - format.js     → pure formatters
//   - SortHeader.jsx → stateless sortable column header
//   - ActivityRow.jsx → the (visually heavy) per-activity <tr>
// ============================================================

import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api/client";
import SortHeader from "../components/activities/SortHeader";
import ActivityRow from "../components/activities/ActivityRow";

// Number of activities per page.
const PAGE_SIZE = 20;

// ============================================================
// MAIN ACTIVITIES COMPONENT
// ============================================================
/**
 * Activities - Main activities list page
 *
 * State Management:
 * - All filter/sort state lives in URL search params
 * - This enables: bookmarking, sharing links, browser back button
 * - Local state is only used for the search input (debounced)
 *
 * Data Flow:
 * 1. URL params change (filter, sort, page)
 * 2. useEffect triggers API call with params
 * 3. Results stored in local state
 * 4. Table re-renders with new data
 */
export default function Activities() {
  const navigate       = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // ============================================================
  // SECTION 1: URL PARAM STATE
  // ============================================================
  // All filters and sort options come from URL params
  // This makes the state shareable and bookmarkable
  // ============================================================
  const sport  = searchParams.get("sport")  || "";  // Filter by sport type
  const after  = searchParams.get("after")  || "";  // Date range start
  const before = searchParams.get("before") || "";  // Date range end
  const sort   = searchParams.get("sort")   || "date";  // Sort column
  const order  = searchParams.get("order")  || "desc";  // Sort direction
  const page   = parseInt(searchParams.get("page") || "1", 10);  // Current page
  const urlSearch = searchParams.get("q")   || "";  // Search query

  // ============================================================
  // SECTION 2: LOCAL COMPONENT STATE
  // ============================================================
  // Search input has local state for debouncing
  // Data state holds API results
  // ============================================================
  const [searchInput, setSearchInput] = useState(urlSearch);
  const [activities,  setActivities]  = useState([]);
  const [total,       setTotal]       = useState(0);
  const [loading,     setLoading]     = useState(true);
  const [sports,      setSports]      = useState([]);
  const [settings,    setSettings]    = useState(null);

  const imperial = settings?.units === "imperial";

  // ============================================================
  // SECTION 3: URL PARAM HELPER FUNCTIONS
  // ============================================================
  // These functions update URL params and reset pagination.
  // They stay inline because they all read/write the shared
  // searchParams state that drives this whole page.
  // ============================================================

  /**
   * Set a URL parameter, resetting page to 1
   * Used for filters and search
   */
  function setParam(key, value) {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      next.delete("page");
      return next;
    });
  }

  /**
   * Set page number parameter
   * Keeps other params intact
   */
  function setPageParam(p) {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      if (p > 1) next.set("page", String(p)); else next.delete("page");
      return next;
    });
  }

  /**
   * Toggle sort column or direction
   * Clicking same column reverses direction
   * Clicking new column sets to descending
   */
  function toggleSort(col) {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      const prevSort = prev.get("sort") || "date";
      if (prevSort === col) {
        const prevOrder = prev.get("order") || "desc";
        next.set("order", prevOrder === "asc" ? "desc" : "asc");
      } else {
        next.set("sort", col);
        next.set("order", "desc");
      }
      next.delete("page");
      return next;
    });
  }

  /**
   * Clear all filters and search
   * Resets to default view (all activities, sorted by date desc)
   */
  function clearFilters() {
    setSearchInput("");
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      ["q", "sport", "after", "before", "page"].forEach(k => next.delete(k));
      return next;
    });
  }

  // ============================================================
  // SECTION 4: EFFECT HOOKS
  // ============================================================
  // These run automatically when their dependencies change
  // ============================================================

  // Debounce search input → URL param (350ms delay)
  // Prevents API calls on every keystroke
  useEffect(() => {
    const t = setTimeout(() => setParam("q", searchInput), 350);
    return () => clearTimeout(t);
  }, [searchInput]);

  // Load settings and available sports on mount
  useEffect(() => {
    let cancelled = false;
    api.getSettings().then(d => { if (!cancelled) setSettings(d); }).catch(() => {});
    api.getSports().then(d => { if (!cancelled) setSports(d); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  // Fetch activities when any filter/sort/page changes
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const params = { page, page_size: PAGE_SIZE, sort, order };
    if (urlSearch) params.search = urlSearch;
    if (sport)  params.sport  = sport;
    if (after)  params.after  = after;
    if (before) params.before = before;
    api.getActivities(params)
      .then(d => { if (!cancelled) { setActivities(d.items); setTotal(d.total); } })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [page, urlSearch, sport, after, before, sort, order]);

  // ============================================================
  // SECTION 5: DERIVED STATE
  // ============================================================
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const hasFilters = urlSearch || sport || after || before;

  // ============================================================
  // SECTION 6: RENDER THE PAGE
  // ============================================================
  return (
    <div className="p-5 max-w-7xl mx-auto space-y-4">
      {/* Page header with activity count */}
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">Activities</h1>
        <span className="text-sm text-slate-500 dark:text-slate-400">
          {total.toLocaleString()} {total === 1 ? "activity" : "activities"}
        </span>
      </div>

      {/*
        FILTERS SECTION
        - Search input (debounced)
        - Sport dropdown
        - Date range pickers
        - Clear filters button
        - Sort controls
      */}
      <div data-tour="activities-filters" className="flex flex-wrap gap-2 items-center">
        <input
          type="text"
          placeholder="Search by name…"
          value={searchInput}
          onChange={e => setSearchInput(e.target.value)}
          className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500 w-52"
        />
        <select
          value={sport}
          onChange={e => setParam("sport", e.target.value)}
          className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500 cursor-pointer"
        >
          <option value="">All sports</option>
          {sports.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <input type="date" value={after} onChange={e => setParam("after", e.target.value)}
          className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500"
        />
        <span className="text-slate-400 text-xs">–</span>
        <input type="date" value={before} onChange={e => setParam("before", e.target.value)}
          className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500"
        />

        {hasFilters && (
          <button
            onClick={clearFilters}
            className="btn btn-neutral btn-sm"
          >
            Clear filters ×
          </button>
        )}

        {/* Sort control dropdown */}
        <div data-tour="activities-sort" className="flex items-center gap-1 ml-auto">
          <span className="text-xs text-slate-400 dark:text-slate-500 uppercase tracking-wider">Sort</span>
          <select
            value={sort}
            onChange={e => setParam("sort", e.target.value)}
            className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500 cursor-pointer"
          >
            <option value="date">Date</option>
            <option value="sport">Sport</option>
            <option value="duration">Duration</option>
            <option value="distance">Distance</option>
            <option value="speed">Pace / Speed</option>
            <option value="heartrate">Avg HR</option>
            <option value="elevation">Elevation</option>
            <option value="calories">Calories</option>
          </select>
          <button
            type="button"
            onClick={() => setParam("order", order === "asc" ? "desc" : "asc")}
            title={order === "asc" ? "Ascending — click to switch to descending" : "Descending — click to switch to ascending"}
            className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
          >
            {order === "asc" ? "↑" : "↓"}
          </button>
        </div>
      </div>

      {/*
        ACTIVITY TABLE
        - Clickable rows navigate to activity detail
        - Column headers are sortable
        - Shows all key activity metrics
      */}
      <div data-tour="activities-table" className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center h-40 text-sm text-slate-400 dark:text-slate-500">Loading…</div>
        ) : activities.length === 0 ? (
          <div className="flex items-center justify-center h-40 text-sm text-slate-400 dark:text-slate-500">No activities found</div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 dark:border-slate-800 text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                <SortHeader label="Activity"     col="sport"     align="left"  sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Date"         col="date"      align="left"  sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Duration"     col="duration"  align="right" sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Distance"     col="distance"  align="right" sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Pace / Speed" col="speed"     align="right" sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Avg HR"       col="heartrate" align="right" sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Elevation"    col="elevation" align="right" sort={sort} order={order} onClick={toggleSort} />
                <SortHeader label="Calories"     col="calories"  align="right" sort={sort} order={order} onClick={toggleSort} />
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {activities.map(a => (
                <ActivityRow
                  key={a.id}
                  activity={a}
                  imperial={imperial}
                  onOpen={() => navigate(`/activities/${a.id}`)}
                  onPreview={() => navigate(`/maps?previewActivity=${a.id}`)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/*
        PAGINATION CONTROLS
        - Shows current page and total pages
        - Prev/Next buttons (disabled at boundaries)
      */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-slate-500 dark:text-slate-400">
          <span>Page {page} of {totalPages}</span>
          <div className="flex gap-2">
            <button disabled={page === 1} onClick={() => setPageParam(page - 1)}
              className="btn btn-neutral btn-sm">
              ← Prev
            </button>
            <button disabled={page === totalPages} onClick={() => setPageParam(page + 1)}
              className="btn btn-neutral btn-sm">
              Next →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
