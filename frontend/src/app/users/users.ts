import { Component, inject, signal } from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { environment } from '../../environments/environment';
import { AuthService, Role } from '../auth';

interface User {
  id: number;
  username: string;
  role: Role;
  enabled: boolean;
  createdAt: string;
}

interface AuditEntry {
  id: number;
  ts: string;
  username: string;
  action: string;
  entity: string;
  entityId: number | null;
  detail: string | null;
}

const ROLES: Role[] = ['ADMIN', 'FLEET_MANAGER', 'TECHNICIAN', 'VIEWER'];

@Component({
  selector: 'app-users',
  imports: [FormsModule, DatePipe, JsonPipe],
  template: `
    <h1>User Management</h1>
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }

    <section class="panel">
      <h2>Users</h2>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>Username</th>
              <th>Role</th>
              <th>Status</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (u of users(); track u.id) {
              <tr [class.acknowledged]="!u.enabled">
                <td>{{ u.username }}{{ u.username === auth.session()?.username ? ' (you)' : '' }}</td>
                <td>
                  <select
                    [ngModel]="u.role"
                    (ngModelChange)="change(u, { role: $event })"
                    [attr.aria-label]="'Role of ' + u.username"
                  >
                    @for (r of roles; track r) {
                      <option [value]="r">{{ r }}</option>
                    }
                  </select>
                </td>
                <td>{{ u.enabled ? 'Active' : 'Disabled' }}</td>
                <td class="nowrap">{{ u.createdAt | date: 'd MMM y' }}</td>
                <td class="actions">
                  <button type="button" (click)="change(u, { enabled: !u.enabled })">
                    {{ u.enabled ? 'Disable' : 'Enable' }}
                  </button>
                  <button type="button" (click)="resetPassword(u)">Reset password</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      <h3>Add a user</h3>
      <form class="filters" (ngSubmit)="create()">
        <label>Username <input name="username" [(ngModel)]="username" autocomplete="off" required /></label>
        <label
          >Password <input name="password" type="password" [(ngModel)]="password" autocomplete="new-password" required
        /></label>
        <label
          >Role
          <select name="role" [(ngModel)]="role">
            @for (r of roles; track r) {
              <option [value]="r">{{ r }}</option>
            }
          </select>
        </label>
        <button type="submit" [disabled]="!username() || !password()">Add user</button>
      </form>
    </section>

    <section class="panel">
      <h2>Audit log</h2>
      @if (!audit().length) {
        <p class="muted">Nothing recorded yet.</p>
      } @else {
        <div class="scroll tall">
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Who</th>
                <th>Action</th>
                <th>What</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              @for (a of audit(); track a.id) {
                <tr>
                  <td class="nowrap">{{ a.ts | date: 'd MMM HH:mm:ss' }}</td>
                  <td>{{ a.username }}</td>
                  <td>{{ a.action }}</td>
                  <td class="nowrap">{{ a.entity }}{{ a.entityId == null ? '' : ' #' + a.entityId }}</td>
                  <td>{{ a.detail }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    <section class="panel">
      <h2>Thresholds and settings</h2>
      <p class="muted">
        The values in force. They are set in the backend's application.yml (or environment variables) and apply after a
        restart.
      </p>
      <details>
        <summary>Show settings</summary>
        <pre>{{ settings() | json }}</pre>
      </details>
    </section>
  `,
})
export class Users {
  private readonly http = inject(HttpClient);
  protected readonly auth = inject(AuthService);
  private readonly api = `${environment.apiBaseUrl}/api`;

  protected readonly roles = ROLES;
  protected readonly users = signal<User[]>([]);
  protected readonly audit = signal<AuditEntry[]>([]);
  protected readonly settings = signal<unknown>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly role = signal<Role>('VIEWER');

  constructor() {
    this.load();
    this.http.get(`${this.api}/admin/settings`).subscribe((s) => this.settings.set(s));
  }

  protected create(): void {
    this.error.set(null);
    this.http
      .post(`${this.api}/users`, { username: this.username(), password: this.password(), role: this.role() })
      .subscribe({
        next: () => {
          this.username.set('');
          this.password.set('');
          this.load();
        },
        error: (e) => this.fail(e),
      });
  }

  protected change(user: User, change: { role?: Role; enabled?: boolean; password?: string }): void {
    this.error.set(null);
    this.http
      .patch(`${this.api}/users/${user.id}`, change)
      .subscribe({ next: () => this.load(), error: (e) => this.fail(e) });
  }

  protected resetPassword(user: User): void {
    const password = prompt(`New password for ${user.username}`);
    if (password) this.change(user, { password });
  }

  private load(): void {
    this.http.get<User[]>(`${this.api}/users`).subscribe((list) => this.users.set(list));
    this.http.get<AuditEntry[]>(`${this.api}/audit-log`).subscribe((list) => this.audit.set(list));
  }

  private fail(e: { error?: { detail?: string } }): void {
    this.error.set(e.error?.detail ?? 'That change did not go through.');
    this.load(); // puts a select the user changed back to the stored value
  }
}
