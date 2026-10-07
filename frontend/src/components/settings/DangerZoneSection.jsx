// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Danger zone: everything in Settings that cannot be undone, each behind the
// two-step DangerConfirmDialog (a click, then a phrase naming the action).
//
// "Delete my data" was "Clear all user data", which read as every user's on a
// shared server; it only ever touched the signed-in account. The admin rows
// below are the ones that reach further, named for exactly how far: everyone's
// data; everyone's data and every other account; the whole server back to
// first-run setup. And the map tiles, which are the instance's and not
// anybody's data, for a basemap that has gone wrong.
import { useState } from "react";
import { api, TOKEN_KEY } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { Section as SharedSection } from "../ui/Section";
import DangerConfirmDialog from "./DangerConfirmDialog";

const KEPT_ACCOUNT = "Accounts, settings and devices are kept.";

function DangerRow({ title, body, button, onClick }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <p className="text-sm font-medium text-slate-800 dark:text-slate-200">{title}</p>
        <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{body}</p>
      </div>
      <button type="button" onClick={onClick} className="btn btn-danger btn-sm shrink-0">{button}</button>
    </div>
  );
}

function WhatGoes() {
  return (
    <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-1 list-disc list-inside">
      <li>Activities and their GPS and sensor data</li>
      <li>Daily health metrics and the injuries log</li>
      <li>Training goals, plans and race plans</li>
      <li>Coaching history and fitness fingerprints</li>
    </ul>
  );
}

export default function DangerZoneSection() {
  const { user } = useAuth();
  const admin = Boolean(user?.is_admin);
  const [open, setOpen] = useState(null);
  const [note, setNote] = useState("");

  const home = () => { window.location.href = "/"; };

  const actions = {
    mine: {
      title: "Delete my data",
      phrase: "delete my data",
      button: "Delete my data",
      row: `Permanently deletes your activities, health records, training goals and metrics — yours only. Your account and settings are kept.`,
      body: <><p>This permanently deletes, for your account only:</p><WhatGoes /><p>Your account, settings and devices are kept. Other people's accounts are not touched.</p></>,
      run: async () => { await api.clearAllData(); home(); },
    },
    maps: {
      title: "Delete all map data",
      phrase: "delete map data",
      button: "Delete map data",
      row: "Deletes every downloaded basemap tile on this server, for a map that has broken. If map downloads are on, everything is downloaded again.",
      body: <><p>Every basemap, terrain and region tile archive on the server is deleted. Downloaded areas keep their place in the list.</p><p>If map downloads are turned on, the basemap and every area start downloading again straight away — that can be several gigabytes. Fonts, routing data and your own tracks are not touched.</p></>,
      run: async () => {
        const r = await api.resetBasemap();
        setOpen(null);
        setNote(r.redownloading ? "Map data deleted — downloading it again now." : "Map data deleted. Turn map downloads on to fetch it again.");
      },
    },
    everyone: {
      title: "Delete everyone's data",
      phrase: "delete everyone's data",
      button: "Delete everyone's data",
      row: `Every account's activities, health records and plans. ${KEPT_ACCOUNT}`,
      body: <><p>This deletes, for <strong>every account on this server</strong>:</p><WhatGoes /><p>{KEPT_ACCOUNT} Everyone's phones will clear their copies at their next sync.</p></>,
      run: async (phrase) => { await api.adminWipeData(phrase); home(); },
    },
    accounts: {
      title: "Delete all other accounts",
      phrase: "delete all other accounts",
      button: "Delete accounts",
      row: "Every account's data, and every account except yours.",
      body: <><p>Every other account on this server is deleted, with all of its data, and your own data is deleted too.</p><p>Only your account and its settings remain.</p></>,
      run: async (phrase) => { await api.adminWipeAccounts(phrase); home(); },
    },
    factory: {
      title: "Factory reset",
      phrase: "factory reset",
      button: "Factory reset",
      row: "Every account and all data, yours included. The server goes back to first-run setup.",
      body: <><p>Every account on this server — including yours — is deleted with all of its data, and the server returns to first-run setup, where the next person to open it creates the admin.</p><p>Downloaded map tiles are kept.</p></>,
      run: async (phrase) => {
        await api.adminFactoryReset(phrase);
        try { localStorage.removeItem(TOKEN_KEY); } catch { /* blocked storage */ }
        home();
      },
    },
  };

  const shown = admin ? ["mine", "maps", "everyone", "accounts", "factory"] : ["mine"];
  const current = open && actions[open];

  return (
    <>
      {/* The shared section, its title in red and the card edged in red —
          the one place in Settings where the colour is the message. */}
      <SharedSection title={<span className="text-red-600 dark:text-red-400">Danger zone</span>}>
        <div className="card border-red-200 dark:border-red-900 space-y-4">
          {shown.map((k, i) => (
            <div key={k} className={i > 0 ? "pt-4 border-t border-red-100 dark:border-red-900/50" : ""}>
              {k === "maps" && i > 0 && (
                <p className="section-title mb-3">Whole server (admin)</p>
              )}
              <DangerRow title={actions[k].title} body={actions[k].row} button={actions[k].button} onClick={() => { setNote(""); setOpen(k); }} />
            </div>
          ))}
          {note && <p className="text-sm text-accent-700 dark:text-accent-400">{note}</p>}
        </div>
      </SharedSection>

      {current && (
        <DangerConfirmDialog
          key={open}
          title={current.title}
          phrase={current.phrase}
          actionLabel={current.button}
          onConfirm={current.run}
          onClose={() => setOpen(null)}
        >
          {current.body}
        </DangerConfirmDialog>
      )}
    </>
  );
}
