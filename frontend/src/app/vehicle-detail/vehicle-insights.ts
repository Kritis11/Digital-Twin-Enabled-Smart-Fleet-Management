import { Component, computed, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import type { EChartsOption, LineSeriesOption } from 'echarts';
import { NgxEchartsDirective } from 'ngx-echarts';
import { EMPTY, catchError, combineLatest, forkJoin, switchMap, timer } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  DriverScore,
  DrivingEvent,
  FleetService,
  FuelReport,
  Period,
  RecommendationStatus,
  Trip,
  VehicleTwin,
  rulLevel,
} from '../fleet.service';

const PERIODS: Period[] = ['24h', '7d', '30d'];
const PARTS: Record<string, string> = { brakes: 'Brake pads', battery: 'Battery', tyres: 'Tyres', engine: 'Engine' };
const EVENT_LABELS: Record<string, string> = {
  HARSH_BRAKING: 'Harsh braking',
  RAPID_ACCELERATION: 'Rapid acceleration',
  SPEEDING: 'Speeding',
  SHARP_CORNERING: 'Sharp cornering',
  EXCESSIVE_IDLING: 'Excessive idling',
  FUEL_DROP: 'Fuel drop',
  LOW_EFFICIENCY: 'Low efficiency',
};
const SEVERITY_COLOURS = { LOW: '#8a94a0', MEDIUM: '#b7791f', HIGH: '#c0362c' };

/** Phase 2 sections of the vehicle page: remaining useful life, recommendations, driving, trips and fuel. */
@Component({
  selector: 'app-vehicle-insights',
  imports: [DatePipe, DecimalPipe, NgxEchartsDirective],
  template: `
    <section class="panel">
      <h2>Remaining useful life</h2>
      @if (!rulRows().length) {
        <p class="muted">No predictions yet. They appear once the ML service has wear history for this vehicle.</p>
      } @else {
        @for (row of rulRows(); track row.name) {
          <div class="rul-row">
            <span class="rul-name">{{ row.label }}</span>
            <div class="rul-track" [attr.aria-label]="row.label + ': about ' + row.days + ' days left'">
              <div class="rul-bar" [class]="row.level" [style.width.%]="row.barPercent"></div>
              <div
                class="rul-range"
                [style.left.%]="row.lowerPercent"
                [style.width.%]="row.rangePercent"
                title="Likely range"
              ></div>
            </div>
            <span class="rul-value">
              <strong>{{ row.days | number: '1.0-0' }} d</strong>
              <span class="muted">
                ({{ row.lower | number: '1.0-0' }}–{{ row.upper | number: '1.0-0' }},
                {{ row.confidence * 100 | number: '1.0-0' }}% conf.)</span
              >
            </span>
          </div>
        }
        <p class="muted legend">
          Bar: predicted days left. Dark band: likely range (80%). Scale ends at {{ barMax }} days.
        </p>
      }
    </section>

    <section class="panel">
      <h2>Recommendations</h2>
      @if (actionError()) {
        <p class="error" role="alert">Could not update the recommendation. Try again.</p>
      }
      @if (!recommendations().length) {
        <p class="muted">Nothing recommended for this vehicle.</p>
      } @else {
        @for (r of recommendations(); track r.id) {
          <div class="recommendation">
            <div>
              <span class="pill" [class]="r.priority.toLowerCase()">{{ r.priority }}</span>
              <strong> {{ r.action }}</strong>
              <span class="muted"> by {{ r.recommendedBy | date: 'd MMM' }} · {{ r.status }}</span>
              <p>{{ r.reason }}</p>
            </div>
            @if (canWrite()) {
              <div class="actions">
                @if (r.status === 'OPEN') {
                  <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'SCHEDULED')">Schedule</button>
                }
                <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'DONE')">Complete</button>
                <button type="button" [disabled]="busy()" (click)="setStatus(r.id, 'DISMISSED')">Dismiss</button>
              </div>
            }
          </div>
        }
      }
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>Driving and fuel</h2>
        <div class="segmented" role="group" aria-label="Period">
          @for (p of periods; track p) {
            <button type="button" [class.active]="period() === p" (click)="period.set(p)">{{ p }}</button>
          }
        </div>
      </div>
      @if (error()) {
        <p class="error" role="alert">Could not load driving data. Retrying…</p>
      }
      @if (!data()) {
        <p class="muted">Loading driving data…</p>
      } @else {
        <div class="cards">
          <div class="card">
            <span>Driver score</span>
            <strong>{{ data()!.score.score == null ? '–' : (data()!.score.score | number: '1.0-0') }}</strong>
          </div>
          <div class="card">
            <span>Distance</span><strong>{{ data()!.score.distanceKm | number: '1.0-0' }} km</strong>
          </div>
          <div class="card">
            <span>Trips</span><strong>{{ data()!.score.trips }}</strong>
          </div>
          <div class="card">
            <span>Fuel efficiency</span>
            <strong
              >{{ data()!.fuel.kmPerLitre == null ? '–' : (data()!.fuel.kmPerLitre | number: '1.2-2') }} km/l</strong
            >
          </div>
          <div class="card">
            <span>Idling</span>
            <strong>{{ data()!.fuel.idleLitres | number: '1.0-0' }} l</strong>
            <span>{{ data()!.fuel.idleHours | number: '1.1-1' }} h standing</span>
          </div>
        </div>
        <p class="muted">
          @for (c of eventCounts(); track c.label) {
            {{ c.label }}: <strong>{{ c.count }}</strong> &nbsp;
          }
        </p>

        @if (!data()!.events.length) {
          <p class="muted">No driving events in this period.</p>
        } @else {
          <div echarts [options]="timeline()" class="chart" aria-label="Driving event timeline"></div>
          @if (data()!.events.length >= 500) {
            <p class="muted">Showing the latest 500 events.</p>
          }
        }

        <div class="charts">
          <div echarts [options]="scoreChart()" class="chart" aria-label="Daily driver score chart"></div>
          <div echarts [options]="fuelChart()" class="chart" aria-label="Daily fuel efficiency chart"></div>
        </div>

        @if (data()!.fuel.anomalies.length) {
          <h3>Fuel anomalies</h3>
          <ul class="plain">
            @for (a of data()!.fuel.anomalies; track a.id) {
              <li>
                <span class="pill" [class]="severityClass(a)">{{ a.severity }}</span> {{ a.ts | date: 'd MMM HH:mm' }} ·
                {{ a.detail }}
              </li>
            }
          </ul>
        }

        <h3>Trips</h3>
        @if (!data()!.trips.length) {
          <p class="muted">
            No completed trips in this period. A trip is recorded once the vehicle has been parked for 5 minutes.
          </p>
        } @else {
          <div class="scroll tall">
            <table>
              <thead>
                <tr>
                  <th>Started</th>
                  <th>Duration</th>
                  <th>Distance</th>
                  <th>Avg / max speed</th>
                  <th>Idle</th>
                  <th>Fuel</th>
                  <th>Events</th>
                  <th>Score</th>
                </tr>
              </thead>
              <tbody>
                @for (t of data()!.trips; track t.id) {
                  <tr>
                    <td class="nowrap">{{ t.startedAt | date: 'd MMM HH:mm' }}</td>
                    <td>{{ t.durationS / 60 | number: '1.0-0' }} min</td>
                    <td>{{ t.distanceKm | number: '1.1-1' }} km</td>
                    <td>{{ t.avgSpeedKmh | number: '1.0-0' }} / {{ t.maxSpeedKmh | number: '1.0-0' }} km/h</td>
                    <td>{{ t.idleS / 60 | number: '1.0-0' }} min</td>
                    <td>{{ t.fuelUsedL | number: '1.1-1' }} l</td>
                    <td>{{ t.eventsCount }}</td>
                    <td>{{ t.driverScore | number: '1.0-0' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </section>
  `,
})
export class VehicleInsights {
  readonly twin = input.required<VehicleTwin>();

  private readonly fleet = inject(FleetService);
  protected readonly canWrite = this.fleet.canWrite;
  protected readonly periods = PERIODS;
  protected readonly barMax = environment.rul.barMaxDays;
  protected readonly period = signal<Period>('7d');
  protected readonly data = signal<
    { score: DriverScore; trips: Trip[]; events: DrivingEvent[]; fuel: FuelReport } | undefined
  >(undefined);
  protected readonly error = signal(false);
  protected readonly busy = signal(false);
  protected readonly actionError = signal(false);

  private readonly vehicleId = computed(() => this.twin().id);

  protected readonly rulRows = computed(() => {
    const percent = (days: number) => Math.min(100, (days / this.barMax) * 100);
    return Object.entries(this.twin().rul ?? {}).map(([name, r]) => ({
      name,
      label: PARTS[name] ?? name,
      ...r,
      level: rulLevel(r.days),
      barPercent: percent(r.days),
      lowerPercent: percent(r.lower),
      rangePercent: percent(r.upper) - percent(r.lower),
    }));
  });

  protected readonly recommendations = computed(() =>
    this.fleet
      .recommendations()
      .filter((r) => r.vehicleId === this.vehicleId() && (r.status === 'OPEN' || r.status === 'SCHEDULED')),
  );

  protected readonly eventCounts = computed(() =>
    Object.entries(this.data()?.score.eventCounts ?? {}).map(([type, count]) => ({
      label: EVENT_LABELS[type] ?? type,
      count,
    })),
  );

  protected readonly timeline = computed<EChartsOption>(() => {
    const events = this.data()?.events ?? [];
    const types = [...new Set(events.map((e) => e.type))].sort();
    return {
      animation: false,
      title: { text: 'Event timeline', left: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
      grid: { left: 130, right: 16, top: 36, bottom: 28 },
      // richText: event details are drawn as text, never interpreted as HTML
      tooltip: { trigger: 'item', renderMode: 'richText', formatter: '{b}' },
      xAxis: { type: 'time' },
      yAxis: { type: 'category', data: types.map((t) => EVENT_LABELS[t] ?? t) },
      series: [
        {
          type: 'scatter',
          symbolSize: 7,
          data: events.map((e) => ({
            name: e.detail,
            value: [e.ts, EVENT_LABELS[e.type] ?? e.type],
            itemStyle: { color: SEVERITY_COLOURS[e.severity] },
          })),
        },
      ],
    };
  });

  protected readonly scoreChart = computed<EChartsOption>(() =>
    dailyChart(
      'Driver score per day',
      (this.data()?.score.daily ?? []).map((d) => [d.day, d.score]),
      0,
      100,
    ),
  );

  protected readonly fuelChart = computed<EChartsOption>(() => {
    const fuel = this.data()?.fuel;
    const chart = dailyChart(
      'Fuel efficiency per day (km/l)',
      (fuel?.daily ?? []).map((d) => [d.day, d.kmPerLitre]),
    );
    if (fuel?.baselineKmPerLitre != null) {
      (chart.series as LineSeriesOption[])[0].markLine = {
        symbol: 'none',
        silent: true,
        lineStyle: { type: 'dashed', color: '#5d6b7a' },
        label: { formatter: 'baseline', fontSize: 10 },
        data: [{ yAxis: fuel.baselineKmPerLitre }],
      };
    }
    return chart;
  });

  constructor() {
    combineLatest([toObservable(this.vehicleId), toObservable(this.period)])
      .pipe(
        switchMap(([id, period]) => {
          this.data.set(undefined);
          return timer(0, environment.insightsRefreshMs).pipe(
            switchMap(() =>
              forkJoin({
                score: this.fleet.driverScore(id, period),
                trips: this.fleet.trips(id, period),
                events: this.fleet.events(id, period),
                fuel: this.fleet.fuel(id, period),
              }).pipe(
                catchError(() => {
                  this.error.set(true);
                  return EMPTY; // keep the timer running
                }),
              ),
            ),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((data) => {
        this.error.set(false);
        this.data.set(data);
      });
  }

  protected severityClass(event: DrivingEvent): string {
    return { LOW: 'low', MEDIUM: 'warning', HIGH: 'critical' }[event.severity];
  }

  protected setStatus(id: number, status: RecommendationStatus): void {
    this.busy.set(true);
    this.actionError.set(false);
    this.fleet.setRecommendationStatus(id, status).subscribe({
      next: () => this.busy.set(false),
      error: () => {
        this.busy.set(false);
        this.actionError.set(true);
      },
    });
  }
}

function dailyChart(title: string, data: [string, number | null][], min?: number, max?: number): EChartsOption {
  return {
    animation: false,
    title: { text: title, left: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
    grid: { left: 48, right: 16, top: 36, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', min, max, scale: min == null },
    series: [{ type: 'line', name: title, data, symbolSize: 5, lineStyle: { width: 1.5 } }],
  };
}
