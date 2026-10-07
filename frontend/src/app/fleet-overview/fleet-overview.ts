import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import * as L from 'leaflet';
import { environment } from '../../environments/environment';
import { FleetService, VehicleTwin, ago, healthLevel, rulLevel } from '../fleet.service';

@Component({
  selector: 'app-fleet-overview',
  imports: [RouterLink, DecimalPipe],
  template: `
    <h1>Fleet Overview</h1>

    <section class="cards" aria-label="Fleet summary">
      <div class="card">
        <span>Total vehicles</span><strong>{{ fleet.twins().length }}</strong>
      </div>
      <div class="card">
        <span>Moving</span><strong>{{ counts().MOVING }}</strong>
      </div>
      <div class="card">
        <span>Idle</span><strong>{{ counts().IDLE }}</strong>
      </div>
      <div class="card">
        <span>Offline</span><strong>{{ counts().OFFLINE }}</strong>
      </div>
      <a class="card" routerLink="/alerts" [class.critical]="fleet.openAlerts().length">
        <span>Open alerts</span><strong>{{ fleet.openAlerts().length }}</strong>
      </a>
      <a class="card" routerLink="/planner" [class.critical]="fleet.urgentRecommendations()">
        <span>Urgent recommendations</span><strong>{{ fleet.urgentRecommendations() }}</strong>
      </a>
      @if (lowestRul(); as low) {
        <a class="card" [routerLink]="['/vehicles', low.vehicleId]" [class.critical]="low.level === 'critical'">
          <span>Lowest remaining life</span>
          <strong>{{ low.days | number: '1.0-0' }} d</strong>
          <span>{{ low.registration }} · {{ low.component }}</span>
        </a>
      }
    </section>

    <div class="panel map" #map aria-label="Fleet map"></div>

    <section class="panel">
      <h2>Vehicles</h2>
      @if (fleet.loading()) {
        <p class="muted">Loading fleet…</p>
      } @else if (!fleet.twins().length) {
        <p class="muted">No vehicles.</p>
      } @else {
        <div class="scroll">
          <table>
            <thead>
              <tr>
                <th>Vehicle</th>
                <th>State</th>
                <th>Health</th>
                <th>Driver score</th>
                <th>Efficiency</th>
                <th>Last seen</th>
              </tr>
            </thead>
            <tbody>
              @for (twin of fleet.twins(); track twin.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/vehicles', twin.id]">{{ twin.registration }}</a>
                    <span class="muted"> {{ twin.make }} {{ twin.model }}</span>
                  </td>
                  <td>
                    <span class="pill" [class]="twin.state.toLowerCase()">{{ twin.state }}</span>
                  </td>
                  <td>
                    <span class="dot" [class]="level(twin)"></span>
                    {{ twin.healthScore == null ? '–' : (twin.healthScore | number: '1.0-0') }}
                  </td>
                  <td>{{ twin.driverScore == null ? '–' : (twin.driverScore | number: '1.0-0') }}</td>
                  <td>
                    {{
                      twin.fuelEfficiencyKmPerLitre == null
                        ? '–'
                        : (twin.fuelEfficiencyKmPerLitre | number: '1.2-2') + ' km/l'
                    }}
                  </td>
                  <td>{{ ago(twin.lastSeen, fleet.now()) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class FleetOverview {
  protected readonly fleet = inject(FleetService);
  protected readonly level = healthLevel;
  protected readonly ago = ago;
  protected readonly counts = computed(() => {
    const counts = { MOVING: 0, IDLE: 0, OFFLINE: 0 };
    for (const twin of this.fleet.twins()) counts[twin.state]++;
    return counts;
  });

  /** The part closest to predicted failure anywhere in the fleet. */
  protected readonly lowestRul = computed(() => {
    const all = this.fleet.twins().flatMap((t) =>
      Object.entries(t.rul ?? {}).map(([component, r]) => ({
        vehicleId: t.id,
        registration: t.registration,
        component,
        days: r.days,
        level: rulLevel(r.days),
      })),
    );
    return all.length ? all.reduce((a, b) => (b.days < a.days ? b : a)) : null;
  });

  private readonly router = inject(Router);
  private readonly mapEl = viewChild.required<ElementRef<HTMLElement>>('map');
  private readonly map = signal<L.Map | null>(null);
  private readonly markers = new Map<number, L.Marker>();
  private fitted = false;

  constructor() {
    afterNextRender(() => {
      const map = L.map(this.mapEl().nativeElement).setView(environment.map.center, environment.map.zoom);
      L.tileLayer(environment.map.tileUrl, { attribution: environment.map.attribution }).addTo(map);
      this.map.set(map);
    });
    effect(() => {
      const map = this.map();
      if (map) this.drawMarkers(map, this.fleet.twins());
    });
    inject(DestroyRef).onDestroy(() => this.map()?.remove());
  }

  private drawMarkers(map: L.Map, twins: VehicleTwin[]): void {
    const located = twins.filter((t) => t.lat != null && t.lng != null);
    for (const twin of located) {
      const position: L.LatLngTuple = [twin.lat!, twin.lng!];
      // An arrow pointing along the heading, coloured by health and dimmed when offline.
      const icon = L.divIcon({
        className: '',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
        html: `<div class="vehicle-marker ${healthLevel(twin)} ${twin.state.toLowerCase()}"
                    style="transform: rotate(${twin.heading ?? 0}deg)">▲</div>`,
      });
      // textContent, not an HTML string: registration comes from the database.
      const label = document.createElement('span');
      label.textContent = `${twin.registration} · ${twin.state} · health ${twin.healthScore?.toFixed(0) ?? '–'}`;

      let marker = this.markers.get(twin.id);
      if (!marker) {
        marker = L.marker(position, { icon, title: twin.registration }).addTo(map).bindTooltip(label);
        marker.on('click', () => this.router.navigate(['/vehicles', twin.id]));
        this.markers.set(twin.id, marker);
      } else {
        marker.setLatLng(position).setIcon(icon).setTooltipContent(label);
      }
    }
    if (!this.fitted && located.length) {
      map.fitBounds(L.latLngBounds(located.map((t) => [t.lat!, t.lng!])), { padding: [40, 40] });
      this.fitted = true;
    }
  }
}
