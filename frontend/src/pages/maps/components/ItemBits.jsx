// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The small pieces the library, the editor and the detail panels share — the
// phone's CollectionDropdown, ColorRow, IconRow and Marker (TrackLibrarySheet.kt,
// MapChrome.kt). Two surfaces offering different versions of the same control
// is how a person learns to distrust both.
import { useState } from "react";
import ColorPicker from "../../../components/ColorPicker";
import { DEFAULT_COLLECTION, PLACE_ICONS, TRACK_COLORS, naturalCompare } from "../utils/mapItems";
import { placeDataUrl } from "../utils/placeIcons";

/** The theme's ink, for the outline a mark carries off the map. */
const inkHalo = () => (document.documentElement.classList.contains("dark") ? "#e2e8f0" : "#0f172a");

/**
 * A place or track as it appears on the map: a place wears its own symbol in
 * its own colour — so finding the row for the mark you are looking at is
 * matching, not reading — and a track, which has no symbol, is a dot of its
 * line's colour.
 */
export function ItemMark({ kind, color, icon, atlas, size = 26 }) {
  if (kind === "track") {
    return (
      <span className="shrink-0 grid place-items-center" style={{ width: size, height: size }}>
        <span className="rounded-full ring-2 ring-white/80 dark:ring-slate-900/80 shadow"
              style={{ width: size * 0.55, height: size * 0.55, backgroundColor: color }} />
      </span>
    );
  }
  return (
    <span className="shrink-0 grid place-items-center" style={{ width: size, height: size }}>
      <img alt="" draggable={false} src={placeDataUrl(atlas, icon, color, inkHalo())}
           style={{ maxWidth: size, maxHeight: size }} />
    </span>
  );
}

/**
 * Which collection an item is in: "Tracks", every collection, and a new one
 * (named, then the item filed in it). The label sits above the box.
 */
export function CollectionPicker({ value, folders, onChange, onCreate, className = "" }) {
  const [naming, setNaming] = useState(null);   // "" while typing | null
  const sorted = [...folders].sort((a, b) => naturalCompare(a.name, b.name));
  const create = async () => {
    const name = (naming || "").trim();
    if (!name) return;
    setNaming(null);
    const f = await onCreate?.(name);
    if (f?.id != null) onChange(f.id);
  };
  return (
    <label className={`block min-w-[8rem] ${className}`}>
      <span className="field-label">Collection</span>
      {naming == null ? (
        <select
          className="field field-sm"
          value={value ?? ""}
          onChange={(e) => {
            if (e.target.value === "__new") { setNaming(""); return; }
            onChange(e.target.value ? Number(e.target.value) : null);
          }}
        >
          <option value="">{DEFAULT_COLLECTION}</option>
          {sorted.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          {onCreate && <option value="__new">+ New collection…</option>}
        </select>
      ) : (
        <span className="flex items-center gap-1">
          <input
            autoFocus className="field field-sm" placeholder="Collection name" value={naming}
            onChange={(e) => setNaming(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); create(); } if (e.key === "Escape") setNaming(null); }}
          />
          <button type="button" className="btn btn-primary btn-sm" disabled={!naming.trim()} onClick={create}>Add</button>
          <button type="button" className="icon-btn icon-btn-sm" title="Cancel" onClick={() => setNaming(null)}>×</button>
        </span>
      )}
    </label>
  );
}

/** Colours in one row of dots, with the spectrum at the end for anything else. */
export function ColorRow({ value, onChange }) {
  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      {TRACK_COLORS.map((c) => (
        <button
          key={c} type="button" title={c} onClick={() => onChange(c)}
          className={`w-5 h-5 rounded-full ring-1 transition-transform ${
            value?.toLowerCase() === c ? "ring-2 ring-offset-1 ring-accent-500 scale-110" : "ring-black/10"}`}
          style={{ backgroundColor: c }}
        />
      ))}
      <ColorPicker value={value || TRACK_COLORS[0]} onChange={onChange} size="w-5 h-5" />
    </div>
  );
}

/** The symbols a place can take, each drawn in the place's colour — the choice looks like what it makes. */
export function SymbolPicker({ value, color, atlas, onChange }) {
  return (
    <div className="flex flex-wrap gap-1">
      {PLACE_ICONS.map((icon) => (
        <button
          key={icon} type="button" onClick={() => onChange(icon)}
          title={icon === "marker" ? "Pin" : icon.replace(/_/g, " ")}
          className={`w-8 h-8 rounded-lg grid place-items-center transition-colors ${
            value === icon ? "bg-accent-100 dark:bg-accent-900/40 ring-1 ring-accent-400"
              : "hover:bg-slate-100 dark:hover:bg-slate-800"}`}
        >
          <ItemMark kind="place" icon={icon} color={color} atlas={atlas} size={22} />
        </button>
      ))}
    </div>
  );
}
