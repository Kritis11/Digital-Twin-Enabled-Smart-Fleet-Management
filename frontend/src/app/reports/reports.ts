import { Component, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { environment } from '../../environments/environment';
import { AuthService, PAGE_ROLES } from '../auth';

interface Report {
  id: number;
  type: keyof typeof TYPES;
  format: 'PDF' | 'XLSX';
  periodFrom: string;
  periodTo: string;
  createdBy: string;
  createdAt: string;
  sizeBytes: number;
}

const TYPES = {
  FLEET_HEALTH: 'Fleet health summary',
  MAINTENANCE: 'Maintenance report',
  DRIVER_BEHAVIOUR: 'Driver behaviour report',
  FUEL: 'Fuel report',
};

/** yyyy-MM-dd for a day `daysAgo` days before today, in local time (what a date input shows). */
function day(daysAgo: number): string {
  const d = new Date(Date.now() - daysAgo * 86400000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Component({
  selector: 'app-reports',
  imports: [FormsModule, DatePipe, DecimalPipe],
  template: `
    <h1>Reports</h1>
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }

    @if (canGenerate) {
      <form class="panel filters" (ngSubmit)="generate()">
        <label>Report
          <select name="type" [(ngModel)]="type">
            @for (t of types; track t[0]) { <option [value]="t[0]">{{ t[1] }}</option> }
          </select>
        </label>
        <label>From <input name="from" type="date" [(ngModel)]="from" [max]="to()" required /></label>
        <label>To <input name="to" type="date" [(ngModel)]="to" [min]="from()" required /></label>
        <label>Format
          <select name="format" [(ngModel)]="format">
            <option value="PDF">PDF</option>
            <option value="XLSX">Excel</option>
          </select>
        </label>
        <button type="submit" class="primary" [disabled]="busy() || !from() || !to()">
          {{ busy() ? 'Generating…' : 'Generate' }}
        </button>
      </form>
    }

    <section class="panel">
      <h2>Past reports</h2>
      @if (!reports().length) {
        <p class="muted">No reports have been generated yet.</p>
      } @else {
        <div class="scroll">
          <table>
            <thead>
              <tr><th>Generated</th><th>Report</th><th>Period</th><th>Format</th><th>Size</th><th>By</th><th></th></tr>
            </thead>
            <tbody>
              @for (r of reports(); track r.id) {
                <tr>
                  <td class="nowrap">{{ r.createdAt | date: 'd MMM y HH:mm' }}</td>
                  <td>{{ titles[r.type] }}</td>
                  <td class="nowrap">{{ r.periodFrom | date: 'd MMM' }} – {{ r.periodTo | date: 'd MMM y' }}</td>
                  <td>{{ r.format === 'XLSX' ? 'Excel' : 'PDF' }}</td>
                  <td class="nowrap">{{ r.sizeBytes / 1024 | number: '1.0-0' }} kB</td>
                  <td>{{ r.createdBy }}</td>
                  <td><button type="button" (click)="download(r)">Download</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class Reports {
  private readonly http = inject(HttpClient);
  private readonly api = `${environment.apiBaseUrl}/api/reports`;

  protected readonly canGenerate = inject(AuthService).hasRole(...PAGE_ROLES.planning);
  protected readonly titles = TYPES;
  protected readonly types = Object.entries(TYPES);
  protected readonly reports = signal<Report[]>([]);
  protected readonly type = signal<Report['type']>('FLEET_HEALTH');
  protected readonly format = signal<Report['format']>('PDF');
  protected readonly from = signal(day(7));
  protected readonly to = signal(day(0));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected generate(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http
      .post<Report>(this.api, { type: this.type(), format: this.format(), from: this.from(), to: this.to() })
      .subscribe({
        next: (report) => {
          this.busy.set(false);
          this.load();
          this.download(report);
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(e.error?.message ?? 'The report could not be generated.');
        },
      });
  }

  /** Fetched with the access token, then handed to the browser as a file: a plain link could not send the token. */
  protected download(report: Report): void {
    this.http.get(`${this.api}/${report.id}/download`, { responseType: 'blob' }).subscribe({
      next: (file) => {
        const link = document.createElement('a');
        link.href = URL.createObjectURL(file);
        link.download = `${report.type.toLowerCase()}_${report.periodFrom}_${report.periodTo}.${report.format.toLowerCase()}`;
        link.click();
        URL.revokeObjectURL(link.href);
      },
      error: () => this.error.set('The file could not be downloaded.'),
    });
  }

  private load(): void {
    this.http.get<Report[]>(this.api).subscribe((list) => this.reports.set(list));
  }
}
