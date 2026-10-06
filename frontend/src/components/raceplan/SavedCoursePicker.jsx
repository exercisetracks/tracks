// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Choose saved course": pick one of the user's map tracks as the race course.
// The server turns it into segments exactly as it would an uploaded GPX.
import { useEffect, useState } from "react";
import { api } from "../../api/client";

export default function SavedCoursePicker({ goalId, onPicked }) {
  const [tracks, setTracks] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.getCourses().then(setTracks).catch(() => setTracks([]));
  }, []);

  if (!tracks || tracks.length === 0) return null;

  async function pick(e) {
    const id = e.target.value;
    if (!id) return;
    setBusy(true);
    try {
      onPicked(await api.courseFromTrack(goalId, id));
    } finally {
      setBusy(false);
      e.target.value = "";
    }
  }

  return (
    <select
      aria-label="Choose saved course" disabled={busy} onChange={pick} defaultValue=""
      className="field w-auto"
    >
      <option value="">{busy ? "Loading…" : "Choose saved course"}</option>
      {tracks.map((t) => (
        <option key={t.id} value={t.id}>{t.name || `Track ${t.id}`}</option>
      ))}
    </select>
  );
}
