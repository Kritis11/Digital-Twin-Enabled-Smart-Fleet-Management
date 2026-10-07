// How responsive is the dashboard while the load test runs? Lives in e2e/ because it uses its Playwright:
//   cd e2e && node tools/dashboard-load.mjs <admin password> [seconds=20] [base url=https://localhost]
// Opens Fleet Overview in headless Chrome and reports, over the measuring period: frames per second,
// main-thread long tasks (over 50 ms) and how long they blocked in total, JS heap, twin updates
// received, and how long opening the Alerts page and a vehicle takes.
import { chromium } from '@playwright/test';

const [password, seconds = '20', base = 'https://localhost'] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await (await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1400, height: 1000 } })).newPage();
let twinFrames = 0;
page.on('websocket', (ws) => ws.on('framereceived', (f) => { if (String(f.payload).startsWith('MESSAGE') && String(f.payload).includes('/topic/twins')) twinFrames++; }));

await page.goto(base + '/login');
await page.getByLabel('Username').fill('admin');
await page.getByLabel('Password').fill(password);
const t0 = Date.now();
await page.getByRole('button', { name: 'Sign in' }).click();
await page.locator('.vehicle-marker').first().waitFor();
const overviewMs = Date.now() - t0;
const vehicles = await page.locator('tbody tr').count();

const before = twinFrames;
const measured = await page.evaluate(async (ms) => {
  let longTasks = 0, blocked = 0, frames = 0, running = true;
  new PerformanceObserver((list) => { for (const e of list.getEntries()) { longTasks++; blocked += e.duration - 50; } }).observe({ entryTypes: ['longtask'] });
  const tick = () => { frames++; if (running) requestAnimationFrame(tick); };
  requestAnimationFrame(tick);
  await new Promise((r) => setTimeout(r, ms));
  running = false;
  return { fps: frames / (ms / 1000), longTasks, blockedMs: Math.round(blocked), heapMb: Math.round((performance.memory?.usedJSHeapSize ?? 0) / 1048576) };
}, seconds * 1000);

const click = async (action, ready) => { const t = Date.now(); await action(); await ready(); return Date.now() - t; };
const alertsMs = await click(() => page.getByRole('link', { name: /^Alerts/ }).click(), () => page.getByRole('heading', { name: 'Alerts', level: 1 }).waitFor());
const vehicleMs = await click(() => page.goto(base + '/vehicles/1'), () => page.locator('canvas').first().waitFor());

console.log(JSON.stringify({
  vehicles, overviewMs, fps: +measured.fps.toFixed(1), longTasks: measured.longTasks,
  blockedPercent: +(100 * measured.blockedMs / (seconds * 1000)).toFixed(1), heapMb: measured.heapMb,
  twinUpdatesPerS: +((twinFrames - before) / seconds).toFixed(1), alertsMs, vehicleMs,
}));
await browser.close();
