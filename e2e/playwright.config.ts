import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';

// Against the development stack (ng serve + backend on 8080) nothing needs setting: the admin login is
// read from the repo's .env. Against another stack, e.g. the production compose file behind nginx:
//   E2E_BASE_URL=https://localhost E2E_API_URL=https://localhost E2E_ADMIN_USERNAME=... E2E_ADMIN_PASSWORD=... npx playwright test
for (const line of safeRead('../.env').split('\n')) {
  const match = /^(ADMIN_USERNAME|ADMIN_PASSWORD)=(.*)$/.exec(line.trim());
  if (match) process.env[`E2E_${match[1]}`] ??= match[2];
}

function safeRead(path: string): string {
  try {
    return readFileSync(path, 'utf8'); // relative to e2e/, where the tests are run from
  } catch {
    return '';
  }
}

export default defineConfig({
  testDir: 'tests',
  globalSetup: './global-setup.ts',
  // The journeys change shared data (they acknowledge an alert, complete a recommendation), so one at a time.
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4200',
    ignoreHTTPSErrors: true, // a local production stack has a self-signed certificate
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1400, height: 1000 } } }],
});
