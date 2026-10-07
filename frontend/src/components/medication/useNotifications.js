// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Browser notifications for due medication doses, app-wide.
//
// Mounted once in the app shell (Layout), not in the Medications section where
// it used to live: there it only ran while the Health page was open, so a
// reminder needed you to be looking at your medications already. It still
// needs a Tracks tab open somewhere — a page cannot wake itself, and true push
// (a service worker and a push service) is a separate decision this does not
// make. The phone app's alarms are the reminder that works with nothing open;
// see mobile/.../meds/MedicationReminders.kt.
//
// What is due comes from /medications/due, whose times are the account's wall
// clock (backend api/medications.py), polled each minute so a tab left open
// overnight sees the next day's doses.
//
// A click opens the Health page rather than logging the dose. It used to mark
// the dose taken, which turned "show me" into a medical record.

import { useCallback, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/client";

const POLL_MS = 60_000;

/**
 * How late a reminder may still be shown. A laptop asleep at eight still
 * hears about the eight o'clock dose when it wakes at half past; one that
 * wakes at noon does not get a stale alarm for breakfast.
 */
export const LATE_WINDOW_MIN = 60;

const STORE_KEY = "tracks_med_reminders_shown";

/** The doses that should be announced now: due, unlogged, opted in, not yet shown. */
export function dosesToAnnounce(due, now, shown) {
  return (due ?? []).filter((d) => {
    if (d.status || !d.notify) return false;
    if (shown.has(reminderKey(d))) return false;
    const late = (now - new Date(d.scheduled_for)) / 60_000;
    return late >= 0 && late < LATE_WINDOW_MIN;
  });
}

/** One dose on one day — so a reload, or a second tab, does not announce it again. */
export function reminderKey(d) {
  return `${d.schedule_id}@${d.scheduled_for}`;
}

function loadShown() {
  try {
    return new Set(JSON.parse(localStorage.getItem(STORE_KEY) ?? "[]"));
  } catch {
    return new Set();
  }
}

function saveShown(shown, due) {
  // Only today's keys are worth keeping; older ones can never match again.
  const live = new Set((due ?? []).map(reminderKey));
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify([...shown].filter((k) => live.has(k))));
  } catch { /* private mode: the in-memory set still stops repeats in this tab */ }
}

export function useMedicationReminders() {
  const navigate = useNavigate();
  const shown = useRef(loadShown());

  const check = useCallback(async () => {
    if (!("Notification" in window) || Notification.permission !== "granted") return;
    let due;
    try { due = await api.getDueMedications(); } catch { return; }
    for (const d of dosesToAnnounce(due, new Date(), shown.current)) {
      const key = reminderKey(d);
      shown.current.add(key);
      const n = new Notification(`Time to take ${d.medication_name}`, {
        body: d.dose ? `${d.dose} ${d.dose_unit ?? ""}`.trim() : "Open Tracks to log it",
        tag: key,
        requireInteraction: true,
      });
      n.onclick = () => { window.focus(); navigate("/health"); n.close(); };
    }
    saveShown(shown.current, due);
  }, [navigate]);

  useEffect(() => {
    check();
    const t = setInterval(check, POLL_MS);
    // A tab coming back from the background, or a laptop from sleep, checks at
    // once rather than up to a minute later.
    const onVisible = () => { if (document.visibilityState === "visible") check(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { clearInterval(t); document.removeEventListener("visibilitychange", onVisible); };
  }, [check]);
}
