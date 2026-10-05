// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Browser-notification engine for due medication doses. Runs only while the tab
// is open: every 30s it fires a Notification for any due-but-unlogged dose whose
// scheduled time just passed (0–2 min ago) and that opted into reminders.
// Clicking the notification invokes `onLogged(med)`. Each dose is notified at
// most once (tracked by schedule_id + day). No effect if notifications aren't
// supported or permission is denied.

import { useEffect, useRef } from "react";

export function useNotifications(dueMeds, onLogged) {
  const notifiedRef = useRef(new Set());
  const permissionRef = useRef(null);

  useEffect(() => {
    if (!("Notification" in window)) return;
    permissionRef.current = Notification.permission;
    if (Notification.permission === "default") {
      Notification.requestPermission().then(p => { permissionRef.current = p; });
    }
  }, []);

  useEffect(() => {
    if (!dueMeds?.length) return;
    const interval = setInterval(() => {
      const now = new Date();
      dueMeds.forEach(med => {
        if (med.status || !med.notify) return;
        const key = `${med.schedule_id}-${new Date(med.scheduled_for).toDateString()}`;
        if (notifiedRef.current.has(key)) return;
        const diff = (now - new Date(med.scheduled_for)) / 60000;
        if (diff >= 0 && diff < 2) {
          notifiedRef.current.add(key);
          if (permissionRef.current === "granted") {
            const n = new Notification(`Time to take ${med.medication_name}`, {
              body: med.dose ? `${med.dose} ${med.dose_unit ?? ""}`.trim() : "Tap to log",
              tag:  key,
            });
            n.onclick = () => { n.close(); onLogged?.(med); };
          }
        }
      });
    }, 30000);
    return () => clearInterval(interval);
  }, [dueMeds, onLogged]);
}
