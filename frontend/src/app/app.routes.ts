import { Routes } from '@angular/router';

// Lazy so Leaflet and the chart code stay out of the initial bundle.
export const routes: Routes = [
  { path: '', loadComponent: () => import('./fleet-overview/fleet-overview').then((m) => m.FleetOverview), title: 'Fleet Overview' },
  { path: 'vehicles/:id', loadComponent: () => import('./vehicle-detail/vehicle-detail').then((m) => m.VehicleDetail), title: 'Vehicle' },
  { path: 'planner', loadComponent: () => import('./planner/planner').then((m) => m.Planner), title: 'Maintenance Planner' },
  { path: 'drivers-fuel', loadComponent: () => import('./drivers-fuel/drivers-fuel').then((m) => m.DriversFuel), title: 'Drivers & Fuel' },
  { path: 'routes', loadComponent: () => import('./route-planner/route-planner').then((m) => m.RoutePlanner), title: 'Route Planner' },
  { path: 'alerts', loadComponent: () => import('./alerts/alerts').then((m) => m.Alerts), title: 'Alerts' },
  { path: '**', redirectTo: '' },
];
