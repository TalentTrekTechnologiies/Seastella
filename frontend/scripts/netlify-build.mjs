// Netlify build: live backend when SEASTELLA_API_ORIGIN is set, demo otherwise.
//
// With a backend, /api/* is proxied to it, so the browser stays on one origin
// and the backend needs no CORS. The rule is written into dist after the build
// rather than into public/, so the repository's _redirects is never edited.
import { execSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const origin = (process.env.SEASTELLA_API_ORIGIN ?? '').trim().replace(/\/+$/, '');

if (origin && !/^https:\/\/[^/\s]+$/.test(origin)) {
  console.error(`SEASTELLA_API_ORIGIN must be an https origin with no path, e.g. https://api.example.com (got "${origin}")`);
  process.exit(1);
}

const run = (cmd, options = {}) =>
  execSync(cmd, {
    stdio: 'inherit',
    ...options,
  });

if (origin) {
  const buildEnv = {
    ...process.env,
    VITE_API_URL: origin,
  };

  console.log('=== SEASTELLA BUILD CONFIG ===');
  console.log('SEASTELLA_API_ORIGIN:', buildEnv.SEASTELLA_API_ORIGIN);
  console.log('VITE_API_URL:', buildEnv.VITE_API_URL);
  console.log('==============================');

  console.log(`Building against the live backend at ${origin}`);
  run('npm run build', { env: buildEnv });
} else {
  console.log('SEASTELLA_API_ORIGIN not set: building the frontend-only demo');
  run('npm run build:demo');
}

const rules = [
  ...(origin ? [`/api/*  ${origin}/api/:splat  200`] : []),
  // Routes like /fleet exist only in React Router: serve the app for them.
  '/*  /index.html  200',
];
writeFileSync('dist/_redirects', rules.join('\n') + '\n');
console.log(`Wrote dist/_redirects:\n${rules.join('\n')}`);
