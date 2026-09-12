/**
 * Visual QA across all six dashboards.
 *
 * Signs in as each seeded role through the real login form, waits for the
 * dashboard's own data to render, and captures desktop and tablet widths.
 * Console errors and failed requests are collected per role: a screenshot that
 * looks fine while the console is full of errors is not a passing check.
 *
 *   node scripts/visual-qa.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.env.QA_BASE ?? 'http://localhost:5173';
const PASSWORD = 'SeaStella#Demo2026';
const OUT = 'qa-screenshots';

const ROLES = [
  { key: 'platform-admin', email: 'admin@seastella.example', settle: 'Platform activity' },
  { key: 'technical-head', email: 'tech.head@acme-shipmanagement.example', settle: 'Overdue services' },
  { key: 'ship-manager', email: 'd.fernandes@acme-shipmanagement.example', settle: 'Vessels' },
  { key: 'captain', email: 'master.kestrel@acme-shipmanagement.example', settle: 'My service requests' },
  { key: 'coordinator', email: 'coordinator@seastella.example', settle: 'Operations board' },
  { key: 'engineer', email: 't.okafor@marine-electronics.example', settle: 'My jobs' },
];

const VIEWPORTS = [
  { name: 'desktop', width: 1600, height: 1100 },
  { name: 'tablet', width: 900, height: 1200 },
];

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const results = [];

for (const role of ROLES) {
  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      deviceScaleFactor: 1,
    });
    const page = await context.newPage();

    const consoleErrors = [];
    const failedRequests = [];
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 160));
    });
    page.on('requestfailed', (r) => failedRequests.push(`${r.method()} ${r.url()}`));
    page.on('response', (r) => {
      if (r.url().includes('/api/') && r.status() >= 400) {
        failedRequests.push(`${r.status()} ${r.url()}`);
      }
    });

    let status = 'ok';
    let detail = '';

    try {
      await page.goto(BASE, { waitUntil: 'networkidle' });

      await page.fill('#signin-email', role.email);
      await page.fill('#signin-password', PASSWORD);
      await page.click('button[type="submit"]');

      // Wait for content the dashboard renders from its own API response,
      // not merely for the shell to mount.
      await page.waitForSelector(`text=${role.settle}`, { timeout: 20000 });

      // Let skeletons resolve so the capture shows the populated state.
      await page.waitForFunction(() => document.querySelectorAll('.skeleton').length === 0, {
        timeout: 15000,
      });
      await page.waitForTimeout(400);

      await page.screenshot({
        path: `${OUT}/${role.key}-${vp.name}.png`,
        fullPage: vp.name === 'desktop',
      });

      // A populated dashboard must render real figures, not just chrome.
      const kpiCount = await page.locator('.kpi').count();
      const panelCount = await page.locator('.panel').count();
      if (kpiCount === 0 || panelCount === 0) {
        status = 'empty';
        detail = `kpis=${kpiCount} panels=${panelCount}`;
      }
      if (consoleErrors.length || failedRequests.length) {
        status = 'errors';
        detail = [...consoleErrors, ...failedRequests].slice(0, 3).join(' | ');
      }
    } catch (e) {
      status = 'failed';
      detail = String(e.message).split('\n')[0].slice(0, 140);
      await page.screenshot({ path: `${OUT}/${role.key}-${vp.name}-FAILED.png` }).catch(() => {});
    }

    results.push({ role: role.key, viewport: vp.name, status, detail });
    await context.close();
  }
}

await browser.close();

console.log('\n' + '='.repeat(64));
console.log('VISUAL QA');
console.log('='.repeat(64));
for (const r of results) {
  const mark = r.status === 'ok' ? 'PASS' : 'FAIL';
  console.log(
    `  ${mark}  ${r.role.padEnd(16)} ${r.viewport.padEnd(8)} ${r.status}${r.detail ? ' :: ' + r.detail : ''}`,
  );
}
const bad = results.filter((r) => r.status !== 'ok');
console.log('='.repeat(64));
console.log(bad.length === 0 ? `ALL ${results.length} CAPTURES CLEAN` : `${bad.length} ISSUES`);
process.exit(bad.length ? 1 : 0);
