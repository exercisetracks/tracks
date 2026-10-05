// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Unit conversion and formatting utilities
 * Handles both metric and imperial units
 */

// Distance conversions
export function mToFt(m) { return m * 3.28084; }
export function mToKm(m) { return m / 1000; }
export function mToMi(m) { return m / 1609.34; }

// Speed conversions
export function mpsToKph(mps) { return mps * 3.6; }
export function mpsToMph(mps) { return mps * 2.23694; }

// Pace calculations
export function fmtPaceMin(mps) {
  if (!mps || mps <= 0) return '—';
  const minPerKm = 1000 / (mps * 60);
  const min = Math.floor(minPerKm);
  const sec = Math.round((minPerKm - min) * 60);
  return `${min}:${sec.toString().padStart(2, '0')}`;
}

export function fmtPaceMinMi(mps) {
  if (!mps || mps <= 0) return '—';
  const minPerMi = 1609.34 / (mps * 60);
  const min = Math.floor(minPerMi);
  const sec = Math.round((minPerMi - min) * 60);
  return `${min}:${sec.toString().padStart(2, '0')}`;
}

// Duration formatting
export function fmtElapsed(seconds) {
  if (!seconds || seconds <= 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.round(seconds % 60);
  
  if (h > 0) {
    return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export function fmtDuration(seconds) {
  return fmtElapsed(seconds);
}

// Date formatting
export function fmtDate(dateStr) {
  if (!dateStr) return '—';
  const date = new Date(dateStr);
  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  });
}

export function fmtDateTime(dateStr) {
  if (!dateStr) return '—';
  const date = new Date(dateStr);
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  });
}

// Heart rate color coding
export function hrColor(hrRatio) {
  const ratio = Math.max(0, Math.min(1, hrRatio));
  if (ratio < 0.5) {
    // Green to yellow
    const t = ratio * 2;
    return `rgb(${Math.round(255 * t)}, ${Math.round(185 * (1 - t) + 255 * t)}, 0)`;
  }
  // Yellow to red
  const t = (ratio - 0.5) * 2;
  return `rgb(255, ${Math.round(255 * (1 - t))}, 0)`;
}

// Distance formatting with units
export function formatDistance(meters, imperial = false) {
  if (meters == null || meters === 0) return '—';
  if (imperial) {
    return `${mToMi(meters).toFixed(2)} mi`;
  }
  return `${mToKm(meters).toFixed(2)} km`;
}

// Elevation formatting with units
export function formatElevation(meters, imperial = false) {
  if (meters == null) return '—';
  if (imperial) {
    return `${Math.round(mToFt(meters))} ft`;
  }
  return `${Math.round(meters)} m`;
}

// Speed formatting with units
export function formatSpeed(mps, imperial = false) {
  if (mps == null || mps === 0) return '—';
  if (imperial) {
    return `${mpsToMph(mps).toFixed(1)} mph`;
  }
  return `${mpsToKph(mps).toFixed(1)} km/h`;
}

// Pace formatting based on sport and units
export function formatPace(mps, isRunning = true, imperial = false) {
  if (!mps || mps <= 0) return '—';
  if (isRunning) {
    return imperial ? fmtPaceMinMi(mps) : fmtPaceMin(mps);
  }
  return formatSpeed(mps, imperial);
}

export default {
  mToFt, mToKm, mToMi,
  mpsToKph, mpsToMph,
  fmtPaceMin, fmtPaceMinMi,
  fmtElapsed, fmtDuration,
  fmtDate, fmtDateTime,
  hrColor,
  formatDistance, formatElevation, formatSpeed, formatPace
};