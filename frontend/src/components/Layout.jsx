// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// App shell / chrome. Wraps every authenticated route: the primary navigation
// (the NAV table below), the account menu, global indicators (e.g. import
// progress), and an <Outlet> where the routed page renders. This is the frame
// that stays put while pages swap underneath it.
import { Outlet, NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useState, useEffect } from "react";
import WatchSyncModal from "./sync/WatchSyncModal";
import SidebarStatus, { useServerActivity } from "./sync/SidebarStatus";
import ServerUpdateBanner from "./ServerUpdateBanner";
import BackupProgressPill from "./BackupProgressPill";
import { GARMIN_VENDOR_ID } from "../lib/mtp";
import { isWindows } from "../lib/deviceSync";
import { TourProvider } from "./tour/TourContext";
import TourTooltip from "./tour/TourTooltip";
import { useTourAutoStart } from "./tour/useTourAutoStart";
import { useMedicationReminders } from "./medication/useNotifications";

// Mounted inside TourProvider (and the router): fires a section's first-visit
// tour and renders the floating tip card. Split out so it can use the tour
// context that Layout provides.
function TourRuntime() {
  useTourAutoStart();
  return <TourTooltip />;
}

// Medication reminders on every page, not just Health — see useNotifications.
function MedicationReminders() {
  useMedicationReminders();
  return null;
}

// Health sits second, next to the dashboard: the two are the pair opened
// daily. The phone's sidebar (mobile Destinations.kt) uses this same order.
const NAV = [
  {
    to: "/", label: "Dashboard", end: true,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
      </svg>
    ),
  },
  {
    to: "/health", label: "Health", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
      </svg>
    ),
  },
  {
    to: "/activities", label: "Activities", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
      </svg>
    ),
  },
  {
    to: "/goals", label: "Training", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
      </svg>
    ),
  },
  {
    to: "/training", label: "Strength", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M3 6h2m14 0h2M5 6a2 2 0 012-2h10a2 2 0 012 2v2H5V6zM5 8v10a2 2 0 002 2h10a2 2 0 002-2V8M9 12h6" />
      </svg>
    ),
  },
  {
    to: "/flexibility", label: "Flexibility", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m0 0l-4-4m4 4l4-4" />
        <circle cx="12" cy="12" r="9" fill="none" />
      </svg>
    ),
  },
  {
    to: "/race-plans", label: "Race Plans", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z" />
        <line strokeLinecap="round" x1="4" y1="22" x2="4" y2="15" />
      </svg>
    ),
  },
  {
    to: "/maps", label: "Maps", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498l4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 00-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0z" />
      </svg>
    ),
  },
  {
    to: "/music", label: "Music", end: false,
    icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l10-2v13" />
        <circle cx="6" cy="19" r="3" />
        <circle cx="16" cy="17" r="3" />
      </svg>
    ),
  },
];

const linkClass = ({ isActive }) =>
  `flex items-center gap-3 px-2.5 py-1.5 rounded-lg text-sm font-medium mb-0.5 transition-colors ${
    isActive
      ? "bg-accent-50 dark:bg-accent-900/30 text-accent-700 dark:text-accent-400"
      : "text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white"
  }`;

export default function Layout() {
  const { logout } = useAuth();
  // What the server is doing for this user — see sync/SidebarStatus.jsx.
  const status = useServerActivity();
  // One-button watch sync modal; autoDevice is set when a previously
  // authorised watch is plugged into this computer while the app is open.
  const [syncModalOpen, setSyncModalOpen] = useState(false);
  const [autoDevice, setAutoDevice] = useState(null);

  // Pop the sync prompt when an authorised Garmin device appears. The watch
  // enumerates twice (generic GPS device first, real MTP device ~10 s later);
  // only the MTP identity is ever authorised, so this fires once, when it's
  // ready. Windows can't claim MTP devices from the browser — skip there.
  useEffect(() => {
    if (typeof navigator === "undefined" || !navigator.usb || isWindows()) return;
    const onConnect = (e) => {
      if (e.device.vendorId === GARMIN_VENDOR_ID) {
        setAutoDevice(e.device);
        setSyncModalOpen(true);
      }
    };
    navigator.usb.addEventListener("connect", onConnect);
    return () => navigator.usb.removeEventListener("connect", onConnect);
  }, []);

  return (
    <TourProvider>
    <div className="flex h-screen bg-slate-100 dark:bg-slate-950">
      {/* Sidebar */}
      <nav data-tour="nav" className="w-52 shrink-0 flex flex-col bg-white dark:bg-slate-900 border-r border-slate-200 dark:border-slate-800">
        <div className="h-14 flex items-center px-4 border-b border-slate-200 dark:border-slate-800">
          <span className="font-bold text-slate-900 dark:text-white text-lg tracking-tight">Tracks</span>
        </div>

        <div className="flex-1 py-2.5 px-2.5 overflow-y-auto">
          {NAV.map(({ to, label, end, icon }) => (
            <NavLink key={to} to={to} end={end} className={linkClass}>
              {icon}
              {label}
            </NavLink>
          ))}
        </div>

        <div className="p-1 space-y-0.5 border-t border-slate-200 dark:border-slate-800">
          <SidebarStatus status={status} onUnlock={logout} />

          {/* Device sync over a cable: the watch's USB connection (WebUSB or
              the garmin-sync agent). Named for the cable because the phone
              syncs with the watch over Bluetooth by itself, and "Sync watch"
              read as though this did that too. */}
          <button
            type="button"
            data-tour="sync"
            onClick={() => { setAutoDevice(null); setSyncModalOpen(true); }}
            className="w-full flex items-center gap-3 px-2.5 py-1.5 mb-1 rounded-lg text-sm font-medium text-left whitespace-nowrap transition-colors text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 3v12m0 0l-3-3m3 3l3-3M8 21h8M10 17h4v4h-4z" />
            </svg>
            Sync device over USB
          </button>

          <NavLink to="/settings" data-tour="settings-link" className={linkClass}>
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            Settings
          </NavLink>
          <button
            onClick={logout}
            className="w-full flex items-center gap-3 px-2.5 py-1.5 rounded-lg text-sm font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
            Log out
          </button>
        </div>
      </nav>

      {/* Page content */}
      <main className="flex-1 overflow-y-auto bg-slate-100 dark:bg-slate-950">
        <ServerUpdateBanner />
        <Outlet />
      </main>

      <WatchSyncModal
        open={syncModalOpen}
        autoDevice={autoDevice}
        onClose={() => { setSyncModalOpen(false); setAutoDevice(null); }}
      />

      <TourRuntime />
      <MedicationReminders />
      <BackupProgressPill />
    </div>
    </TourProvider>
  );
}
