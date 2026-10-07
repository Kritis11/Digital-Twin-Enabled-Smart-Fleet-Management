import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Client } from '@stomp/stompjs';
import { Observable, forkJoin, tap } from 'rxjs';
import { environment } from '../environments/environment';
import { AuthService } from './auth';

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
  rul: Record<string, Rul>;
  driverScore?: number;
  fuelEfficiencyKmPerLitre?: number;
  openRecommendations?: number;
}

export interface Rul {
  days: number;
  lower: number;
  upper: number;
  confidence: number;
}

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type RecommendationStatus = 'OPEN' | 'SCHEDULED' | 'DONE' | 'DISMISSED';

export interface Recommendation {
  id: number;
  vehicleId: number;
  component: string;
  action: string;
  priority: Priority;
  recommendedBy: string;
  reason: string;
  status: RecommendationStatus;
  updatedAt: string;
}

export interface DrivingEvent {
  id: number;
  type: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  ts: string;
  value: number;
  detail: string;
}

export interface Trip {
  id: number;
  startedAt: string;
  endedAt: string;
  distanceKm: number;
  durationS: number;
  avgSpeedKmh: number;
  maxSpeedKmh: number;
  idleS: number;
  fuelUsedL: number;
  eventsCount: number;
  driverScore: number;
}

export interface DriverScore {
  score: number | null;
  distanceKm: number;
  trips: number;
  eventCounts: Record<string, number>;
  daily: { day: string; score: number | null; distanceKm: number; events: number }[];
}

/** Totals the fuel endpoints report for a vehicle, a day or the fleet. */
export interface FuelFigures {
  trips: number;
  distanceKm: number;
  fuelLitres: number;
  kmPerLitre: number | null;
  litresPer100Km: number | null;
  idleHours: number;
  idleLitres: number;
  driverScore: number | null;
}

export interface FuelReport extends FuelFigures {
  baselineKmPerLitre: number | null;
  daily: ({ day: string } & FuelFigures)[];
  anomalies: DrivingEvent[];
}

export interface FuelSummary {
  vehicles: ({ vehicleId: number; registration: string; anomalies: number } & FuelFigures)[];
  totals: { distanceKm: number; fuelLitres: number; kmPerLitre: number | null; idleLitres: number };
  scoreEfficiencyCorrelation: number | null;
  idlingSummary: string;
}

export interface OptimiseRequest {
  vehicleIds: number[];
  depot: { lat: number; lng: number } | null;
  returnToStart: boolean;
  stops: { name: string; lat: number; lng: number; windowStart?: string; windowEnd?: string }[];
}

export interface OptimiseResult {
  departAt: string;
  routes: {
    vehicleId: number;
    registration: string;
    /** stop is the index into the request's stops. */
    stops: { stop: number; name?: string; lat: number; lng: number; arrival: string }[];
    distanceKm: number;
    durationMinutes: number;
    fuelLitres: number;
    geometry: [number, number][];
  }[];
  excluded: { vehicleId: number; registration: string; reasons: string[] }[];
  unassigned: number[];
  totalDistanceKm: number;
  totalFuelLitres: number;
  distanceSource?: 'osrm' | 'straight-line';
  note?: string;
}

export interface Utilisation {
  availableHoursPerVehicle: number;
  vehicles: {
    vehicleId: number; registration: string; trips: number; activeHours: number; idleHours: number;
    idleShare: number | null; distanceKm: number; utilisation: number; usage: 'UNDER_USED' | 'NORMAL' | 'OVER_USED';
  }[];
  fleet: { activeHours: number; idleHours: number; distanceKm: number; utilisation: number | null; underUsed: string[]; overUsed: string[] };
}

export type Period = '24h' | '7d' | '30d' | '90d';

/** Colour band for a remaining useful life in days. */
export function rulLevel(days: number): Level {
  return days <= environment.rul.urgentDays ? 'critical' : days <= environment.rul.soonDays ? 'warning' : 'ok';
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
  private readonly auth = inject(AuthService);
  /** False for viewers: pages hide their buttons and forms. */
  readonly canWrite = this.auth.canWrite;

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
  /** Every recommendation, most urgent first; refreshed on a timer and after each change. */
  readonly recommendations = signal<Recommendation[]>([]);
  readonly urgentRecommendations = computed(
    () => this.recommendations().filter((r) => r.status === 'OPEN' && r.priority === 'URGENT').length,
  );

  constructor() {
    setInterval(() => this.now.set(Date.now()), 1000);

    let connectedBefore = false;
    const client = new Client({
      brokerURL: environment.wsUrl,
      reconnectDelay: environment.wsReconnectMs,
      // The server accepts the WebSocket only with a valid access token on the CONNECT frame.
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${this.auth.session()?.accessToken}` };
      },
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

    // Data flows only while someone is logged in; logging out stops it and forgets what was loaded.
    let timer: ReturnType<typeof setInterval> | undefined;
    effect(() => {
      if (this.auth.loggedIn()) {
        this.load();
        this.loadRecommendations();
        timer = setInterval(() => this.loadRecommendations(), environment.insightsRefreshMs);
        client.activate();
      } else {
        clearInterval(timer);
        client.deactivate();
        connectedBefore = false;
        this.twins.set([]);
        this.alerts.set([]);
        this.recommendations.set([]);
        this.error.set(null);
      }
    });
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
        if (this.auth.loggedIn()) this.error.set(`Cannot reach the backend${this.api ? ' at ' + this.api : ''}.`);
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

  loadRecommendations(): void {
    // Errors are left to the main load()'s banner; the list just keeps its last contents.
    this.http.get<Recommendation[]>(`${this.api}/api/recommendations`).subscribe({
      next: (list) => this.recommendations.set(list),
      error: () => undefined,
    });
  }

  setRecommendationStatus(id: number, status: RecommendationStatus): Observable<Recommendation> {
    return this.http
      .patch<Recommendation>(`${this.api}/api/recommendations/${id}`, { status })
      .pipe(tap(() => this.loadRecommendations()));
  }

  recomputeRecommendations(): Observable<unknown> {
    return this.http.post(`${this.api}/api/recommendations/recompute`, null).pipe(tap(() => this.loadRecommendations()));
  }

  driverScore(vehicleId: number, period: Period): Observable<DriverScore> {
    return this.http.get<DriverScore>(`${this.api}/api/vehicles/${vehicleId}/driver-score`, { params: { period } });
  }

  trips(vehicleId: number, period: Period): Observable<Trip[]> {
    return this.http.get<Trip[]>(`${this.api}/api/vehicles/${vehicleId}/trips`, { params: { period } });
  }

  events(vehicleId: number, period: Period): Observable<DrivingEvent[]> {
    return this.http.get<DrivingEvent[]>(`${this.api}/api/vehicles/${vehicleId}/events`, { params: { period } });
  }

  fuel(vehicleId: number, period: Period): Observable<FuelReport> {
    return this.http.get<FuelReport>(`${this.api}/api/vehicles/${vehicleId}/fuel`, { params: { period } });
  }

  fuelSummary(period: Period): Observable<FuelSummary> {
    return this.http.get<FuelSummary>(`${this.api}/api/fleet/fuel-summary`, { params: { period } });
  }

  optimiseRoutes(request: OptimiseRequest): Observable<OptimiseResult> {
    return this.http.post<OptimiseResult>(`${this.api}/api/routes/optimise`, request);
  }

  utilisation(period: Period): Observable<Utilisation> {
    return this.http.get<Utilisation>(`${this.api}/api/fleet/utilisation`, { params: { period } });
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
