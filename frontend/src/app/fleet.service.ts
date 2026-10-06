import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Client } from '@stomp/stompjs';
import { Observable, forkJoin, tap } from 'rxjs';
import { environment } from '../environments/environment';

export type Status = 'OK' | 'WARNING' | 'CRITICAL';
export type Level = 'ok' | 'warning' | 'critical';

export interface VehicleTwin {
  id: number;
  registration: string;
  make: string;
  model: string;
  year: number;
  lat?: number;
  lng?: number;
  heading?: number;
  lastSeen?: string;
  state: 'MOVING' | 'IDLE' | 'OFFLINE';
  sensors: Record<string, number>;
  dtcCodes: string[];
  components: Record<string, Status>;
  healthScore?: number;
  anomaly?: boolean;
  anomalyScore?: number;
  anomalyReasons?: string[];
}

export interface Alert {
  id: number;
  vehicleId: number;
  component: string;
  severity: Status;
  message: string;
  source: 'RULE' | 'ML';
  createdAt: string;
  acknowledged: boolean;
}

export interface MaintenanceRecord {
  id?: number;
  vehicleId: number;
  type: string;
  description?: string | null;
  performedAt: string;
  cost?: number | null;
}

/** One time bucket from GET /api/vehicles/{id}/telemetry: ts plus the average of each sensor. */
export type TelemetryPoint = { ts: string } & Record<string, number | null>;

/** Colour band for a vehicle. Falls back to the worst component until the ML service has scored it. */
export function healthLevel(twin: VehicleTwin): Level {
  if (twin.healthScore == null) {
    const statuses = Object.values(twin.components);
    return statuses.includes('CRITICAL') ? 'critical' : statuses.includes('WARNING') ? 'warning' : 'ok';
  }
  const { greenAbove, amberFrom } = environment.health;
  return twin.healthScore > greenAbove ? 'ok' : twin.healthScore >= amberFrom ? 'warning' : 'critical';
}

export function ago(iso: string | undefined, now: number): string {
  if (!iso) return 'never';
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  return s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ago` : `${Math.floor(s / 3600)}h ago`;
}

/** Live fleet state: loaded over REST, then kept current by /topic/twins and /topic/alerts. */
@Injectable({ providedIn: 'root' })
export class FleetService {
  private readonly http = inject(HttpClient);
  private readonly api = environment.apiBaseUrl;

  readonly twins = signal<VehicleTwin[]>([]);
  /** Newest first, open and acknowledged (the backend caps the list). */
  readonly alerts = signal<Alert[]>([]);
  readonly openAlerts = computed(() => this.alerts().filter((a) => !a.acknowledged));
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  /** True while the WebSocket is connected. */
  readonly live = signal(false);
  /** Ticks every second so "last seen" labels stay current. */
  readonly now = signal(Date.now());

  constructor() {
    setInterval(() => this.now.set(Date.now()), 1000);
    this.load();

    let connectedBefore = false;
    const client = new Client({
      brokerURL: environment.wsUrl,
      reconnectDelay: environment.wsReconnectMs,
      onConnect: () => {
        // Anything pushed while we were disconnected is gone, so catch up over REST.
        if (connectedBefore) this.load();
        connectedBefore = true;
        this.live.set(true);
        client.subscribe('/topic/twins', (m) => this.upsertTwin(JSON.parse(m.body)));
        client.subscribe('/topic/alerts', (m) => this.upsertAlert(JSON.parse(m.body)));
      },
      onWebSocketClose: () => this.live.set(false),
    });
    client.activate();
  }

  load(): void {
    forkJoin({
      twins: this.http.get<VehicleTwin[]>(`${this.api}/api/vehicles`),
      alerts: this.http.get<Alert[]>(`${this.api}/api/alerts`, { params: { status: 'all' } }),
    }).subscribe({
      next: ({ twins, alerts }) => {
        this.twins.set(twins);
        this.alerts.set(alerts);
        this.error.set(null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(`Cannot reach the backend at ${this.api}.`);
        this.loading.set(false);
      },
    });
  }

  acknowledge(id: number): Observable<Alert> {
    return this.http
      .post<Alert>(`${this.api}/api/alerts/${id}/acknowledge`, null)
      .pipe(tap((alert) => this.upsertAlert(alert)));
  }

  telemetry(vehicleId: number, from: Date, to: Date): Observable<TelemetryPoint[]> {
    return this.http.get<TelemetryPoint[]>(`${this.api}/api/vehicles/${vehicleId}/telemetry`, {
      params: { from: from.toISOString(), to: to.toISOString() },
    });
  }

  alertsBetween(vehicleId: number, from: Date, to: Date): Observable<Alert[]> {
    return this.http.get<Alert[]>(`${this.api}/api/alerts`, {
      params: { status: 'all', vehicleId, from: from.toISOString(), to: to.toISOString() },
    });
  }

  maintenance(vehicleId: number): Observable<MaintenanceRecord[]> {
    return this.http.get<MaintenanceRecord[]>(`${this.api}/api/maintenance-records`, { params: { vehicleId } });
  }

  addMaintenance(record: MaintenanceRecord): Observable<MaintenanceRecord> {
    return this.http.post<MaintenanceRecord>(`${this.api}/api/maintenance-records`, record);
  }

  private upsertTwin(twin: VehicleTwin): void {
    this.twins.update((list) =>
      list.some((t) => t.id === twin.id)
        ? list.map((t) => (t.id === twin.id ? twin : t))
        : [...list, twin].sort((a, b) => a.id - b.id),
    );
  }

  private upsertAlert(alert: Alert): void {
    this.alerts.update((list) =>
      list.some((a) => a.id === alert.id) ? list.map((a) => (a.id === alert.id ? alert : a)) : [alert, ...list],
    );
  }
}
