import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../auth';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <form class="panel login" (ngSubmit)="submit()">
      <h1>Fleet Twin</h1>
      <label>Username
        <input name="username" [(ngModel)]="username" autocomplete="username" required autofocus />
      </label>
      <label>Password
        <input name="password" type="password" [(ngModel)]="password" autocomplete="current-password" required />
      </label>
      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
      <button type="submit" class="primary" [disabled]="busy() || !username() || !password()">
        {{ busy() ? 'Signing in…' : 'Sign in' }}
      </button>
    </form>
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.auth.loggedIn()) this.router.navigate(['/']);
  }

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.username(), this.password()).subscribe({
      next: () => this.router.navigate(['/']),
      error: (e) => {
        this.busy.set(false);
        this.error.set(e.status === 401 ? 'Wrong username or password.' : 'Cannot reach the server. Try again.');
      },
    });
  }
}
