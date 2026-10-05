// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * ActivityTable - Generic table component for displaying activity logs
 * 
 * Table styling uses high contrast colors:
 * - Headers: slate-600 (light mode) / slate-300 (dark mode)
 * - Data cells: slate-900 (light mode) / slate-100 (dark mode)
 * - Empty state: slate-500 (light mode) / slate-400 (dark mode)
 */
export function ActivityTable({
  columns,
  data,
  emptyMessage = "No data available",
  onRowClick,
  striped = true,
}) {
  // High contrast header styling
  const th =
    "px-2.5 py-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider text-right first:text-left whitespace-nowrap";
  // High contrast data cell styling
  const td =
    "px-2.5 py-1.5 text-sm tabular-nums text-slate-900 dark:text-slate-100 text-right first:text-left whitespace-nowrap";

  if (!data?.length) {
    return (
      <div className="text-center py-3.5 text-sm text-slate-500 dark:text-slate-400">
        {emptyMessage}
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 dark:border-slate-700">
            {columns.map((col, i) => (
              <th key={i} className={th}>
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody
          className={`divide-y divide-slate-200 dark:divide-slate-700 ${
            striped ? "divide-slate-100 dark:divide-slate-800/60" : ""
          }`}
        >
          {data.map((row, rowIndex) => (
            <tr
              key={row.id ?? rowIndex}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={
                onRowClick
                  ? "cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50"
                  : "hover:bg-slate-50 dark:hover:bg-slate-800/50"
              }
            >
              {columns.map((col, colIndex) => {
                const value = col.key ? row[col.key] : null;
                const displayValue = col.formatter
                  ? col.formatter(value, row)
                  : value ?? "—";
                return (
                  <td key={colIndex} className={`${td} ${col.className || ""}`}>
                    {displayValue}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * CompactTable - Simplified table for quick data display
 * 
 * Uses high contrast colors matching ActivityTable
 */
export function CompactTable({ headers, rows, showHeader = true }) {
  // High contrast header styling
  const th =
    "px-2.5 py-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider text-right first:text-left";
  // High contrast data cell styling
  const td =
    "px-2.5 py-1.5 text-sm tabular-nums text-slate-900 dark:text-slate-100 text-right first:text-left";

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        {showHeader && (
          <thead>
            <tr className="border-b border-slate-200 dark:border-slate-700">
              {headers.map((h, i) => (
                <th key={i} className={th}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
        )}
        <tbody className="divide-y divide-slate-200 dark:divide-slate-700">
          {rows.map((row, i) => (
            <tr key={i} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
              {row.map((cell, j) => (
                <td key={j} className={td}>
                  {cell ?? "—"}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}