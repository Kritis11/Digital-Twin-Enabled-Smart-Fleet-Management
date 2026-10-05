import { Routes } from '@angular/router';
import { FleetOverview } from './fleet-overview/fleet-overview';

export const routes: Routes = [
  { path: '', component: FleetOverview, title: 'Fleet Overview' },
  { path: '**', redirectTo: '' },
];
