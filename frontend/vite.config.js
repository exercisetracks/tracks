// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  resolve: {
    dedupe: ["react", "react-dom"],
  },
  optimizeDeps: {
    include: ["react", "react-dom", "react/jsx-dev-runtime", "@maplibre/maplibre-gl-geocoder"],
  },
  server: {
    port: 3000,
    host: "0.0.0.0",
    // Proxy /api to the backend so the dev server is a single origin.
    // Access dev at :3000 directly (not :4080) — HMR websocket needs a direct connection.
    proxy: {
      "/api": {
        target: "http://backend:8000",
        rewrite: (path) => path.replace(/^\/api/, ""),
        ws: true,
      },
    },
  },
  // `vite preview` (production) rejects requests whose Host header isn't
  // allow-listed. Behind Caddy the Host is the operator's domain, so disable
  // the check — access is gated by the operator's proxy / network.
  preview: {
    port: 3000,
    host: "0.0.0.0",
    allowedHosts: true,
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules/react-dom") || id.includes("node_modules/react/")) {
            return "vendor-react";
          }
          if (id.includes("node_modules/recharts")) return "recharts";
          if (id.includes("node_modules/maplibre-gl")) return "maplibre";
          // Heavy, independently-used libs — split out of the main entry chunk
          // so they cache separately and don't bloat every page's initial load.
          if (id.includes("node_modules/proj4")) return "vendor-proj4";
          if (id.includes("node_modules/pmtiles")) return "vendor-pmtiles";
          if (id.includes("node_modules/@dnd-kit")) return "vendor-dndkit";
          if (id.includes("node_modules/react-router") || id.includes("node_modules/@remix-run")) {
            return "vendor-router";
          }
        },
      },
    },
    chunkSizeWarningLimit: 1000,
  },
});
