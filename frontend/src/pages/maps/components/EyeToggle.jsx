// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Show/hide eye toggle for a custom track. `hidden` tracks stay in the list but
// are dropped from the map's courses geojson (server-side), so toggling re-fetches.
// Shared by CustomTrackManager rows and the CustomTrackDetail header.
export default function EyeToggle({ hidden, onClick, className = "", size = "w-4 h-4" }) {
  return (
    <button
      onClick={onClick}
      title={hidden ? "Hidden — click to show on map" : "Visible — click to hide"}
      className={`shrink-0 transition-colors ${
        hidden ? "text-slate-300 dark:text-slate-600 hover:text-slate-500"
               : "text-accent-600 dark:text-accent-400 hover:text-accent-700"} ${className}`}
    >
      {hidden ? (
        <svg className={size} fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88" />
        </svg>
      ) : (
        <svg className={size} fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" />
          <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
      )}
    </button>
  );
}
