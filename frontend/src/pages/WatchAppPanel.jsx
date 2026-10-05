// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The on-watch Connect IQ music app, explained.
//
// There is nothing to *do* here from a browser: the phone app installs the
// watch app over Bluetooth and hands it the music server's address and login,
// and from then on the watch talks to that server by itself. This panel exists
// so the second library on the watch is explained next to the first one, and
// so the music server's role in it is stated where the server is configured.
import { api } from "../api/client";
import { useEffect, useState } from "react";

export default function WatchAppPanel() {
  const [server, setServer] = useState(null);

  useEffect(() => {
    api.getMusicServer().then(setServer).catch(() => setServer({ configured: false }));
  }, []);

  return (
    <div className="rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <div className="border-b border-slate-100 px-3.5 py-2.5 dark:border-slate-800">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">Watch app (no cable)</h2>
        <p className="text-xs text-slate-400 dark:text-slate-500">
          Tracks Music on the watch downloads playlists straight from your
          Navidrome server over the watch's own Wi-Fi — on the charger, with no
          phone and no computer — and plays them in its own player, not under
          "My Music". A watch using both paths has two libraries.
        </p>
      </div>

      <div className="space-y-2 px-3.5 py-3 text-sm text-slate-600 dark:text-slate-300">
        <ol className="list-decimal space-y-1 pl-5">
          <li>Connect your music server above. The watch signs in to it directly, so the address must be reachable from the watch's Wi-Fi with a real (CA-signed) certificate.</li>
          <li>In the Tracks phone app, open <span className="font-medium">Music › Send music app to watch</span>. It goes over Bluetooth; no Connect IQ Store.</li>
          <li>On the watch: <span className="font-medium">Music › Music Providers › Tracks Music</span>, tick playlists, sync. "Liked songs" and "Recently played" follow what you listen to.</li>
        </ol>
        {server && !server.configured && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            No music server is connected yet — that is the one thing the watch app needs.
          </p>
        )}
        <p className="text-xs text-slate-400 dark:text-slate-500">
          The watch re-syncs its chosen playlists whenever it is charging on Wi-Fi.
          Navidrome only, for the watch app: its own API is what lets the watch
          read a playlist in pieces small enough to hold.
        </p>
      </div>
    </div>
  );
}
