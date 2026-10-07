import { expect, test } from '@playwright/test';
import { login, open } from './helpers';

test('fleet manager: overview, a vehicle in detail, and back', async ({ page }) => {
  await login(page, 'FLEET_MANAGER');

  // Fleet Overview: summary cards, one map marker and one table row per vehicle
  await expect(page.getByRole('link', { name: /Open alerts/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /Urgent recommendations/ })).toBeVisible();
  const rows = page.locator('tbody tr');
  await expect(rows.first()).toBeVisible();
  const vehicles = await rows.count();
  expect(vehicles).toBeGreaterThanOrEqual(5);
  await expect(page.locator('.vehicle-marker')).toHaveCount(vehicles);

  // Vehicle detail, reached by clicking the first vehicle
  const registration = (await rows.first().getByRole('link').innerText()).trim();
  await rows.first().getByRole('link').click();
  await expect(page.getByRole('heading', { name: registration, level: 1 })).toBeVisible();
  await expect(page.locator('.tile')).toHaveCount(8); // engine, battery, brakes, four tyres, fuel
  await expect(page.getByText('Health score')).toBeVisible();
  for (const section of ['Remaining useful life', 'Recommendations', 'Driving and fuel', 'Telemetry', 'Maintenance history']) {
    await expect(page.getByRole('heading', { name: section })).toBeVisible();
  }
  await expect(page.locator('section', { hasText: 'Telemetry' }).locator('canvas').first()).toBeVisible();
  await page.getByRole('button', { name: '24 h' }).click();
  await expect(page.getByRole('button', { name: '24 h' })).toHaveClass(/active/);

  await page.getByRole('link', { name: '← Fleet Overview' }).click();
  await expect(page.getByRole('heading', { name: 'Fleet Overview' })).toBeVisible();
});

test('technician acknowledges an alert', async ({ page }) => {
  await login(page, 'TECHNICIAN');
  await open(page, 'Alerts');

  const open_ = page.locator('tbody tr');
  await expect(open_.first()).toBeVisible();
  const row = open_.first();
  const message = (await row.locator('td').nth(5).innerText()).trim();
  const when = (await row.locator('td').first().innerText()).trim();
  await row.getByRole('button', { name: 'Acknowledge' }).click();

  // It leaves the list of open alerts and shows as acknowledged under "all"
  const same = page.locator('tbody tr', { hasText: when }).filter({ hasText: message });
  await expect(same.getByRole('button', { name: 'Acknowledge' })).toHaveCount(0);
  await page.getByLabel('Status').selectOption('all');
  await expect(same.first()).toContainText('Acknowledged');
});

test('technician completes a recommendation, which records the maintenance', async ({ page }) => {
  await login(page, 'TECHNICIAN');
  await open(page, 'Maintenance Planner');

  // the least pressing one, so the urgent ones stay for whoever looks at the system next
  const row = page.locator('tbody tr').last();
  await expect(row).toBeVisible();
  const action = (await row.locator('td').nth(3).innerText()).trim();
  const registration = (await row.locator('td').nth(2).innerText()).trim();
  const before = await page.locator('tbody tr').count();
  await row.getByRole('button', { name: 'Complete' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(before - 1);

  // The work is now in that vehicle's maintenance history
  await page.getByLabel('Status').selectOption('DONE');
  await page.locator('tbody tr', { hasText: action }).filter({ hasText: registration }).first().getByRole('link', { name: registration }).click();
  const history = page.locator('section', { has: page.getByRole('heading', { name: 'Maintenance history' }) });
  await expect(history.locator('tbody tr').first()).toContainText(action);
});

test('fleet manager plans routes for stops clicked on the map', async ({ page }) => {
  await login(page, 'FLEET_MANAGER');
  await open(page, 'Route Planner');

  const map = page.getByLabel('Route map');
  await expect(map.locator('.stop-marker').first()).toBeVisible(); // vehicles are drawn once the map has fitted them
  const box = (await map.boundingBox())!;
  const centre = { x: box.x + box.width / 2, y: box.y + box.height / 2 };

  await page.getByRole('button', { name: 'Set a depot' }).click();
  await page.mouse.click(centre.x, centre.y);
  await expect(page.getByRole('button', { name: 'Move depot', exact: true })).toBeVisible();
  // a few pixels apart: the map shows the whole country here, so this is tens of kilometres
  for (const [dx, dy] of [[-12, -8], [10, -9], [14, 6], [-9, 10], [2, -13], [-15, 2]]) {
    await page.mouse.click(centre.x + dx, centre.y + dy);
  }
  const stops = page.locator('section', { has: page.getByRole('button', { name: 'Optimise routes' }) }).locator('tbody tr');
  await expect(stops).toHaveCount(6);
  await stops.first().getByRole('button', { name: 'Remove' }).click();
  await expect(stops).toHaveCount(5);

  await page.getByRole('button', { name: 'Optimise routes' }).click();
  const result = page.locator('section', { has: page.getByRole('heading', { name: 'Routes', exact: true }) });
  await expect(result).toBeVisible({ timeout: 45_000 });

  // Either some vehicles are fit and get routes, or none is and the page says so. Both are correct
  // answers; which one depends on the state of the fleet. An unfit vehicle is always explained.
  const routes = result.locator('tbody tr');
  await expect(routes.first().or(result.getByText('No vehicle is fit to be assigned.'))).toBeVisible();
  if (await routes.count()) {
    await expect(result).toContainText(/km and about .* l of fuel in total/);
    await expect(map.locator('path.leaflet-interactive')).toHaveCount(await routes.count());
    const planned = (await routes.allInnerTexts()).join(' ').match(/Stop \d/g) ?? [];
    const unreachable = await result.getByText('Not reachable').count();
    expect(planned.length + unreachable).toBeGreaterThan(0);
    expect(new Set(planned).size).toBe(planned.length); // no stop is visited twice
  } else {
    await expect(result).toContainText('No vehicle is fit to be assigned.');
  }
  const leftOut = result.locator('ul li');
  for (const reason of await leftOut.allInnerTexts()) {
    expect(reason).toMatch(/Urgent recommendation outstanding|open critical alert|predicted to fail|Position unknown/);
  }
  if (!(await routes.count())) expect(await leftOut.count()).toBeGreaterThan(0);
});

test('fleet manager generates a report and downloads it again from the list', async ({ page }) => {
  await login(page, 'FLEET_MANAGER');
  await open(page, 'Reports');
  const past = page.locator('tbody tr');
  await page.waitForLoadState('networkidle');
  const before = await past.count();

  await page.getByLabel('Report').selectOption('MAINTENANCE');
  await page.getByLabel('Format').selectOption('XLSX');
  const [generated] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Generate' }).click()]);
  expect(generated.suggestedFilename()).toMatch(/^maintenance_\d{4}-\d\d-\d\d_\d{4}-\d\d-\d\d\.xlsx$/);
  expect(await generated.failure()).toBeNull();

  await expect(past).toHaveCount(before + 1);
  await expect(past.first()).toContainText('Maintenance report');
  await expect(past.first()).toContainText('Excel');
  await expect(past.first()).toContainText('e2e-fleet-manager');
  const [again] = await Promise.all([page.waitForEvent('download'), past.first().getByRole('button', { name: 'Download' }).click()]);
  expect(again.suggestedFilename()).toBe(generated.suggestedFilename());
});

test('fleet manager sees drivers, fuel and utilisation', async ({ page }) => {
  await login(page, 'FLEET_MANAGER');
  await open(page, 'Drivers & Fuel');
  for (const section of ['Driver score ranking', 'Fuel efficiency and idling', 'Utilisation']) {
    await expect(page.getByRole('heading', { name: section })).toBeVisible();
  }
  await page.getByRole('button', { name: '30d' }).click();
  await expect(page.getByRole('heading', { name: 'Utilisation' })).toBeVisible();
  await expect(page.locator('section', { has: page.getByRole('heading', { name: 'Utilisation' }) }).locator('tbody tr').first()).toContainText('%');
});

test('viewer can look at everything on their pages but change nothing', async ({ page }) => {
  await login(page, 'VIEWER');
  await open(page, 'Alerts');
  await expect(page.locator('tbody tr').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Acknowledge' })).toHaveCount(0);

  await open(page, 'Maintenance Planner');
  await expect(page.locator('tbody tr').first()).toBeVisible();
  await expect(page.locator('main').getByRole('button')).toHaveCount(0);

  await open(page, 'Reports');
  await expect(page.getByRole('button', { name: 'Generate' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Past reports' })).toBeVisible();

  await page.goto('/vehicles/1');
  await expect(page.getByRole('heading', { name: 'Maintenance history' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add record' })).toHaveCount(0);
});

test('admin is told why a user cannot be added, changes a role, and finds it in the audit log', async ({ page }) => {
  await login(page, 'ADMIN');
  await open(page, 'User Management');
  const users = page.locator('section', { has: page.getByRole('heading', { name: 'Users', exact: true }) });
  const row = users.locator('tbody tr').filter({ has: page.getByRole('cell', { name: 'e2e-temp', exact: true }) }); // prepared as an active VIEWER by global-setup
  await expect(row).toContainText('Active');

  await page.getByLabel('Username').fill('e2e-never-created');
  await page.getByLabel('Password').fill('short');
  await page.getByRole('button', { name: 'Add user' }).click();
  await expect(page.getByRole('alert')).toContainText('at least 10 characters');
  await page.getByLabel('Username').fill('e2e-temp');
  await page.getByLabel('Password').fill('long-enough-password');
  await page.getByRole('button', { name: 'Add user' }).click();
  await expect(page.getByRole('alert')).toContainText('That username is taken');
  await expect(users.locator('tbody tr', { hasText: 'e2e-never-created' })).toHaveCount(0);

  await row.getByRole('combobox').selectOption('TECHNICIAN');
  await row.getByRole('button', { name: 'Disable' }).click();
  await expect(row).toContainText('Disabled');

  const audit = page.locator('section', { has: page.getByRole('heading', { name: 'Audit log' }) });
  await expect(audit.locator('tbody tr').nth(1)).toContainText('e2e-temp: role VIEWER -> TECHNICIAN');
  await expect(audit.locator('tbody tr').first()).toContainText('e2e-temp: disabled');
});
