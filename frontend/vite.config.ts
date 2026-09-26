import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Served at the root of its own host, https://thawemarine.seastella.in/.
// To serve it under a path instead, set this (e.g. '/thawemarine/'): assets,
// routes and API calls all derive from it.
const BASE = '/';

export default defineConfig({
  base: BASE,
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    // The API is served by the Spring Boot app at /api. Proxying in dev keeps
    // the frontend same-origin, exactly as nginx does in production
    // (deploy/nginx-thawemarine.conf).
    proxy: {
      [`${BASE}api`]: {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (p) => p.slice(BASE.length - 1),
      },
    },
  },
});
