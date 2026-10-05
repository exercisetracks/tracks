// Node ESM requires explicit file extensions; Vite does not, and the map style
// modules are written the Vite way (`import { buildSources } from "./sources"`).
// Rewriting ~24 source files just so a build script can import them would be
// the tail wagging the dog, so this hook does what Vite's resolver does:
// retry a failed relative specifier with `.js`, then `/index.js`.
//
// Imported for side effect by build-map-style.mjs. Deliberately narrow — it
// only fires after a resolution has already failed, so it cannot shadow a real
// module.

import { registerHooks } from "node:module";

const CANDIDATES = [".js", "/index.js"];

registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (err) {
      if (!specifier.startsWith(".")) throw err;
      for (const suffix of CANDIDATES) {
        try {
          return nextResolve(specifier + suffix, context);
        } catch {
          // try the next candidate
        }
      }
      throw err;
    }
  },
});
