import { expect, test } from '@playwright/test';
import { MENU, Role, login, menu } from './helpers';

test('an anonymous visitor only gets the login page', async ({ page }) => {
  for (const path of ['/', '/alerts', '/users', '/vehicles/1']) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login$/);
  }
  await expect(page.getByRole('navigation')).toHaveCount(0);
});

test('a wrong password is refused with a message', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Username').fill('e2e-viewer');
  await page.getByLabel('Password').fill('definitely-not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
  await expect(page).toHaveURL(/\/login$/);
});

for (const role of Object.keys(MENU) as Role[]) {
  test(`${role} sees exactly the pages for that role and can log out`, async ({ page }) => {
    await login(page, role);
    expect(await menu(page)).toEqual(MENU[role]);

    // A page that is not in the menu cannot be reached by typing its address either.
    for (const [path, name] of [
      ['/drivers-fuel', 'Drivers & Fuel'],
      ['/routes', 'Route Planner'],
      ['/reports', 'Reports'],
      ['/users', 'User Management'],
    ]) {
      await page.goto(path);
      if (MENU[role].includes(name)) {
        await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
      } else {
        await expect(page.getByRole('heading', { name: 'Fleet Overview' })).toBeVisible();
      }
    }

    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto('/alerts');
    await expect(page).toHaveURL(/\/login$/);
  });
}
