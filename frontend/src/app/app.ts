import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, PAGE_ROLES } from './auth';
import { FleetService } from './fleet.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  template: `
    @if (!auth.loggedIn()) {
      <main><router-outlet /></main>
    } @else {
      <div class="shell">
        <nav class="sidebar" aria-label="Main">
          <div class="brand">Fleet Twin</div>
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Fleet Overview</a>
          <a routerLink="/alerts" routerLinkActive="active">
            Alerts
            @if (fleet.openAlerts().length; as open) {
              <span class="badge" aria-label="open alerts">{{ open }}</span>
            }
          </a>
          <a routerLink="/planner" routerLinkActive="active">
            Maintenance Planner
            @if (fleet.urgentRecommendations(); as urgent) {
              <span class="badge" aria-label="urgent recommendations">{{ urgent }}</span>
            }
          </a>
          @if (auth.hasRole(...pages.fleetAnalysis)) {
            <a routerLink="/drivers-fuel" routerLinkActive="active">Drivers &amp; Fuel</a>
          }
          @if (auth.hasRole(...pages.planning)) {
            <a routerLink="/routes" routerLinkActive="active">Route Planner</a>
          }
          @if (auth.hasRole(...pages.admin)) {
            <a routerLink="/users" routerLinkActive="active">User Management</a>
          }
          <div class="live" [class.on]="fleet.live()" role="status">
            {{ fleet.live() ? 'Live' : 'Reconnecting…' }}
          </div>
          <div class="account">
            {{ auth.session()?.username }} <span class="muted">{{ auth.session()?.role }}</span>
            <button type="button" (click)="auth.logout()">Log out</button>
          </div>
        </nav>
        <main>
          @if (fleet.error(); as message) {
            <div class="banner" role="alert">
              {{ message }}
              <button type="button" (click)="fleet.load()">Retry</button>
            </div>
          }
          <router-outlet />
        </main>
      </div>
    }
  `,
})
export class App {
  protected readonly fleet = inject(FleetService);
  protected readonly auth = inject(AuthService);
  protected readonly pages = PAGE_ROLES;
}
