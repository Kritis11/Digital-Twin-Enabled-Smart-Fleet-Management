import { Routes } from '@angular/router';
import { PAGE_ROLES, authGuard } from './auth';

// Lazy so Leaflet and the chart code stay out of the initial bundle.
// Every page but the login needs a session; `data.roles` narrows it further (see PAGE_ROLES).
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./login/login').then((m) => m.Login), title: 'Sign in' },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./fleet-overview/fleet-overview').then((m) => m.FleetOverview), title: 'Fleet Overview' },
      { path: 'vehicles/:id', loadComponent: () => import('./vehicle-detail/vehicle-detail').then((m) => m.VehicleDetail), title: 'Vehicle' },
      { path: 'planner', loadComponent: () => import('./planner/planner').then((m) => m.Planner), title: 'Maintenance Planner' },
      { path: 'alerts', loadComponent: () => import('./alerts/alerts').then((m) => m.Alerts), title: 'Alerts' },
      { path: 'drivers-fuel', data: { roles: PAGE_ROLES.fleetAnalysis }, loadComponent: () => import('./drivers-fuel/drivers-fuel').then((m) => m.DriversFuel), title: 'Drivers & Fuel' },
      { path: 'routes', data: { roles: PAGE_ROLES.planning }, loadComponent: () => import('./route-planner/route-planner').then((m) => m.RoutePlanner), title: 'Route Planner' },
      { path: 'reports', data: { roles: PAGE_ROLES.fleetAnalysis }, loadComponent: () => import('./reports/reports').then((m) => m.Reports), title: 'Reports' },
      { path: 'users', data: { roles: PAGE_ROLES.admin }, loadComponent: () => import('./users/users').then((m) => m.Users), title: 'User Management' },
    ],
  },
  { path: '**', redirectTo: '' },
];
