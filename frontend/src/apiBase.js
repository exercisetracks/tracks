// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Single source of truth for the backend base URL.
//
// VITE_API_URL is baked at build time. A *relative* value (e.g. "/api") is
// resolved against the page origin at runtime, so one production build works
// behind any reverse proxy / domain without rebuilding — the homelab operator
// points their proxy at the single Caddy entry container, which routes /api/* to
// the backend. An *absolute* value (the dev default, http://localhost:8000) is
// used as-is so `npm run dev` talks to the backend directly.
const raw = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

export const BASE_URL =
  raw.startsWith("/") && typeof window !== "undefined"
    ? window.location.origin + raw.replace(/\/$/, "")
    : raw;
