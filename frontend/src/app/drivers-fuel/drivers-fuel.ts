import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import type { EChartsOption } from 'echarts';
import { NgxEchartsDirective } from 'ngx-echarts';
import { EMPTY, catchError, forkJoin, switchMap, timer } from 'rxjs';
import { environment } from '../../environments/environment';
import { FleetService, FuelSummary, Period, Utilisation } from '../fleet.service';

const PERIODS: Period[] = ['24h', '7d', '30d', '90d'];

@Component({
  selector: 'app-drivers-fuel',
  imports: [RouterLink, DecimalPipe, PercentPipe, NgxEchartsDirective],
  template: `
    <div class="panel-head">
      <h1>Drivers &amp; Fuel</h1>
      <div class="segmented" role="group" aria-label="Period">
        @for (p of periods; track p) {
          <button type="button" [class.active]="period() === p" (click)="period.set(p)">{{ p }}</button>
        }
      </div>
    </div>

    @if (error()) {
      <p class="error" role="alert">Could not load the fuel summary. Retrying…</p>
    }
    @if (!summary()) {
      <p class="muted">Loading…</p>
    } @else {
      <section class="cards" aria-label="Fleet totals">
        <div class="card">
          <span>Distance</span><strong>{{ summary()!.totals.distanceKm | number: '1.0-0' }} km</strong>
        </div>
        <div class="card">
          <span>Fuel burned</span><strong>{{ summary()!.totals.fuelLitres | number: '1.0-0' }} l</strong>
        </div>
        <div class="card">
          <span>Fleet efficiency</span>
          <strong
            >{{
              summary()!.totals.kmPerLitre == null ? '–' : (summary()!.totals.kmPerLitre | number: '1.2-2')
            }}
            km/l</strong
          >
        </div>
        <div class="card">
          <span>Idling cost</span><strong>{{ summary()!.totals.idleLitres | number: '1.0-0' }} l</strong>
        </div>
      </section>

      <section class="panel">
        <h2>Driver score ranking</h2>
        <div class="scroll">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Vehicle</th>
                <th>Driver score</th>
                <th>Distance</th>
                <th>Efficiency</th>
                <th>Per 100 km</th>
                <th>Idling</th>
                <th>Fuel anomalies</th>
              </tr>
            </thead>
            <tbody>
              @for (v of ranked(); track v.vehicleId; let i = $index) {
                <tr>
                  <td>{{ i + 1 }}</td>
                  <td>
                    <a [routerLink]="['/vehicles', v.vehicleId]">{{ v.registration }}</a>
                  </td>
                  <td>
                    @if (v.driverScore == null) {
                      <span class="muted">no trips</span>
                    } @else {
                      <div class="score-bar">
                        <div [style.width.%]="v.driverScore" [class]="scoreLevel(v.driverScore)"></div>
                      </div>
                      {{ v.driverScore | number: '1.0-0' }}
                    }
                  </td>
                  <td>{{ v.distanceKm | number: '1.0-0' }} km</td>
                  <td>{{ v.kmPerLitre == null ? '–' : (v.kmPerLitre | number: '1.2-2') }} km/l</td>
                  <td>{{ v.litresPer100Km == null ? '–' : (v.litresPer100Km | number: '1.1-1') }} l</td>
                  <td>{{ v.idleLitres | number: '1.0-0' }} l ({{ v.idleHours | number: '1.1-1' }} h)</td>
                  <td>{{ v.anomalies }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel">
        <h2>Fuel efficiency and idling</h2>
        <p>{{ summary()!.idlingSummary }}</p>
        @if (summary()!.scoreEfficiencyCorrelation != null) {
          <p class="muted">
            Driver score and fuel efficiency move together across vehicle-days: correlation
            {{ summary()!.scoreEfficiencyCorrelation | number: '1.2-2' }}
            (1 = better-scored days always go further per litre, 0 = unrelated).
          </p>
        }
        <div class="charts">
          <div echarts [options]="efficiencyChart()" class="chart" aria-label="Fuel efficiency by vehicle"></div>
          <div echarts [options]="idlingChart()" class="chart" aria-label="Idling litres by vehicle"></div>
        </div>
      </section>

      @if (utilisation(); as u) {
        <section class="panel">
          <h2>Utilisation</h2>
          <p>
            The fleet was on trips for {{ u.fleet.activeHours | number: '1.0-0' }} h ({{
              u.fleet.utilisation | percent
            }}
            of {{ u.availableHoursPerVehicle | number: '1.0-0' }} working hours per vehicle),
            {{ u.fleet.idleHours | number: '1.0-0' }} h of it standing with the engine on.
            @if (u.fleet.underUsed.length) {
              Under-used: {{ u.fleet.underUsed.join(', ') }}.
            }
            @if (u.fleet.overUsed.length) {
              Over-used: {{ u.fleet.overUsed.join(', ') }}.
            }
          </p>
          <div class="scroll">
            <table>
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Utilisation</th>
                  <th>Active</th>
                  <th>Idle</th>
                  <th>Distance</th>
                  <th>Trips</th>
                  <th>Usage</th>
                </tr>
              </thead>
              <tbody>
                @for (v of u.vehicles; track v.vehicleId) {
                  <tr>
                    <td>
                      <a [routerLink]="['/vehicles', v.vehicleId]">{{ v.registration }}</a>
                    </td>
                    <td>
                      <div class="score-bar">
                        <div
                          [style.width.%]="v.utilisation * 100"
                          [class]="v.usage === 'NORMAL' ? 'ok' : 'warning'"
                        ></div>
                      </div>
                      {{ v.utilisation | percent }}
                    </td>
                    <td>{{ v.activeHours | number: '1.1-1' }} h</td>
                    <td>
                      {{ v.idleHours | number: '1.1-1' }} h{{
                        v.idleShare == null ? '' : ' (' + (v.idleShare | percent) + ')'
                      }}
                    </td>
                    <td>{{ v.distanceKm | number: '1.0-0' }} km</td>
                    <td>{{ v.trips }}</td>
                    <td>
                      @if (v.usage !== 'NORMAL') {
                        <span class="pill warning">{{ v.usage === 'UNDER_USED' ? 'Under-used' : 'Over-used' }}</span>
                      } @else {
                        Normal
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    }
  `,
})
export class DriversFuel {
  private readonly fleet = inject(FleetService);
  protected readonly periods = PERIODS;
  protected readonly period = signal<Period>('7d');
  protected readonly summary = signal<FuelSummary | undefined>(undefined);
  protected readonly utilisation = signal<Utilisation | undefined>(undefined);
  protected readonly error = signal(false);

  /** Best driver first; vehicles that did not drive go last. */
  protected readonly ranked = computed(() =>
    [...(this.summary()?.vehicles ?? [])].sort((a, b) => (b.driverScore ?? -1) - (a.driverScore ?? -1)),
  );
  protected readonly efficiencyChart = computed(() =>
    barChart(
      'Fuel efficiency (km/l)',
      this.ranked().map((v) => [v.registration, v.kmPerLitre]),
    ),
  );
  protected readonly idlingChart = computed(() =>
    barChart(
      'Fuel used idling (l)',
      this.ranked().map((v) => [v.registration, v.idleLitres]),
    ),
  );

  constructor() {
    toObservable(this.period)
      .pipe(
        switchMap((period) => {
          this.summary.set(undefined);
          return timer(0, environment.insightsRefreshMs).pipe(
            switchMap(() =>
              forkJoin([this.fleet.fuelSummary(period), this.fleet.utilisation(period)]).pipe(
                catchError(() => {
                  this.error.set(true);
                  return EMPTY;
                }),
              ),
            ),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(([summary, utilisation]) => {
        this.error.set(false);
        this.summary.set(summary);
        this.utilisation.set(utilisation);
      });
  }

  protected scoreLevel(score: number): string {
    return score >= 80 ? 'ok' : score >= 60 ? 'warning' : 'critical';
  }
}

function barChart(title: string, data: [string, number | null][]): EChartsOption {
  return {
    animation: false,
    title: { text: title, left: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
    grid: { left: 48, right: 16, top: 36, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map((d) => d[0]) },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', name: title, data: data.map((d) => d[1]), barMaxWidth: 48 }],
  };
}
