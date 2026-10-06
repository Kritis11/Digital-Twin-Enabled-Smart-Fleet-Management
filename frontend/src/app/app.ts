import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FleetService } from './fleet.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  template: `
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
        <div class="live" [class.on]="fleet.live()" role="status">
          {{ fleet.live() ? 'Live' : 'Reconnecting…' }}
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
  `,
})
export class App {
  protected readonly fleet = inject(FleetService);
}
