import { Routes } from '@angular/router';

// Lazy so Leaflet and the chart code stay out of the initial bundle.
export const routes: Routes = [
  { path: '', loadComponent: () => import('./fleet-overview/fleet-overview').then((m) => m.FleetOverview), title: 'Fleet Overview' },
  { path: 'vehicles/:id', loadComponent: () => import('./vehicle-detail/vehicle-detail').then((m) => m.VehicleDetail), title: 'Vehicle' },
  { path: 'alerts', loadComponent: () => import('./alerts/alerts').then((m) => m.Alerts), title: 'Alerts' },
  { path: '**', redirectTo: '' },
];
