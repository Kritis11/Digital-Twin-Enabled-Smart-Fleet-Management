import { Page, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

export type Role = 'ADMIN' | 'FLEET_MANAGER' | 'TECHNICIAN' | 'VIEWER';

const users: Record<Role, { username: string; password: string }> = JSON.parse(readFileSync('.auth/users.json', 'utf8'));

export const MENU: Record<Role, string[]> = {
  ADMIN: ['Fleet Overview', 'Alerts', 'Maintenance Planner', 'Drivers & Fuel', 'Route Planner', 'Reports', 'User Management'],
  FLEET_MANAGER: ['Fleet Overview', 'Alerts', 'Maintenance Planner', 'Drivers & Fuel', 'Route Planner', 'Reports'],
  TECHNICIAN: ['Fleet Overview', 'Alerts', 'Maintenance Planner'],
  VIEWER: ['Fleet Overview', 'Alerts', 'Maintenance Planner', 'Drivers & Fuel', 'Reports'],
};

/** Signs in through the login form and waits for the dashboard to be live. */
export async function login(page: Page, role: Role): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill(users[role].username);
  await page.getByLabel('Password').fill(users[role].password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Fleet Overview' })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'Live' })).toBeVisible();
}

/** Opens a page through the menu, the way a user does (a full reload would also work, but is not the journey). */
export async function open(page: Page, name: string): Promise<void> {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name }).click();
  await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
}

/** The menu entries, without the badge counts next to Alerts and Maintenance Planner. */
export async function menu(page: Page): Promise<string[]> {
  const links = await page.getByRole('navigation', { name: 'Main' }).getByRole('link').allInnerTexts();
  return links.map((text) => text.replace(/\s*\d+$/, '').trim());
}
