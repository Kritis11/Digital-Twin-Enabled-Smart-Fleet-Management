import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { FleetService } from '../fleet.service';

@Component({
  selector: 'app-alerts',
  imports: [FormsModule, RouterLink, DatePipe],
  template: `
    <h1>Alerts</h1>

    <section class="panel filters" aria-label="Filters">
      <label
        >Status
        <select [(ngModel)]="status">
          <option value="open">Open</option>
          <option value="all">All</option>
        </select>
      </label>
      <label
        >Severity
        <select [(ngModel)]="severity">
          <option value="">Any</option>
          <option value="CRITICAL">Critical</option>
          <option value="WARNING">Warning</option>
        </select>
      </label>
      <label
        >Vehicle
        <select [(ngModel)]="vehicleId">
          <option [ngValue]="null">Any</option>
          @for (twin of fleet.twins(); track twin.id) {
            <option [ngValue]="twin.id">{{ twin.registration }}</option>
          }
        </select>
      </label>
      <label
        >Source
        <select [(ngModel)]="source">
          <option value="">Any</option>
          <option value="RULE">Rule</option>
          <option value="ML">ML</option>
        </select>
      </label>
      <span class="muted">{{ shown().length }} shown</span>
    </section>

    <section class="panel">
      @if (failed()) {
        <p class="error" role="alert">Could not acknowledge the alert. Try again.</p>
      }
      @if (fleet.loading()) {
        <p class="muted">Loading alerts…</p>
      } @else if (!shown().length) {
        <p class="muted">No alerts match these filters.</p>
      } @else {
        <div class="scroll">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Vehicle</th>
                <th>Component</th>
                <th>Severity</th>
                <th>Source</th>
                <th>Message</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (alert of shown(); track alert.id) {
                <tr [class.acknowledged]="alert.acknowledged">
                  <td class="nowrap">{{ alert.createdAt | date: 'd MMM HH:mm:ss' }}</td>
                  <td>
                    <a [routerLink]="['/vehicles', alert.vehicleId]">{{
                      registrations().get(alert.vehicleId) ?? alert.vehicleId
                    }}</a>
                  </td>
                  <td>{{ alert.component }}</td>
                  <td>
                    <span class="pill" [class]="alert.severity.toLowerCase()">{{ alert.severity }}</span>
                  </td>
                  <td>
                    <span class="pill" [class]="alert.source.toLowerCase()">{{ alert.source }}</span>
                  </td>
                  <td>{{ alert.message }}</td>
                  <td>
                    @if (alert.acknowledged) {
                      <span class="muted">Acknowledged</span>
                    } @else if (fleet.canWrite()) {
                      <button type="button" [disabled]="busy().has(alert.id)" (click)="acknowledge(alert.id)">
                        Acknowledge
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class Alerts {
  protected readonly fleet = inject(FleetService);

  protected readonly status = signal<'open' | 'all'>('open');
  protected readonly severity = signal('');
  protected readonly source = signal('');
  protected readonly vehicleId = signal<number | null>(null);
  protected readonly busy = signal(new Set<number>());
  protected readonly failed = signal(false);

  protected readonly registrations = computed(() => new Map(this.fleet.twins().map((t) => [t.id, t.registration])));
  protected readonly shown = computed(() =>
    this.fleet
      .alerts()
      .filter(
        (a) =>
          (this.status() === 'all' || !a.acknowledged) &&
          (!this.severity() || a.severity === this.severity()) &&
          (!this.source() || a.source === this.source()) &&
          (this.vehicleId() == null || a.vehicleId === this.vehicleId()),
      ),
  );

  protected acknowledge(id: number): void {
    const setBusy = (on: boolean) =>
      this.busy.update((ids) => {
        const next = new Set(ids);
        if (on) next.add(id);
        else next.delete(id);
        return next;
      });
    setBusy(true);
    this.failed.set(false);
    this.fleet.acknowledge(id).subscribe({
      next: () => setBusy(false),
      error: () => {
        setBusy(false);
        this.failed.set(true);
      },
    });
  }
}
