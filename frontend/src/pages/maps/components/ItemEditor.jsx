// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The editor's panel (state in useItemEditor): everything about the track or
// place being drawn or edited, committed together by one Save — the phone's
// RouteBar. The line or the spot is edited on the map; this holds its name,
// collection, colour, symbol, and the height profile of the line.
//
// Two ways out and no third: Save keeps it, × throws the drawing away. A
// "done" step between them once closed the tool while leaving the draft alive,
// a state with a line on the map and nothing able to save it.
import Switch from "../../../components/ui/Switch";
import ElevationProfileCard from "./ElevationProfileCard";
import { CollectionPicker, ColorRow, SymbolPicker } from "./ItemBits";
import { DEFAULT_PLACE_COLOR, pointAlong, profileOf } from "../utils/mapItems";

const SPORTS = ["hiking", "running", "cycling", "walking"];

export default function ItemEditor({ editor, folders, atlas, imperial, onCreateFolder }) {
  if (!editor.open) return null;
  const { isPlace, kind, handles, stats, line } = editor;

  const km = (v) => (imperial ? `${(v * 0.621371).toFixed(1)} mi` : `${v.toFixed(1)} km`);
  const m = (v) => (imperial ? `${Math.round(v * 3.28084).toLocaleString()} ft` : `${Math.round(v).toLocaleString()} m`);

  const status = editor.error
    ? editor.error
    : editor.routing ? "Following the trails…"
      : !isPlace && stats ? `${km(stats.km)} · ↑${m(stats.gain)} ↓${m(stats.loss)}`
        : kind === "place" ? "Drag the pin, or click the map to move it"
          : handles.length === 0 ? "Click the map to start — one point is a place, two or more a track"
            : isPlace ? "A place — click again to make a track"
              : "Click to extend · drag a point to move it · click one for options";

  const title = kind === "place" ? "Edit place" : kind === "track" ? "Edit track"
    : isPlace ? "New place" : handles.length ? "New track" : "Draw";

  return (
    <div className="absolute bottom-0 left-2 right-2 sm:left-[11rem] sm:right-[5rem] z-30 pointer-events-none">
      <div className="pointer-events-auto w-full max-w-[52rem] mx-auto px-3.5 py-2.5 space-y-2
                      bg-white/90 dark:bg-slate-900/90 backdrop-blur-md
                      border border-b-0 border-slate-200/70 dark:border-slate-700/70 rounded-t-xl
                      shadow-[0_-4px_20px_rgba(0,0,0,0.10)]">
        <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">{title}</div>

        {/* The shape of the climbing, not just its total. */}
        {!isPlace && line?.length > 1 && (
          <ElevationProfileCard
            profile={profileOf(line)} imperial={imperial} grade height={120}
            onScrub={(at) => editor.scrub(at == null ? null : pointAlong(line, at))}
          />
        )}

        <div className="flex items-end gap-2 flex-wrap">
          <label className="block flex-1 min-w-[12rem]">
            <span className="field-label">{isPlace ? "Place name" : "Track name"}</span>
            <input
              className="field field-sm" value={editor.name} placeholder={isPlace ? "Place" : "Track"}
              onChange={(e) => editor.setName(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && editor.canSave) editor.save(); }}
            />
          </label>
          <CollectionPicker value={editor.folderId} folders={folders} onChange={editor.setFolder} onCreate={onCreateFolder} />
          {!isPlace && kind !== "track" && (
            <label className="block">
              <span className="field-label">Sport</span>
              <select className="field field-sm" value={editor.sport} onChange={(e) => editor.setSport(e.target.value)}>
                {SPORTS.map((s) => <option key={s} value={s}>{s[0].toUpperCase() + s.slice(1)}</option>)}
              </select>
            </label>
          )}
        </div>

        <ColorRow value={editor.color || (isPlace ? DEFAULT_PLACE_COLOR : null)} onChange={editor.setColor} />

        {isPlace && (
          <SymbolPicker value={editor.icon} color={editor.color || DEFAULT_PLACE_COLOR} atlas={atlas} onChange={editor.setIcon} />
        )}

        {/* Refine a recorded or imported line: fewer points, same shape, previewed as it moves. */}
        {!isPlace && editor.originalLine && (
          <div className="flex items-center gap-3 text-[11px] text-slate-600 dark:text-slate-300">
            <span className="w-40 shrink-0">
              {editor.simplify === 0 ? "Simplify" : `Simplify ${editor.simplify}% · ${editor.baseLine?.length ?? 0} pts`}
            </span>
            <input
              type="range" min="0" max="100" step="1" value={editor.simplify}
              onChange={(e) => editor.setSimplify(Number(e.target.value))}
              className="flex-1 accent-accent-600"
            />
          </div>
        )}

        <div className="flex items-center gap-2">
          {editor.menu != null && handles[editor.menu] ? (
            <span className="flex-1 flex items-center gap-2 text-xs text-slate-600 dark:text-slate-300">
              Point {editor.menu + 1}
              <button type="button" className="btn btn-danger btn-sm" onClick={() => editor.deleteHandle(editor.menu)}>Delete point</button>
              <button type="button" className="icon-btn icon-btn-sm" title="Close" onClick={() => editor.setMenu(null)}>×</button>
            </span>
          ) : (
            <span className={`flex-1 min-w-0 truncate text-xs ${editor.error ? "text-rose-600 dark:text-rose-400" : "text-slate-600 dark:text-slate-300"}`}>
              {status}
            </span>
          )}
          {kind !== "place" && handles.length > 0 && (
            <button type="button" className="btn btn-neutral btn-sm" onClick={editor.undo} title="Remove the last point">Undo</button>
          )}
          {!isPlace && (
            <label className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-300" title="Route between points along trails and roads">
              Snap
              <Switch small checked={editor.snap} onChange={editor.setSnap} label="Snap to trails" />
            </label>
          )}
          <button type="button" className="icon-btn" title={kind === "new" ? "Discard this drawing" : "Discard changes"} onClick={editor.close}>×</button>
          <button type="button" className="btn btn-primary btn-sm" disabled={!editor.canSave} onClick={editor.save}>
            {editor.saving ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
