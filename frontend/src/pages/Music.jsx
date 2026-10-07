// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The music library, and choosing what of it rides on the watch.
//
// Sending is a separate button from the sidebar's "Sync device over USB" on purpose:
// everything that sync moves is kilobytes and can ride along every time, while
// a music push is megabytes per track and can run for minutes.
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import WatchSyncModal from "../components/sync/WatchSyncModal";
import MusicServerPanel from "./MusicServerPanel";
import WatchAppPanel from "./WatchAppPanel";
import { Section } from "../components/ui/Section";
import { PlusIcon } from "../components/ui/Button";

const FILE_LIMIT = 500;

function formatMb(bytes) {
  if (!bytes) return "0 MB";
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDuration(seconds) {
  if (!seconds) return "—";
  const total = Math.round(seconds);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

function Callout({ tone = "amber", children }) {
  return <div className={tone === "amber" ? "alert-warn" : "alert-error"}>{children}</div>;
}

function TrackRow({ track, onToggle, onDelete }) {
  return (
    <div className="flex items-center gap-3 px-3.5 py-2.5 border-b border-slate-100 dark:border-slate-800 last:border-0">
      <input
        type="checkbox"
        checked={track.load_to_device}
        onChange={(e) => onToggle(track.id, e.target.checked)}
        className="shrink-0"
        aria-label={`Carry ${track.title} on the watch`}
      />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium text-slate-800 dark:text-slate-100">{track.title}</span>
          {track.on_watch && (
            <span className="badge shrink-0 bg-accent-50 text-accent-700 dark:bg-accent-900/20 dark:text-accent-400">
              On watch
            </span>
          )}
        </div>
        <p className="truncate text-xs text-slate-400 dark:text-slate-500">
          {[track.artist, track.album].filter(Boolean).join(" · ") || "Unknown artist"}
        </p>
      </div>
      <span className="shrink-0 font-mono text-xs text-slate-400">{formatDuration(track.duration_s)}</span>
      <span className="hidden shrink-0 font-mono text-xs text-slate-400 sm:inline">{formatMb(track.size_bytes)}</span>
      <button
        type="button"
        onClick={() => onDelete(track)}
        className="icon-btn icon-btn-sm icon-btn-danger shrink-0"
        aria-label={`Delete ${track.title}`}
      >
        ×
      </button>
    </div>
  );
}

export default function Music() {
  const [tracks, setTracks] = useState(null);
  const [playlists, setPlaylists] = useState([]);
  const [plan, setPlan] = useState(null);
  const [error, setError] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [uploadNote, setUploadNote] = useState(null);
  const [syncOpen, setSyncOpen] = useState(false);
  const fileRef = useRef(null);

  const refresh = useCallback(async () => {
    try {
      const [t, p, d] = await Promise.all([
        api.getMusicTracks(),
        api.getMusicPlaylists(),
        api.getMusicDevicePlan(),
      ]);
      setTracks(t.tracks);
      setPlaylists(p.playlists);
      setPlan(d);
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const upload = async (files) => {
    if (!files?.length) return;
    setUploading(true);
    setUploadNote(null);
    try {
      const res = await api.uploadMusicFiles([...files]);
      const parts = [];
      if (res.added.length) parts.push(`${res.added.length} added`);
      if (res.duplicates.length) parts.push(`${res.duplicates.length} already in the library`);
      if (res.failed.length) parts.push(`${res.failed.length} could not be read`);
      setUploadNote({
        text: parts.join(", ") || "Nothing to add",
        failures: res.failed,
      });
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setUploading(false);
    }
  };

  const toggleTrack = async (id, load) => {
    // Optimistic: the checkbox should not lag behind the click.
    setTracks((prev) => prev.map((t) => (t.id === id ? { ...t, load_to_device: load } : t)));
    try {
      await api.setMusicLoad([id], load);
      setPlan(await api.getMusicDevicePlan());
    } catch (e) {
      setError(e.message);
      refresh();
    }
  };

  const deleteTrack = async (track) => {
    if (!confirm(`Delete "${track.title}" from your library?`)) return;
    try {
      await api.deleteMusicTrack(track.id);
      await refresh();
    } catch (e) {
      setError(e.message);
    }
  };

  const addPlaylist = async () => {
    const name = prompt("Playlist name");
    if (!name) return;
    try {
      await api.createMusicPlaylist({ name });
      await refresh();
    } catch (e) {
      setError(e.message);
    }
  };

  const togglePlaylist = async (pl, load) => {
    try {
      await api.patchMusicPlaylist(pl.id, { load_to_device: load });
      await refresh();
    } catch (e) {
      setError(e.message);
    }
  };

  const carried = tracks?.filter((t) => t.load_to_device) ?? [];
  const carriedBytes = carried.reduce((n, t) => n + (t.size_bytes || 0), 0);
  const projected = plan?.on_device_after ?? 0;
  const overLimit = projected > FILE_LIMIT;
  const nothingToSend =
    !plan || (plan.add.length === 0 && plan.remove.length === 0 && plan.playlists.length === 0);

  return (
    <div className="p-5 max-w-4xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">Music</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">
            {tracks === null
              ? "Loading…"
              : `${tracks.length} track${tracks.length !== 1 ? "s" : ""} · ${carried.length} carried (${formatMb(carriedBytes)})`}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            disabled={uploading}
            data-tour="music-add"
            className="btn btn-tonal"
          >
            {uploading ? "Adding…" : "Add music"}
          </button>
          <button
            type="button"
            onClick={() => setSyncOpen(true)}
            disabled={nothingToSend || overLimit}
            data-tour="music-send"
            className="btn btn-primary"
          >
            Send to watch
          </button>
        </div>
      </div>

      <input
        ref={fileRef}
        type="file"
        accept="audio/*,.mp3,.m4a,.flac,.wav,.ogg,.aac"
        multiple
        className="hidden"
        onChange={(e) => { upload(e.target.files); e.target.value = ""; }}
      />

      {error && <Callout tone="red">{error}</Callout>}

      {overLimit && (
        <Callout>
          That would put {projected} files on the watch, over Garmin's{" "}
          {FILE_LIMIT}-file limit. Uncheck some tracks before sending — going
          over does not fail, the extra files simply never appear.
        </Callout>
      )}

      {carried.length > 0 && (
        <Callout>
          After sending, set <strong>Music Providers → My Music</strong> on the
          watch. Tracks are copied to the device but stay hidden until you do —
          this is the usual reason a push looks like it did nothing.
        </Callout>
      )}

      {uploadNote && (
        <div className="card text-sm text-slate-600 dark:text-slate-300">
          <p>{uploadNote.text}</p>
          {uploadNote.failures.map((f, i) => (
            <p key={i} className="text-xs text-red-500">{f.filename}: {f.error}</p>
          ))}
        </div>
      )}

      <div data-tour="music-server">
        <MusicServerPanel onLibraryChanged={refresh} />
      </div>

      <div data-tour="music-watchapp">
        <WatchAppPanel />
      </div>

      {/* Playlists */}
      <Section
        title="Playlists"
        dataTour="music-playlists"
        action={<button type="button" onClick={addPlaylist} className="btn btn-tonal btn-sm"><PlusIcon />New</button>}
      >
        <div className="card p-0">
          {playlists.length === 0 ? (
            <p className="px-3.5 py-3 text-sm text-slate-400 dark:text-slate-500">
              No playlists yet. A playlist is written to the watch as an .m3u, and
              carrying one carries its tracks too.
            </p>
          ) : (
            playlists.map((pl) => (
              <div key={pl.id} className="flex items-center gap-3 border-b border-slate-100 px-3.5 py-2.5 last:border-0 dark:border-slate-800">
                <input
                  type="checkbox"
                  checked={pl.load_to_device}
                  onChange={(e) => togglePlaylist(pl, e.target.checked)}
                  aria-label={`Carry ${pl.name} on the watch`}
                />
                <span className="flex-1 truncate text-sm font-medium text-slate-800 dark:text-slate-100">{pl.name}</span>
                <span className="text-xs text-slate-400">
                  {pl.track_ids.length} track{pl.track_ids.length !== 1 ? "s" : ""}
                </span>
              </div>
            ))
          )}
        </div>
      </Section>

      {/* Tracks */}
      <Section
        title="Tracks"
        dataTour="music-tracks"
        action={plan && (
          <span className="text-xs text-slate-400 dark:text-slate-500">
              {projected} of {FILE_LIMIT} files on watch after sending
            </span>
          )}
        >
        <div className="card p-0">
          {tracks === null ? (
            <p className="px-3.5 py-3 text-sm text-slate-400">Loading…</p>
          ) : tracks.length === 0 ? (
            <p className="px-3.5 py-3 text-sm text-slate-400 dark:text-slate-500">
              Nothing here yet. Add mp3, m4a, flac or wav files — anything that is
              not already a watch-friendly mp3 is converted on upload.
            </p>
          ) : (
            tracks.map((t) => (
              <TrackRow key={t.id} track={t} onToggle={toggleTrack} onDelete={deleteTrack} />
            ))
          )}
        </div>
      </Section>

      <WatchSyncModal
        open={syncOpen}
        mode="music"
        onClose={() => setSyncOpen(false)}
        onSynced={refresh}
      />
    </div>
  );
}
