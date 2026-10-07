import { request } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';

/**
 * Makes sure one user per role exists, with a password generated for this run, and writes the four
 * logins to .auth/users.json for the tests. Uses the admin from E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD.
 */
export default async function globalSetup(): Promise<void> {
  const api = process.env.E2E_API_URL ?? 'http://localhost:8080';
  const admin = { username: process.env.E2E_ADMIN_USERNAME ?? 'admin', password: process.env.E2E_ADMIN_PASSWORD ?? '' };
  if (!admin.password) throw new Error('Set E2E_ADMIN_PASSWORD (or ADMIN_PASSWORD in the repo .env)');

  const http = await request.newContext({ baseURL: api, ignoreHTTPSErrors: true });
  const login = await http.post('/api/auth/login', { data: admin });
  if (!login.ok()) throw new Error(`Admin login at ${api} failed with ${login.status()}: is the stack running?`);
  const headers = { Authorization: `Bearer ${(await login.json()).accessToken}` };

  const password = randomBytes(18).toString('base64url');
  const existing: { id: number; username: string }[] = await (await http.get('/api/users', { headers })).json();
  const users: Record<string, { username: string; password: string }> = { ADMIN: admin };
  for (const role of ['FLEET_MANAGER', 'TECHNICIAN', 'VIEWER']) {
    const username = `e2e-${role.toLowerCase().replace('_', '-')}`;
    const found = existing.find((u) => u.username === username);
    const response = found
      ? await http.patch(`/api/users/${found.id}`, { headers, data: { password, role, enabled: true } })
      : await http.post('/api/users', { headers, data: { username, password, role } });
    if (!response.ok()) throw new Error(`Could not prepare ${username}: ${response.status()} ${await response.text()}`);
    users[role] = { username, password };
  }
  // A throwaway user for the admin journey to change, put back to an active VIEWER before every run.
  const temp = existing.find((u) => u.username === 'e2e-temp');
  const prepared = temp
    ? await http.patch(`/api/users/${temp.id}`, { headers, data: { role: 'VIEWER', enabled: true } })
    : await http.post('/api/users', { headers, data: { username: 'e2e-temp', password, role: 'VIEWER' } });
  if (!prepared.ok()) throw new Error(`Could not prepare e2e-temp: ${prepared.status()}`);
  mkdirSync('.auth', { recursive: true });
  writeFileSync('.auth/users.json', JSON.stringify(users));
  await http.dispose();
}
