import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { FleetService, RecommendationStatus } from '../fleet.service';

@Component({
  selector: 'app-planner',
  imports: [FormsModule, RouterLink, DatePipe],
  template: `
    <div class="panel-head">
      <h1>Maintenance Planner</h1>
      @if (fleet.canWrite()) {
        <button type="button" [disabled]="busy()" (click)="recompute()">Recompute now</button>
      }
    </div>

    <section class="panel filters" aria-label="Filters">
      <label>Status
        <select [(ngModel)]="status">
          <option value="ACTIVE">Open and scheduled</option>
          <option value="OPEN">Open</option>
          <option value="SCHEDULED">Scheduled</option>
          <option value="DONE">Done</option>
          <option value="DISMISSED">Dismissed</option>
          <option value="">All</option>
        </select>
      </label>
      <label>Priority
        <select [(ngModel)]="priority">
          <option value="">Any</option>
          <option value="URGENT">Urgent</option>
          <option value="HIGH">High</option>
          <option value="MEDIUM">Medium</option>
          <option value="LOW">Low</option>
        </select>
      </label>
      <label>Vehicle
        <select [(ngModel)]="vehicleId">
          <option [ngValue]="null">Any</option>
          @for (twin of fleet.twins(); track twin.id) {
            <option [ngValue]="twin.id">{{ twin.registration }}</option>
          }
        </select>
      </label>
      <span class="muted">{{ shown().length }} shown</span>
    </section>

    <section class="panel">
      @if (failed()) {
        <p class="error" role="alert">That change did not go through. Try again.</p>
      }
      @if (!shown().length) {
        <p class="muted">No recommendations match these filters.</p>
      } @else {
        <div class="scroll">
          <table>
            <thead>
              <tr><th>Priority</th><th>By</th><th>Vehicle</th><th>Action</th><th>Why</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              @for (r of shown(); track r.id) {
                <tr [class.acknowledged]="r.status === 'DONE' || r.status === 'DISMISSED'">
                  <td><span class="pill" [class]="r.priority.toLowerCase()">{{ r.priority }}</span></td>
                  <td class="nowrap">{{ r.recommendedBy | date: 'd MMM' }}</td>
                  <td><a [routerLink]="['/vehicles', r.vehicleId]">{{ registrations().get(r.vehicleId) ?? r.vehicleId }}</a></td>
                  <td>{{ r.action }}</td>
                  <td>{{ r.reason }}</td>
                  <td>{{ r.status }}</td>
                  <td class="actions">
                    @if (fleet.canWrite() && r.status === 'OPEN') {
                      <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'SCHEDULED')">Schedule</button>
                    }
                    @if (fleet.canWrite() && (r.status === 'OPEN' || r.status === 'SCHEDULED')) {
                      <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'DONE')">Complete</button>
                      <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'DISMISSED')">Dismiss</button>
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
export class Planner {
  protected readonly fleet = inject(FleetService);

  protected readonly status = signal('ACTIVE');
  protected readonly priority = signal('');
  protected readonly vehicleId = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly failed = signal(false);

  protected readonly registrations = computed(() => new Map(this.fleet.twins().map((t) => [t.id, t.registration])));
  /** The backend already sorts by priority, then recommended-by date. */
  protected readonly shown = computed(() =>
    this.fleet.recommendations().filter(
      (r) =>
        (!this.status() || r.status === this.status() ||
          (this.status() === 'ACTIVE' && (r.status === 'OPEN' || r.status === 'SCHEDULED'))) &&
        (!this.priority() || r.priority === this.priority()) &&
        (this.vehicleId() == null || r.vehicleId === this.vehicleId()),
    ),
  );

  constructor() {
    this.fleet.loadRecommendations();
  }

  protected setStatus(id: number, status: RecommendationStatus): void {
    this.run(this.fleet.setRecommendationStatus(id, status));
  }

  protected recompute(): void {
    this.run(this.fleet.recomputeRecommendations());
  }

  private run(request: ReturnType<FleetService['recomputeRecommendations']>): void {
    this.busy.set(true);
    this.failed.set(false);
    request.subscribe({
      next: () => this.busy.set(false),
      error: () => {
        this.busy.set(false);
        this.failed.set(true);
      },
    });
  }
}
