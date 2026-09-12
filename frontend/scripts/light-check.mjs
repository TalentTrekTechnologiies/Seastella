/** Captures one dashboard in the light theme, to confirm both themes resolve. */
import { chromium } from 'playwright';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });

await page.goto('http://localhost:5173', { waitUntil: 'networkidle' });
await page.fill('#signin-email', 'tech.head@acme-shipmanagement.example');
await page.fill('#signin-password', 'SeaStella#Demo2026');
await page.click('button[type="submit"]');
await page.waitForSelector('text=Maintenance radar', { timeout: 20000 });
await page.waitForFunction(() => document.querySelectorAll('.skeleton').length === 0, { timeout: 15000 });

await page.click('.theme-toggle');
await page.waitForTimeout(500);

const theme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));

// A transparent body would borrow the host's ground; assert it is painted.
const bodyBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
const textColor = await page.evaluate(() => getComputedStyle(document.body).color);

await page.screenshot({ path: 'qa-screenshots/technical-head-light.png', fullPage: false });
console.log(JSON.stringify({ theme, bodyBg, textColor }, null, 2));
await browser.close();
