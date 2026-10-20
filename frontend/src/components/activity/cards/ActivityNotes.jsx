// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useEffect, useState } from "react";
import { api } from "../../../api/client";
import Modal from "../../ui/Modal";
import Button from "../../ui/Button";

/*
 * An activity's notes: the first few words beside a pencil at the top of the
 * activity, and the whole note in a dialog behind it.
 *
 * The same control as the phone's app bar (ActivityDetailScreen.kt), and the
 * same field: `notes` is part of the activity's synced row (spec/sync.yaml),
 * so a note written here is on the phone after its next sync and the other
 * way round. noteSnippet matches the phone's to the word.
 */

const SNIPPET_WORDS = 4;

/** The note's first words, or null for no note — the pencil then stands alone. */
export function noteSnippet(notes, words = SNIPPET_WORDS) {
  const text = (notes ?? "").trim();
  if (!text) return null;
  // First line only: a note that opens with a short line has said its
  // headline there. Whole words, so the cut never lands mid-word.
  const firstLine = text.split("\n")[0].trim();
  const all = firstLine.split(/\s+/);
  const cut = all.slice(0, words).join(" ");
  return all.length > words || firstLine.length < text.length ? `${cut}…` : cut;
}

function PencilIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.12 2.12 0 013 3L7 19l-4 1 1-4 12.5-12.5z" />
    </svg>
  );
}

export function ActivityNotes({ activity }) {
  const [notes, setNotes] = useState(activity?.notes ?? "");
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  // Another activity in the same view (navigating between them) brings its own note.
  useEffect(() => { setNotes(activity?.notes ?? ""); }, [activity?.id, activity?.notes]);

  const snippet = noteSnippet(notes);
  const edit = () => { setDraft(notes); setError(null); setOpen(true); };

  const save = async () => {
    const next = draft.trim();
    // Unchanged is not written: an edit stamps the field, and a needless one
    // would let this copy beat a note written on the phone since.
    if (next === notes.trim()) { setOpen(false); return; }
    setSaving(true);
    try {
      // An empty string, not null — the API reads null as "leave it alone".
      // Every client treats a blank note as no note.
      await api.patchActivity(activity.id, { notes: next });
      setNotes(next);
      setOpen(false);
    } catch {
      setError("Could not save the note. Try again.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      {snippet && (
        <button
          type="button"
          onClick={edit}
          title={notes}
          className="max-w-[14rem] truncate text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 transition-colors"
        >
          {snippet}
        </button>
      )}
      <button type="button" onClick={edit} className="icon-btn" aria-label="Notes" title="Notes" data-tour="activity-notes">
        <PencilIcon />
      </button>
      {open && (
        <Modal title="Notes" onClose={saving ? undefined : () => setOpen(false)}>
          <textarea
            autoFocus
            rows={6}
            value={draft}
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) save(); }}
            placeholder="How it went, the conditions, the kit…"
            aria-label="Notes"
            className="field w-full resize-y"
          />
          {error && <p className="alert-error">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={() => setOpen(false)} disabled={saving}>Cancel</Button>
            <Button variant="primary" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save"}</Button>
          </div>
        </Modal>
      )}
    </>
  );
}

export default ActivityNotes;
