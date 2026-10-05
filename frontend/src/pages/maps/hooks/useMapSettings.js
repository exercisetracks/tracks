// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect } from "react";
import { api } from "../../../api/client";

// Loads the user settings once on mount. MapView needs two things from these:
// `map_enabled` (gates the whole page) and `units` (imperial/metric for the
// panels). `settingsLoaded` flips true on either success OR failure so the page
// can stop showing the loading state and fall through to the map even if the
// settings request errored. Self-contained: no map dependency, one fetch, one
// cancel guard on unmount.
export function useMapSettings() {
  const [settings, setSettings] = useState(null);
  const [settingsLoaded, setSettingsLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.getSettings()
      .then((s) => { if (!cancelled) { setSettings(s); setSettingsLoaded(true); } })
      .catch(() => setSettingsLoaded(true));
    return () => { cancelled = true; };
  }, []);

  return { settings, settingsLoaded };
}
