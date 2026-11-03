// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Add course" / "Replace course": the one button the Course section shows,
// with every way of getting a course behind it — a GPX file, drawing one on
// the map, or one of the user's saved tracks. The phone's race plan does the
// same with a bottom sheet (CourseSheet in RacePlansScreen.kt); the three used
// to sit side by side on both, which crowded the section once a course was
// already loaded and the choice was rarely made twice.
//
// A triathlon's course comes whole, by leg, or both (organisers publish
// either; race_predictor/triathlon.py), so for one the menu first asks what
// the course is for. A leg's own row opens it fixed to that leg (`leg`).
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../api/client";
import AnchoredPopover from "../ui/AnchoredPopover";
import Tabs from "../ui/Tabs";

export const TRI_TARGETS = [
  { key: "", label: "Whole course" }, { key: "swim", label: "Swim" },
  { key: "bike", label: "Bike" }, { key: "run", label: "Run" },
];

/**
 * What the file picker offers: GPX only. The extension is what browsers
 * filter on reliably; the MIME type is there for the ones that use it.
 * The server checks the extension again (upload_course_gpx).
 */
export const GPX_ACCEPT = ".gpx,application/gpx+xml";

export default function CourseMenu({ goalId, hasCourse, uploading, onUpload, onPicked,
  triathlon = false, leg, label, small = false }) {
  const [open, setOpen] = useState(false);
  const [target, setTarget] = useState("");
  const forLeg = leg ?? (triathlon ? target : "") ?? "";
  const [tracks, setTracks] = useState(null);
  const [busy, setBusy] = useState(false);
  const anchor = useRef(null);
  const file = useRef(null);

  useEffect(() => {
    api.getCourses().then(setTracks).catch(() => setTracks([]));
  }, []);

  async function pick(id) {
    setBusy(true);
    try {
      onPicked(await api.courseFromTrack(goalId, id, forLeg || undefined));
      setOpen(false);
    } finally {
      setBusy(false);
    }
  }

  const row = "w-full text-left px-3 py-2 rounded-lg text-sm hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50";
  return (
    <>
      <button ref={anchor} onClick={() => setOpen(!open)} disabled={uploading}
        className={`btn btn-tonal${small ? " btn-sm" : ""}`} aria-haspopup="menu" aria-expanded={open}>
        {uploading ? "Uploading…" : label ?? (hasCourse ? "Replace course" : "Add course")}
      </button>
      <input ref={file} type="file" accept={GPX_ACCEPT} className="hidden" data-testid="gpx-input"
        onChange={(e) => { setOpen(false); onUpload(e, forLeg || undefined); }} />
      <AnchoredPopover anchorRef={anchor} open={open} onClose={() => setOpen(false)} align="left">
        <div role="menu" className="w-72 p-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg text-slate-800 dark:text-slate-100">
          {triathlon && leg == null && (
            <Tabs size="sm" stretch label="Course for" tabs={TRI_TARGETS} value={target} onChange={setTarget}
              className="mb-2" />
          )}
          <button role="menuitem" className={row} onClick={() => file.current?.click()}>Upload GPX file</button>
          <Link role="menuitem" to={`/maps?raceGoal=${goalId}${forLeg ? `&leg=${forLeg}` : ""}`}
            className={`block ${row}`}>Draw on map</Link>
          {tracks?.length > 0 && (
            <>
              <p className="px-3 pt-3 pb-1 field-label">Saved courses</p>
              <div className="max-h-64 overflow-y-auto">
                {tracks.map((t) => (
                  <button key={t.id} role="menuitem" className={row} disabled={busy} onClick={() => pick(t.id)}>
                    {t.name || `Track ${t.id}`}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      </AnchoredPopover>
    </>
  );
}
