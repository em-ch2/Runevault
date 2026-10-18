import { computed, inject, Injectable, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { finalize, map } from 'rxjs';

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
  // Auth0 user ID, used only to explain why the dashboard locks a self-approval; the backend enforces it.
  readonly subject = toSignal(this.auth0.user$.pipe(map((profile) => profile?.sub ?? null)), { initialValue: null });
  readonly error = computed(() => this.redirectError() ?? this.sdkError()?.message ?? null);

  signIn(): void {
    if (this.busy() || this.loading()) return;
    this.redirectError.set(null);
    this.busy.set(true);
    // No forced prompt: an existing Auth0 session is reused, so a signed-in user returns without retyping.
    // Sign out ends the Auth0 session, so switching accounts still works.
    this.auth0.loginWithRedirect({ appState: { target: '/' } }).pipe(
      finalize(() => this.busy.set(false)),
    ).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not open sign in. Please try again.');
      },
    });
  }

  signUp(): void {
    if (this.busy() || this.loading()) return;
    this.redirectError.set(null);
    this.busy.set(true);
    this.auth0.loginWithRedirect({
      appState: { target: '/' },
      authorizationParams: { screen_hint: 'signup' },
    }).pipe(finalize(() => this.busy.set(false))).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not open account creation. Please try again.');
      },
    });
  }

  signOut(): void {
    if (this.busy() || this.loading()) return;
    this.redirectError.set(null);
    this.busy.set(true);
    // Return to the same root URL used by the Auth0 quickstart allowlist.
    // The signed-in guard sends the now signed-out user to /login.
    this.auth0.logout({ logoutParams: { returnTo: window.location.origin } }).pipe(
      finalize(() => this.busy.set(false)),
    ).subscribe({
      error: () => {
        this.busy.set(false);
        this.redirectError.set('Could not sign out. Please try again.');
      },
    });
  }
}
