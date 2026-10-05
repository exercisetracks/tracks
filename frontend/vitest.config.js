// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    include: ['**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
    // Pinned, because src/apiBase.js reads VITE_API_URL and the API-facing
    // tests mock fetch by exact path. Left to the ambient environment, the
    // same suite passes on a developer's machine (where the variable is unset)
    // and fails inside the frontend container (where compose sets it to
    // "/api", so every mocked path gains a prefix the mock does not match) —
    // eight failures that say nothing about the code. This is the value the
    // tests were written against: an absolute base, which apiBase strips.
    env: {
      VITE_API_URL: 'http://localhost:8000',
    },
  },
});