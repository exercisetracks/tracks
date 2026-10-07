// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Version: this server, the newest release, and every phone signed in to this
// account with the version it runs — the phone's Version card, from the
// server's side.
//
// Tracks is not in an app store, so this is how a household learns an update
// exists, and, as important, that a phone and its server have drifted apart:
// they share one sync protocol. The web app itself is served by the server,
// so it is always the server's version and gets no row of its own.
//
// The newest release comes from GET /version, which asks GitHub on the
// server (cached for hours; UPDATE_CHECK=false turns it off). Each phone's
// version is what it last reported (its X-Tracks-Client header, recorded on
// sync); a phone that has not synced since updating shows its old version.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { isNewer, versionOf } from "../../lib/versions";
import { Section } from "./primitives";
import UpdateCommand from "./UpdateCommand";

function Status({ tone, children }) {
  const tones = {
    ok:   "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300",
    new:  "bg-accent-50 text-accent-700 dark:bg-accent-900/20 dark:text-accent-400",
    warn: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300",
  };
  return <span className={`badge ${tones[tone]}`}>{children}</span>;
}

function Row({ label, sub, version, status }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="min-w-0">
        <div className="truncate text-sm font-medium text-slate-800 dark:text-slate-100">{label}</div>
        {sub && <div className="truncate text-xs text-slate-400 dark:text-slate-500">{sub}</div>}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {status}
        <span className="font-mono text-sm text-slate-700 dark:text-slate-200">{version ?? "—"}</span>
      </div>
    </div>
  );
}

// A phone's standing against its server first, then against the newest
// release: a phone ahead of or behind its own server is the thing to fix.
export function deviceStatus(app, server, latest) {
  if (!app) return null;
  if (isNewer(server, app)) return { tone: "warn", text: "Behind server" };
  if (isNewer(app, server)) return { tone: "warn", text: "Ahead of server" };
  if (isNewer(latest, app)) return { tone: "new", text: `${latest} available` };
  return { tone: "ok", text: "Up to date" };
}

function latestNote(status) {
  if (!status) return null;
  if (!status.update_check) return "Not checked: UPDATE_CHECK is off on this server";
  if (status.check_error) return "Could not reach GitHub";
  return status.latest?.published_at ? `Published ${status.latest.published_at.slice(0, 10)}` : null;
}

export default function VersionSection() {
  const { user } = useAuth();
  const [status, setStatus] = useState(null);
  const [phones, setPhones] = useState([]);

  useEffect(() => {
    api.getVersionStatus().then(setStatus).catch(() => setStatus(null));
    api.getSessions()
      .then(rows => setPhones((rows ?? []).filter(r => r.client_version)))
      .catch(() => setPhones([]));
  }, []);

  const server = status?.server_version;
  const latest = status?.latest?.version;

  return (
    <Section title="Version">
      <Row
        label="Server"
        sub="Also this web app"
        version={server}
        status={status && (status.server_update_available
          ? <Status tone="new">{latest} available</Status>
          : latest && <Status tone="ok">Up to date</Status>)}
      />
      <Row
        label="Latest release"
        sub={latestNote(status)}
        version={latest}
        status={status?.latest && (
          <a href={status.latest.url} target="_blank" rel="noreferrer"
            className="text-xs font-medium text-accent-600 hover:underline dark:text-accent-400">
            Release notes
          </a>
        )}
      />

      {phones.length > 0 && (
        <div className="space-y-3 border-t border-slate-100 pt-4 dark:border-slate-800">
          <div className="field-label">Your phones</div>
          {phones.map(p => {
            const app = versionOf(p.client_version);
            const s = deviceStatus(app, server, latest);
            return (
              <Row
                key={p.id}
                label={p.device_label || "Phone"}
                sub={p.last_used_at ? `Last seen ${new Date(p.last_used_at).toLocaleDateString()}` : null}
                version={app}
                status={s && <Status tone={s.tone}>{s.text}</Status>}
              />
            );
          })}
          <p className="text-xs text-slate-400 dark:text-slate-500">
            As each phone last reported on sync. Get the app from the{" "}
            <a href={status?.releases_url ?? "https://github.com/exercisetracks/tracks/releases"}
              target="_blank" rel="noreferrer" className="text-accent-600 hover:underline dark:text-accent-400">
              releases page
            </a>{" "}
            — the APK matching the server's version.
          </p>
        </div>
      )}

      {user?.is_admin && status?.server_update_available && (
        <div className="border-t border-slate-100 pt-4 dark:border-slate-800">
          <UpdateCommand />
        </div>
      )}
    </Section>
  );
}
