// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Movement lookup registry. Resolves an exercise/stretch name (or an explicit
// viewer_slug override from a custom exercise/stretch) to a slug that a future
// visual asset could be keyed by. No assets are registered today — this stays
// as the seam for wiring real exercise imagery back in later.
import { ALIASES } from "./aliases";

// slug -> true. Empty until a real asset source is wired back in.
const REGISTRY = {};

export function slugify(name) {
  return (name || "")
    .toLowerCase()
    .replace(/['’]/g, "")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

// Resolve a movement name to a registered slug, honouring explicit aliases and
// an optional custom-movement viewer_slug the user picked.
export function resolveSlug(name, viewerSlug = null) {
  if (viewerSlug && REGISTRY[viewerSlug]) return viewerSlug;
  const alias = ALIASES[name];
  if (alias && REGISTRY[alias]) return alias;
  const slug = slugify(name);
  return REGISTRY[slug] ? slug : null;
}

export function hasAnimation(name, viewerSlug = null) {
  return resolveSlug(name, viewerSlug) !== null;
}
