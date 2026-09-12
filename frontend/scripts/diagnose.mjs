import { chromium } from 'playwright';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });

await page.goto('http://localhost:5173', { waitUntil: 'networkidle' });
await page.fill('#signin-email', 'master.kestrel@acme-shipmanagement.example');
await page.fill('#signin-password', 'SeaStella#Demo2026');
await page.click('button[type="submit"]');
await page.waitForSelector('text=My service requests', { timeout: 20000 });
await page.waitForTimeout(1500);

const info = await page.evaluate(() => {
  const rail = document.querySelector('.grid-main-rail');
  const stack = document.querySelector('.grid-main-rail > .stack');
  const panels = [...document.querySelectorAll('.panel')].map((p) => {
    const r = p.getBoundingClientRect();
    return {
      title: p.querySelector('.panel__title')?.textContent?.trim() ?? '(untitled)',
      w: Math.round(r.width),
      h: Math.round(r.height),
    };
  });
  return {
    railChildren: rail ? rail.children.length : null,
    stackExists: Boolean(stack),
    stackChildren: stack ? stack.children.length : null,
    stackRect: stack
      ? { w: Math.round(stack.getBoundingClientRect().width), h: Math.round(stack.getBoundingClientRect().height) }
      : null,
    skeletons: document.querySelectorAll('.skeleton').length,
    panels,
  };
});

console.log(JSON.stringify(info, null, 2));
await browser.close();
