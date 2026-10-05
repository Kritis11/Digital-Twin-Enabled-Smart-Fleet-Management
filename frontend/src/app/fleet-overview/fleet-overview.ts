import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

type BackendStatus = 'checking' | 'connected' | 'unreachable';

@Component({
  selector: 'app-fleet-overview',
  template: `
    <main>
      <h1>Fleet Overview</h1>
      @switch (status()) {
        @case ('connected') {
          <p class="status ok" role="status">Backend connected</p>
        }
        @case ('unreachable') {
          <p class="status down" role="status">Backend unreachable</p>
        }
        @default {
          <p class="status" role="status">Checking backend…</p>
        }
      }
    </main>
  `,
  styles: `
    main { font-family: system-ui, sans-serif; margin: 2rem; }
    .status { font-weight: 600; }
    .ok { color: #1a7f37; }
    .down { color: #b42318; }
  `,
})
export class FleetOverview {
  protected readonly status = signal<BackendStatus>('checking');

  constructor() {
    inject(HttpClient)
      .get<{ status: string }>(`${environment.apiBaseUrl}/actuator/health`)
      .subscribe({
        next: (health) => this.status.set(health.status === 'UP' ? 'connected' : 'unreachable'),
        error: () => this.status.set('unreachable'),
      });
  }
}
