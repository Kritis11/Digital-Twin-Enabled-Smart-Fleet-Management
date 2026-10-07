import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, finalize, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { environment } from '../environments/environment';

export type Role = 'ADMIN' | 'FLEET_MANAGER' | 'TECHNICIAN' | 'VIEWER';

export interface Session {
  accessToken: string;
  refreshToken: string;
  username: string;
  role: Role;
}

const STORAGE_KEY = 'fleet-twin-session';
const AUTH_URL = `${environment.apiBaseUrl}/api/auth`;

/** Who may open which page. The backend enforces the same rules (SecurityConfig); this only shapes the menu. */
export const PAGE_ROLES = {
  fleetAnalysis: ['ADMIN', 'FLEET_MANAGER', 'VIEWER'],
  planning: ['ADMIN', 'FLEET_MANAGER'],
  admin: ['ADMIN'],
} satisfies Record<string, Role[]>;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private refreshing?: Observable<Session>;

  readonly session = signal<Session | null>(readStored());
  readonly loggedIn = computed(() => this.session() !== null);
  /** Viewers see everything their pages show but cannot change it. */
  readonly canWrite = computed(() => this.hasRole('ADMIN', 'FLEET_MANAGER', 'TECHNICIAN'));

  hasRole(...roles: Role[]): boolean {
    const role = this.session()?.role;
    return role != null && roles.includes(role);
  }

  login(username: string, password: string): Observable<Session> {
    return this.http.post<Session>(`${AUTH_URL}/login`, { username, password }).pipe(tap((s) => this.store(s)));
  }

  /** Trades the refresh token for a new pair. Calls made while one is in flight share it. */
  refresh(): Observable<Session> {
    this.refreshing ??= this.http
      .post<Session>(`${AUTH_URL}/refresh`, { refreshToken: this.session()?.refreshToken })
      .pipe(
        tap((s) => this.store(s)),
        finalize(() => (this.refreshing = undefined)),
        shareReplay(1),
      );
    return this.refreshing;
  }

  logout(): void {
    // Best effort: the server ends the refresh token; the local session ends either way.
    this.http.post(`${AUTH_URL}/logout`, null).subscribe({ error: () => undefined });
    this.end();
  }

  /** Forgets the session and shows the login page. */
  end(): void {
    this.store(null);
    this.router.navigate(['/login']);
  }

  private store(session: Session | null): void {
    this.session.set(session);
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else localStorage.removeItem(STORAGE_KEY);
  }
}

function readStored(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
  } catch {
    return null;
  }
}

/** Adds the access token; on a 401 refreshes it once and repeats the request. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (req.url.startsWith(`${AUTH_URL}/login`) || req.url.startsWith(`${AUTH_URL}/refresh`)) return next(req);
  const withToken = () => {
    const token = auth.session()?.accessToken;
    return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  };
  return next(withToken()).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401 || !auth.loggedIn()) return throwError(() => error);
      return auth.refresh().pipe(
        catchError((refreshError) => {
          auth.end();
          return throwError(() => refreshError);
        }),
        switchMap(() => next(withToken())),
      );
    }),
  );
};

/** Sends anonymous visitors to /login and anyone without one of the route's `data.roles` to the overview. */
export const authGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.loggedIn()) return router.createUrlTree(['/login']);
  const roles = route.data['roles'] as Role[] | undefined;
  return !roles || auth.hasRole(...roles) || router.createUrlTree(['/']);
};
