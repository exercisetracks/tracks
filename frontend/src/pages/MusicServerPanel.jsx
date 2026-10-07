// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Connecting a self-hosted music server, and letting it decide what the watch
// carries. Split out of Music.jsx so that page stays about the library itself.
import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { Section } from "../components/ui/Section";

function Field({ label, ...props }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-slate-500 dark:text-slate-400">{label}</span>
      <input
        className="field"
        {...props}
      />
    </label>
  );
}

export default function MusicServerPanel({ onLibraryChanged }) {
  const [server, setServer] = useState(null);
  const [url, setUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [playlists, setPlaylists] = useState(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState(null);
  const [error, setError] = useState(null);
  // Where the typed address led: null before anything is typed, "probing",
  // { url } once a music server answered there, or "none".
  const [found, setFound] = useState(null);
  const probeSeq = useRef(0);

  const refresh = async () => {
    try {
      const s = await api.getMusicServer();
      setServer(s);
      setUrl(s.url || "");
      // A saved server is one that was reached, so its login fields show.
      if (s.url) setFound({ url: s.url });
      setUsername(s.username || "");
      if (s.configured) {
        api.getRemotePlaylists().then((r) => setPlaylists(r.playlists)).catch(() => setPlaylists([]));
      }
    } catch (e) {
      setError(e.message);
    }
  };

  useEffect(() => { refresh(); }, []);

  const run = async (fn, success) => {
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const result = await fn();
      setNote(typeof success === "function" ? success(result) : success);
      await refresh();
      onLibraryChanged?.();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  // The address is checked as it is typed, and the login fields appear only
  // once a music server has answered at it. The server fills in what people
  // leave out — `10.0.0.5` becomes http://10.0.0.5:4533 if that is where
  // Navidrome is (services/subsonic.discover) — and the form shows the address
  // it settled on, so what is saved is what was found rather than a guess.
  const probe = (text) => {
    const seq = ++probeSeq.current;
    if (!text.trim()) { setFound(null); return; }
    setFound("probing");
    api.probeMusicServer(text)
      .then((r) => {
        if (seq !== probeSeq.current) return;
        setFound({ url: r.url });
        // Into the field itself, so the scheme and port it was missing are
        // visible and editable rather than applied behind the user's back.
        setUrl(r.url);
      })
      .catch(() => { if (seq === probeSeq.current) setFound("none"); });
  };

  useEffect(() => {
    if (server === null) return;
    if (found?.url && found.url === url.trim().replace(/\/+$/, "")) return;
    // Edited away from the address that answered: hide the login until the
    // new one does too.
    probeSeq.current++;
    setFound(url.trim() ? "probing" : null);
    const t = setTimeout(() => probe(url), 600);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, server === null]);

  const reached = found && typeof found === "object";
  const target = reached ? found.url : url;

  const connect = () =>
    run(
      () => api.setMusicServer({ url: target, username, ...(password ? { password } : {}) }),
      (r) => `Connected to ${r.server || "the music server"}`,
    );

  const rotate = () =>
    run(
      () => api.rotateMusic(),
      (r) => `Now carrying ${r.carried} tracks (${r.added} added, ${r.dropped} dropped)`,
    );

  if (server === null) return null;

  return (
    <Section title="Music server">
      <div className="card p-0">
        <div className="border-b border-slate-100 px-3.5 py-2.5 dark:border-slate-800">
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Navidrome, Gonic, Airsonic — anything speaking the Subsonic API. Tracks
            keeps references, not copies: audio is fetched only when a watch needs it.
          </p>
        </div>

        <div className="space-y-3 px-3.5 py-3">
          <div className="grid gap-2 sm:grid-cols-3">
            <div>
              <Field label="Server address" value={url} onChange={(e) => setUrl(e.target.value)}
                     placeholder="10.0.0.5 or music.example.com" />
              <p className="mt-1 text-xs text-slate-400 dark:text-slate-500" aria-live="polite">
                {found === "probing" && "Looking for a music server…"}
                {found === "none" && "No music server answered there."}
              </p>
            </div>
            {reached && (
              <>
                <Field label="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
                <Field label={server.configured ? "Password (leave blank to keep)" : "Password"}
                       type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button" onClick={connect} disabled={busy || !reached || !username}
              className="btn btn-primary"
            >
              {server.configured ? "Update" : "Connect"}
            </button>
            {server.configured && (
              <>
                <button
                  type="button" onClick={rotate} disabled={busy}
                  className="btn btn-tonal"
                >
                  Refresh from listening history
                </button>
                <button
                  type="button"
                  onClick={() => run(() => api.clearMusicServer(), "Disconnected")}
                  disabled={busy}
                  className="btn btn-danger"
                >
                  Disconnect
                </button>
              </>
            )}
          </div>

          {server.configured && (
            <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
              <input
                type="checkbox"
                checked={server.auto_rotate}
                onChange={(e) => run(() => api.setMusicServerOptions({ auto_rotate: e.target.checked }), null)}
              />
              Keep the watch's music fresh automatically
              <span className="text-xs text-slate-400">
                (top {server.rotate_count} by plays; never unloads tracks you picked yourself)
              </span>
            </label>
          )}

          {note && <p className="text-sm text-accent-700 dark:text-accent-400">{note}</p>}
          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

          {server.configured && playlists !== null && (
            <div className="rounded-lg border border-slate-200 dark:border-slate-800">
              <p className="border-b border-slate-100 px-2.5 py-1.5 text-xs font-medium text-slate-500 dark:border-slate-800 dark:text-slate-400">
                Playlists on the server
              </p>
              {playlists.length === 0 ? (
                <p className="px-2.5 py-2 text-sm text-slate-400">None found.</p>
              ) : (
                playlists.map((pl) => (
                  <div key={pl.id} className="flex items-center gap-2 border-b border-slate-100 px-2.5 py-1.5 last:border-0 dark:border-slate-800">
                    <span className="flex-1 truncate text-sm text-slate-700 dark:text-slate-200">{pl.name}</span>
                    <span className="text-xs text-slate-400">{pl.song_count}</span>
                    <button
                      type="button"
                      onClick={() => run(
                        () => api.importRemotePlaylist(pl.id, true),
                        (r) => `Imported ${r.tracks} tracks from ${r.name}`,
                      )}
                      disabled={busy}
                      className="btn btn-tonal btn-sm"
                    >
                      Import
                    </button>
                  </div>
                ))
              )}
            </div>
          )}
        </div>
      </div>
    </Section>
  );
}
