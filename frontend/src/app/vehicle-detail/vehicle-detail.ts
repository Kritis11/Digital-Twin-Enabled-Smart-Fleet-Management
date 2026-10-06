import { Component, computed, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import type { EChartsOption } from 'echarts';
import { NgxEchartsDirective } from 'ngx-echarts';
import { EMPTY, catchError, combineLatest, forkJoin, switchMap, timer } from 'rxjs';
import { environment } from '../../environments/environment';
import { Alert, FleetService, MaintenanceRecord, TelemetryPoint, ago, healthLevel } from '../fleet.service';

const RANGES = { '15 min': 15, '1 h': 60, '24 h': 24 * 60 } as const;
type Range = keyof typeof RANGES;

/** Charted sensors. `components` are the twin components whose RULE alerts are marked on that chart. */
const CHARTS = [
  { key: 'engineTemp', label: 'Engine temperature', unit: '°C', components: ['engine'] },
  { key: 'rpm', label: 'RPM', unit: 'rpm', components: ['engine'] },
  { key: 'speed', label: 'Speed', unit: 'km/h', components: [] },
  { key: 'vibration', label: 'Vibration', unit: 'g', components: ['engine'] },
  { key: 'batteryVoltage', label: 'Battery', unit: 'V', components: ['battery'] },
  { key: 'fuelLevel', label: 'Fuel', unit: '%', components: ['fuel'] },
];

/** Tile label plus the readings shown on it ([sensor, unit]), per twin component. */
const TILES: Record<string, { label: string; readings: [string, string][] }> = {
  engine: { label: 'Engine', readings: [['engineTemp', '°C'], ['vibration', 'g']] },
  battery: { label: 'Battery', readings: [['batteryVoltage', 'V']] },
  brakes: { label: 'Brakes', readings: [['brakePadWear', '% worn']] },
  tyre_fl: { label: 'Tyre front left', readings: [['tyrePressureFl', 'psi']] },
  tyre_fr: { label: 'Tyre front right', readings: [['tyrePressureFr', 'psi']] },
  tyre_rl: { label: 'Tyre rear left', readings: [['tyrePressureRl', 'psi']] },
  tyre_rr: { label: 'Tyre rear right', readings: [['tyrePressureRr', 'psi']] },
  fuel: { label: 'Fuel system', readings: [['fuelLevel', '%']] },
};

const MARK_COLOURS = { ML: '#7c3aed', RULE: '#d97706' };
const GAUGE_ARC = Math.PI * 50; // length of the semicircle path below

@Component({
  selector: 'app-vehicle-detail',
  imports: [RouterLink, FormsModule, DatePipe, DecimalPipe, NgxEchartsDirective],
  template: `
    <p><a routerLink="/">← Fleet Overview</a></p>

    @if (twin(); as t) {
      <header class="panel twin-header">
        <div>
          <h1>{{ t.registration }}</h1>
          <p class="muted">{{ t.make }} {{ t.model }} · {{ t.year }} · vehicle #{{ t.id }}</p>
          <p>
            <span class="pill" [class]="t.state.toLowerCase()">{{ t.state }}</span>
            <span class="muted"> last seen {{ ago(t.lastSeen, fleet.now()) }}</span>
          </p>
          @if (t.anomaly) {
            <p class="anomaly" role="status">
              <strong>ML anomaly</strong> (score {{ t.anomalyScore | number: '1.2-2' }}):
              {{ t.anomalyReasons?.join(', ') }}
            </p>
          }
          @if (t.dtcCodes.length) {
            <p class="muted">Fault codes: {{ t.dtcCodes.join(', ') }}</p>
          }
        </div>
        <figure class="gauge" [class]="level(t)">
          <svg viewBox="0 0 120 70" role="img" [attr.aria-label]="'Health score ' + (t.healthScore ?? 'unknown')">
            <path class="track" d="M10 60 A50 50 0 0 1 110 60" />
            <path class="value" d="M10 60 A50 50 0 0 1 110 60" [attr.stroke-dasharray]="gaugeDash(t.healthScore)" />
            <text x="60" y="58">{{ t.healthScore == null ? '–' : (t.healthScore | number: '1.0-0') }}</text>
          </svg>
          <figcaption>Health score</figcaption>
        </figure>
      </header>

      <section class="tiles" aria-label="Component status">
        @for (tile of tiles(); track tile.name) {
          <div class="tile" [class]="tile.status.toLowerCase()">
            <span>{{ tile.label }}</span>
            <strong>{{ tile.status }}</strong>
            <span>{{ tile.readings }}</span>
          </div>
        }
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>Telemetry</h2>
          <div class="segmented" role="group" aria-label="Time range">
            @for (r of ranges; track r) {
              <button type="button" [class.active]="range() === r" (click)="range.set(r)">{{ r }}</button>
            }
          </div>
        </div>
        <p class="muted legend">
          <span class="mark ml"></span> ML anomaly <span class="mark rule"></span> rule alert
        </p>
        @if (chartError()) {
          <p class="error" role="alert">Could not load telemetry history. Retrying…</p>
        }
        @if (!points()) {
          <p class="muted">Loading telemetry…</p>
        } @else if (!points()!.length) {
          <p class="muted">No telemetry in this time range.</p>
        } @else {
          <div class="charts">
            @for (chart of charts(); track chart.key) {
              <div echarts [options]="chart.options" class="chart" [attr.aria-label]="chart.label + ' chart'"></div>
            }
          </div>
        }
      </section>

      <section class="panel">
        <h2>Maintenance history</h2>
        <form class="filters" (ngSubmit)="addRecord()">
          <label>Type <input name="type" [(ngModel)]="type" required maxlength="50" placeholder="e.g. Oil change" /></label>
          <label>Performed <input name="performedAt" type="datetime-local" [(ngModel)]="performedAt" required /></label>
          <label>Cost <input name="cost" type="number" min="0" step="0.01" [(ngModel)]="cost" /></label>
          <label class="grow">Description <input name="description" [(ngModel)]="description" /></label>
          <button type="submit" [disabled]="saving() || !type() || !performedAt()">Add record</button>
        </form>
        @if (maintenanceError(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }
        @if (!records()) {
          <p class="muted">Loading maintenance history…</p>
        } @else if (!records()!.length) {
          <p class="muted">No maintenance recorded for this vehicle.</p>
        } @else {
          <div class="scroll">
            <table>
              <thead><tr><th>Performed</th><th>Type</th><th>Description</th><th>Cost</th></tr></thead>
              <tbody>
                @for (record of records(); track record.id) {
                  <tr>
                    <td class="nowrap">{{ record.performedAt | date: 'd MMM y HH:mm' }}</td>
                    <td>{{ record.type }}</td>
                    <td>{{ record.description }}</td>
                    <td>{{ record.cost == null ? '–' : (record.cost | number: '1.2-2') }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    } @else if (fleet.loading()) {
      <p class="muted">Loading vehicle…</p>
    } @else {
      <p class="error" role="alert">Vehicle {{ id() }} was not found.</p>
    }
  `,
})
export class VehicleDetail {
  /** Route parameter. */
  readonly id = input.required<string>();

  protected readonly fleet = inject(FleetService);
  protected readonly level = healthLevel;
  protected readonly ago = ago;
  protected readonly ranges = Object.keys(RANGES) as Range[];

  protected readonly vehicleId = computed(() => Number(this.id()));
  protected readonly twin = computed(() => this.fleet.twins().find((t) => t.id === this.vehicleId()));
  protected readonly tiles = computed(() =>
    Object.entries(this.twin()?.components ?? {}).map(([name, status]) => {
      const tile = TILES[name] ?? { label: name, readings: [] };
      const sensors = this.twin()?.sensors ?? {};
      const readings = tile.readings.map(([sensor, unit]) => `${sensors[sensor]?.toFixed(1) ?? '–'} ${unit}`);
      return { name, status, label: tile.label, readings: readings.join(' · ') };
    }),
  );

  protected readonly range = signal<Range>('15 min');
  /** undefined until the first response for the current vehicle and range. */
  protected readonly points = signal<TelemetryPoint[] | undefined>(undefined);
  protected readonly chartError = signal(false);
  private readonly marks = signal<Alert[]>([]);
  protected readonly charts = computed(() => {
    const points = this.points() ?? [];
    const marks = this.marks();
    return CHARTS.map((chart) => ({ ...chart, options: chartOptions(chart, points, marks) }));
  });

  protected readonly records = signal<MaintenanceRecord[] | undefined>(undefined);
  protected readonly maintenanceError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly type = signal('');
  protected readonly performedAt = signal('');
  protected readonly cost = signal<number | null>(null);
  protected readonly description = signal('');

  constructor() {
    // Re-fetch the chart range on a timer; switching vehicle or range restarts it.
    combineLatest([toObservable(this.vehicleId), toObservable(this.range)])
      .pipe(
        switchMap(([vehicleId, range]) => {
          this.points.set(undefined);
          return timer(0, environment.chartRefreshMs).pipe(
            switchMap(() => {
              const to = new Date();
              const from = new Date(to.getTime() - RANGES[range] * 60_000);
              return forkJoin([
                this.fleet.telemetry(vehicleId, from, to),
                this.fleet.alertsBetween(vehicleId, from, to),
              ]).pipe(
                catchError(() => {
                  this.chartError.set(true);
                  return EMPTY; // keep the timer running
                }),
              );
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(([points, alerts]) => {
        this.chartError.set(false);
        this.points.set(points);
        this.marks.set(alerts);
      });

    toObservable(this.vehicleId)
      .pipe(
        switchMap((vehicleId) => {
          this.records.set(undefined);
          this.maintenanceError.set(null);
          return this.fleet.maintenance(vehicleId).pipe(
            catchError(() => {
              this.maintenanceError.set('Could not load maintenance history.');
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((records) => this.records.set(records));
  }

  protected gaugeDash(score: number | undefined): string {
    return `${((score ?? 0) / 100) * GAUGE_ARC} ${GAUGE_ARC}`;
  }

  protected addRecord(): void {
    if (!this.type().trim() || !this.performedAt()) return;
    this.saving.set(true);
    this.maintenanceError.set(null);
    this.fleet
      .addMaintenance({
        vehicleId: this.vehicleId(),
        type: this.type().trim(),
        description: this.description().trim() || null,
        performedAt: new Date(this.performedAt()).toISOString(),
        cost: this.cost(),
      })
      .subscribe({
        next: (record) => {
          this.records.update((list) =>
            [record, ...(list ?? [])].sort((a, b) => b.performedAt.localeCompare(a.performedAt)),
          );
          this.type.set('');
          this.performedAt.set('');
          this.cost.set(null);
          this.description.set('');
          this.saving.set(false);
        },
        error: () => {
          this.maintenanceError.set('Could not save the record. Check the values and try again.');
          this.saving.set(false);
        },
      });
  }
}

function chartOptions(chart: (typeof CHARTS)[number], points: TelemetryPoint[], alerts: Alert[]): EChartsOption {
  // ML anomalies are vehicle-wide, so they appear on every chart; rule alerts only on their component's charts.
  const marks = alerts.filter((a) => a.source === 'ML' || chart.components.includes(a.component));
  return {
    animation: false,
    title: { text: `${chart.label} (${chart.unit})`, left: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
    grid: { left: 48, right: 16, top: 52, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', scale: true },
    series: [
      {
        type: 'line',
        name: chart.label,
        showSymbol: false,
        lineStyle: { width: 1.5 },
        data: points.map((p) => [p.ts, p[chart.key]]),
        markLine: {
          symbol: 'none',
          silent: true,
          data: marks.map((a) => ({
            xAxis: a.createdAt,
            lineStyle: { color: MARK_COLOURS[a.source], type: a.source === 'ML' ? 'solid' : 'dashed' },
            label: { formatter: a.source, fontSize: 9, color: MARK_COLOURS[a.source] },
          })),
        },
      },
    ],
  };
}
