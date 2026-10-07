import { Component, DestroyRef, ElementRef, afterNextRender, effect, inject, signal, viewChild } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import { environment } from '../../environments/environment';
import { FleetService, OptimiseResult } from '../fleet.service';

interface PlannedStop {
  name: string;
  lat: number;
  lng: number;
  /** Optional arrival window as HH:mm today. */
  from: string;
  to: string;
}

@Component({
  selector: 'app-route-planner',
  imports: [FormsModule, DatePipe, DecimalPipe],
  template: `
    <h1>Route Planner</h1>
    <p class="muted">
      Click the map to add delivery stops, then optimise. Vehicles with an urgent recommendation, an open critical
      alert or a part close to failure are left out, and the reason is shown.
    </p>

    <div class="panel map" #map aria-label="Route map"></div>

    <section class="panel">
      <div class="filters">
        <button type="button" [class.active]="placingDepot()" (click)="placingDepot.set(!placingDepot())">
          {{ placingDepot() ? 'Click the map to place the depot…' : depot() ? 'Move depot' : 'Set a depot' }}
        </button>
        @if (depot()) {
          <button type="button" (click)="depot.set(null)">Remove depot</button>
        }
        <label class="check"><input type="checkbox" [(ngModel)]="returnToStart" /> Return to start</label>
        <span class="muted">
          {{ depot() ? 'Every route starts at the depot.' : 'No depot: each vehicle starts from where it is now.' }}
        </span>
      </div>
      <div class="filters" role="group" aria-label="Vehicles on offer">
        @for (twin of fleet.twins(); track twin.id) {
          <label class="check">
            <input type="checkbox" [checked]="!skipped().has(twin.id)" (change)="toggleVehicle(twin.id)" />
            {{ twin.registration }}
          </label>
        }
      </div>

      @if (!stops().length) {
        <p class="muted">No stops yet.</p>
      } @else {
        <div class="scroll">
          <table>
            <thead>
              <tr><th>#</th><th>Name</th><th>Position</th><th>Arrive from</th><th>Arrive by</th><th></th></tr>
            </thead>
            <tbody>
              @for (stop of stops(); track $index; let i = $index) {
                <tr>
                  <td>{{ i + 1 }}</td>
                  <td><input [(ngModel)]="stop.name" [attr.aria-label]="'Name of stop ' + (i + 1)" /></td>
                  <td class="nowrap">{{ stop.lat | number: '1.4-4' }}, {{ stop.lng | number: '1.4-4' }}</td>
                  <td><input type="time" [(ngModel)]="stop.from" [attr.aria-label]="'Earliest arrival at stop ' + (i + 1)" /></td>
                  <td><input type="time" [(ngModel)]="stop.to" [attr.aria-label]="'Latest arrival at stop ' + (i + 1)" /></td>
                  <td><button type="button" (click)="removeStop(i)">Remove</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <div class="actions">
        <button type="button" class="primary" [disabled]="busy() || !stops().length" (click)="optimise()">
          {{ busy() ? 'Optimising…' : 'Optimise routes' }}
        </button>
        <button type="button" [disabled]="busy() || !stops().length" (click)="clear()">Clear stops</button>
      </div>
      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
    </section>

    @if (result(); as r) {
      <section class="panel">
        <h2>Routes</h2>
        @if (!r.routes.length && !r.distanceSource) {
          <!-- the solver was never asked: nobody to send -->
          <p class="error">{{ r.note ?? 'No vehicle is fit to be assigned.' }}</p>
        } @else if (!r.routes.length) {
          <p class="error">
            No route could be planned: none of the stops can be reached within a day's driving or inside its time window.
          </p>
        } @else {
          <p>
            {{ r.totalDistanceKm | number: '1.0-1' }} km and about {{ r.totalFuelLitres | number: '1.0-1' }} l of fuel in total,
            leaving {{ r.departAt | date: 'HH:mm' }}.
            <span class="muted">
              Distances: {{ r.distanceSource === 'osrm' ? 'by road (OSRM)' : 'straight-line estimate' }}.
              @if (r.note) { {{ r.note }} }
            </span>
          </p>
          <div class="scroll">
            <table>
              <thead>
                <tr><th>Vehicle</th><th>Stops in order</th><th>Distance</th><th>Time</th><th>Est. fuel</th></tr>
              </thead>
              <tbody>
                @for (route of r.routes; track route.vehicleId; let i = $index) {
                  <tr>
                    <td class="nowrap"><span class="dot" [style.background]="colour(i)"></span>{{ route.registration }}</td>
                    <td>
                      @for (visit of route.stops; track visit.stop; let last = $last) {
                        {{ visit.name || 'Stop ' + (visit.stop + 1) }}
                        <span class="muted">({{ visit.arrival | date: 'HH:mm' }})</span>{{ last ? '' : ' → ' }}
                      }
                    </td>
                    <td class="nowrap">{{ route.distanceKm | number: '1.1-1' }} km</td>
                    <td class="nowrap">{{ route.durationMinutes / 60 | number: '1.1-1' }} h</td>
                    <td class="nowrap">{{ route.fuelLitres | number: '1.1-1' }} l</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
        @if (r.routes.length && r.unassigned.length) {
          <p class="error">
            Not reachable inside their time window:
            @for (i of r.unassigned; track i; let last = $last) {
              {{ stops()[i]?.name || 'Stop ' + (i + 1) }}{{ last ? '' : ', ' }}
            }
          </p>
        }
        @if (r.excluded.length) {
          <h3>Vehicles left out</h3>
          <ul class="plain">
            @for (x of r.excluded; track x.vehicleId) {
              <li><strong>{{ x.registration }}</strong>: {{ x.reasons.join('; ') }}</li>
            }
          </ul>
        }
      </section>
    }
  `,
})
export class RoutePlanner {
  protected readonly fleet = inject(FleetService);

  protected readonly stops = signal<PlannedStop[]>([]);
  protected readonly depot = signal<{ lat: number; lng: number } | null>(null);
  protected readonly placingDepot = signal(false);
  protected readonly returnToStart = signal(true);
  /** Vehicles the user has unticked. */
  protected readonly skipped = signal(new Set<number>());
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<OptimiseResult | null>(null);

  private readonly mapEl = viewChild.required<ElementRef<HTMLElement>>('map');
  private readonly map = signal<L.Map | null>(null);
  private readonly layer = L.layerGroup();
  private fitted = false;

  constructor() {
    afterNextRender(() => {
      const map = L.map(this.mapEl().nativeElement).setView(environment.map.center, environment.map.zoom);
      L.tileLayer(environment.map.tileUrl, { attribution: environment.map.attribution }).addTo(map);
      this.layer.addTo(map);
      map.on('click', (e) => this.onMapClick(e.latlng));
      this.map.set(map);
    });
    effect(() => {
      const map = this.map();
      if (map) this.draw(map);
    });
    inject(DestroyRef).onDestroy(() => this.map()?.remove());
  }

  protected colour(i: number): string {
    return environment.routeColours[i % environment.routeColours.length];
  }

  protected toggleVehicle(id: number): void {
    this.skipped.update((set) => {
      const next = new Set(set);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected removeStop(index: number): void {
    this.stops.update((list) => list.filter((_, i) => i !== index));
    this.result.set(null);
  }

  protected clear(): void {
    this.stops.set([]);
    this.result.set(null);
  }

  protected optimise(): void {
    this.busy.set(true);
    this.error.set(null);
    this.fleet
      .optimiseRoutes({
        vehicleIds: this.fleet.twins().map((t) => t.id).filter((id) => !this.skipped().has(id)),
        depot: this.depot(),
        returnToStart: this.returnToStart(),
        stops: this.stops().map((s) => ({
          name: s.name, lat: s.lat, lng: s.lng, windowStart: todayAt(s.from), windowEnd: todayAt(s.to),
        })),
      })
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.result.set(result);
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(e.error?.detail ?? 'Route optimisation failed. Is the ML service running?');
        },
      });
  }

  private onMapClick(at: L.LatLng): void {
    if (this.placingDepot()) {
      this.depot.set({ lat: at.lat, lng: at.lng });
      this.placingDepot.set(false);
    } else {
      this.stops.update((list) => [...list, { name: `Stop ${list.length + 1}`, lat: at.lat, lng: at.lng, from: '', to: '' }]);
    }
    this.result.set(null);
  }

  private draw(map: L.Map): void {
    this.layer.clearLayers();
    const result = this.result();
    result?.routes.forEach((route, i) =>
      L.polyline(route.geometry, { color: this.colour(i), weight: 5, opacity: 0.8 }).bindTooltip(route.registration).addTo(this.layer),
    );
    // Stop markers take the colour of the route that serves them.
    const colourOfStop = new Map<number, string>();
    result?.routes.forEach((route, i) => route.stops.forEach((v) => colourOfStop.set(v.stop, this.colour(i))));
    this.stops().forEach((stop, i) =>
      pin(String(i + 1), colourOfStop.get(i) ?? '#5d6b7a', stop.name).setLatLng([stop.lat, stop.lng]).addTo(this.layer),
    );
    const depot = this.depot();
    if (depot) pin('D', '#16202c', 'Depot').setLatLng([depot.lat, depot.lng]).addTo(this.layer);
    const twins = this.fleet.twins().filter((t) => t.lat != null && t.lng != null);
    if (!depot) {
      for (const t of twins) pin('▲', '#1f5fbf', t.registration).setLatLng([t.lat!, t.lng!]).addTo(this.layer);
    }
    if (result?.routes.length) {
      map.fitBounds(L.latLngBounds(result.routes.flatMap((r) => r.geometry)), { padding: [30, 30] });
    } else if (!this.fitted && twins.length) {
      map.fitBounds(L.latLngBounds(twins.map((t) => [t.lat!, t.lng!])), { padding: [40, 40] });
      this.fitted = true;
    }
  }
}

function pin(text: string, colour: string, title: string): L.Marker {
  const el = document.createElement('div');
  el.className = 'stop-marker';
  el.style.background = colour;
  el.textContent = text; // textContent: stop names are user input
  return L.marker([0, 0], { icon: L.divIcon({ className: '', iconSize: [24, 24], iconAnchor: [12, 12], html: el }), title });
}

/** "09:30" -> today at that local time as an ISO instant; '' -> undefined. */
function todayAt(time: string): string | undefined {
  if (!time) return undefined;
  const [h, m] = time.split(':').map(Number);
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d.toISOString();
}
