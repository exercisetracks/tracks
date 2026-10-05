// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react';
import { api } from '../api/client';

/**
 * Custom hook to fetch all activity-related data
 * Handles loading state, error handling, and parallel data fetching
 * 
 * @param {string} activityId - The activity ID to fetch data for
 * @returns {Object} Contains activity, track, laps, sets, climbs, loading, error
 */
export function useActivityData(activityId) {
  const [state, setState] = useState({
    activity: null,
    track: [],
    laps: [],
    sets: [],
    climbs: [],
    golfHoles: [],
    settings: null,
    loading: true,
    error: null
  });

  useEffect(() => {
    if (!activityId) {
      setState(prev => ({ ...prev, loading: false, error: 'No activity ID provided' }));
      return;
    }

    let cancelled = false;

    const fetchData = async () => {
      setState(prev => ({ ...prev, loading: true, error: null }));
      try {
        // Fetch everything except golf holes
        const [
          activityRes,
          trackRes,
          lapsRes,
          setsRes,
          climbsRes,
          settingsRes,
        ] = await Promise.allSettled([
          api.getActivity(activityId),
          api.getTrack(activityId),
          api.getLaps(activityId).catch(() => []),
          api.getSets(activityId).catch(() => []),
          api.getClimbs(activityId).catch(() => []),
          api.getSettings().catch(() => null),
        ]);

        const activity = activityRes.status === 'fulfilled' ? activityRes.value : null;

        // Conditionally fetch golf holes only for golf activities
        let golfHoles = [];
        if (activity && (activity.sport || '').toLowerCase() === 'golf') {
          try {
            golfHoles = await api.getGolfHoles(activityId);
          } catch {
            golfHoles = [];
          }
        }

        if (cancelled) return;

        // Safe elapsed-time calculation — only keep points with parseable timestamps
        let track = trackRes.status === 'fulfilled' ? trackRes.value : [];
        const firstValid = track.find(p => p.recorded_at && Number.isFinite(new Date(p.recorded_at).getTime()));
        if (firstValid) {
          const t0 = new Date(firstValid.recorded_at).getTime();
          track = track
            .filter(p => p.recorded_at && Number.isFinite(new Date(p.recorded_at).getTime()))
            .map(p => ({
              ...p,
              elapsed: Math.round((new Date(p.recorded_at).getTime() - t0) / 1000),
            }));
        } else {
          track = [];
        }

        setState({
          activity,
          track,
          laps: lapsRes.status === 'fulfilled' ? lapsRes.value : [],
          sets: setsRes.status === 'fulfilled' ? setsRes.value : [],
          climbs: climbsRes.status === 'fulfilled' ? climbsRes.value : [],
          golfHoles,
          settings: settingsRes.status === 'fulfilled' ? settingsRes.value : null,
          loading: false,
          error: activityRes.status === 'rejected' ? activityRes.error : null,
        });
      } catch (e) {
        if (!cancelled) {
          setState(prev => ({ ...prev, loading: false, error: e }));
        }
      }
    };

    fetchData();

    return () => {
      cancelled = true;
    };
  }, [activityId]);

  return state;
}

export default useActivityData;