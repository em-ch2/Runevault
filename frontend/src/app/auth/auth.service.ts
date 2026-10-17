import { computed, inject, Injectable, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { map } from 'rxjs';

export interface AuthUser {
  name: string;
  email: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly auth0 = inject(Auth0Service);
  private readonly redirectError = signal<string | null>(null);
  readonly busy = signal(false);
  private readonly sdkError = toSignal(this.auth0.error$, { initialValue: null });

  readonly loading = toSignal(this.auth0.isLoading$, { initialValue: true });
  readonly authenticated = toSignal(this.auth0.isAuthenticated$, { initialValue: false });
  readonly user = toSignal(
    this.auth0.user$.pipe(
      map((profile): AuthUser | null => profile
        ? { name: profile.name ?? profile.nickname ?? profile.email ?? 'Member', email: profile.email ?? '' }
        : null),
    ),
    { initialValue: null },
  );
  readonly error = computed(() => this.redirectError() ?? this.sdkError()?.message ?? null);

  signIn(): void {
    this.redirectError.set(null);
    this.busy.set(true);
    this.auth0.loginWithRedirect({ appState: { target: '/' } }).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not open sign in. Please try again.');
      },
    });
  }

  signUp(): void {
    this.redirectError.set(null);
    this.busy.set(true);
    this.auth0.loginWithRedirect({
      appState: { target: '/' },
      authorizationParams: { screen_hint: 'signup' },
    }).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not open account creation. Please try again.');
      },
    });
  }

  signOut(): void {
    this.redirectError.set(null);
    this.busy.set(true);
    this.auth0.logout({ logoutParams: { returnTo: window.location.origin + '/login' } }).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not sign out. Please try again.');
      },
    });
  }
}
