// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect } from "react";
import { api } from "../../../api/client";

// Owns the map-data "setup" status that feeds the single download/error toast.
// Two independent sources are folded together here:
//   • Global downloads (basemap overview + DEM) — polled from the backend every
//     4s while the page is mounted. Fully self-contained (no map, own state).
//   • Region downloads/merges — passed in from useRegionDownload's `regions`
//     list (this hook only buckets them; it does not own that data).
// Returns the pre-bucketed arrays the toast renders plus `showToast`, so the
// derived-filter noise stays out of the component body.
export function useDownloadStatus(regions) {
  const [globalDownloads, setGlobalDownloads] = useState([]);

  // Poll global download status (basemap overview + DEM).
  useEffect(() => {
    let active = true;
    let timeout;
    async function poll() {
      if (!active) return;
      try {
        const data = await api.getGlobalDownloads();
        if (active) setGlobalDownloads(data.downloads || []);
      } catch {}
      if (active) timeout = setTimeout(poll, 4000);
    }
    poll();
    return () => { active = false; clearTimeout(timeout); };
  }, []);

  const regionDownloading = regions.filter(
    (r) => r.status === "downloading" || r.status === "downloading_dem" || r.status === "queued"
  );
  const regionMerging = regions.filter((r) => r.status === "merging");
  const globalActive  = globalDownloads.filter((d) => d.status === "downloading");
  const globalErrored = globalDownloads.filter((d) => d.status === "error");

  const showToast =
    regionDownloading.length > 0 || regionMerging.length > 0 ||
    globalActive.length > 0 || globalErrored.length > 0;

  return { regionDownloading, regionMerging, globalActive, globalErrored, showToast };
}
