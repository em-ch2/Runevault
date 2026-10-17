import { TestBed } from '@angular/core/testing';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  const profile = new BehaviorSubject<{ name: string; email: string } | null>(null);
  const loading = new BehaviorSubject(false);
  const authenticated = new BehaviorSubject(false);
  const sdkError = new BehaviorSubject<Error | null>(null);
  const sdk = {
    user$: profile.asObservable(),
    isLoading$: loading.asObservable(),
    isAuthenticated$: authenticated.asObservable(),
    error$: sdkError.asObservable(),
    loginWithRedirect: vi.fn(() => of(undefined)),
    logout: vi.fn(() => of(undefined)),
  };

  beforeEach(() => {
    profile.next(null);
    loading.next(false);
    authenticated.next(false);
    sdkError.next(null);
    sdk.loginWithRedirect.mockReset().mockReturnValue(of(undefined));
    sdk.logout.mockReset().mockReturnValue(of(undefined));
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [{ provide: Auth0Service, useValue: sdk }] });
  });

  it('reflects verified SDK identity and loading state without browser accounts', () => {
    const auth = TestBed.inject(AuthService);
    expect(auth.user()).toBeNull();
    loading.next(true);
    expect(auth.loading()).toBe(true);
    loading.next(false);
    authenticated.next(true);
    profile.next({ name: 'Ada', email: 'ada@example.com' });
    expect(auth.authenticated()).toBe(true);
    expect(auth.user()).toEqual({ name: 'Ada', email: 'ada@example.com' });
    expect(localStorage.getItem('poneglyph.users')).toBeNull();
    expect(localStorage.getItem('poneglyph.session')).toBeNull();
  });

  it('starts hosted login, hosted signup, and Auth0 logout', () => {
    const auth = TestBed.inject(AuthService);
    auth.signIn();
    expect(sdk.loginWithRedirect).toHaveBeenCalledWith({
      appState: { target: '/' }, authorizationParams: { prompt: 'login', max_age: 0 },
    });
    auth.signUp();
    expect(sdk.loginWithRedirect).toHaveBeenCalledWith({
      appState: { target: '/' }, authorizationParams: { screen_hint: 'signup', prompt: 'login', max_age: 0 },
    });
    auth.signOut();
    expect(sdk.logout).toHaveBeenCalledWith({
      logoutParams: { returnTo: window.location.origin },
    });
  });

  it('shows authentication and redirect errors', () => {
    const auth = TestBed.inject(AuthService);
    sdkError.next(new Error('Login callback failed'));
    expect(auth.error()).toBe('Login callback failed');
    sdk.loginWithRedirect.mockReturnValueOnce(throwError(() => new Error('Network unavailable')));
    auth.signIn();
    expect(auth.error()).toBe('Could not open sign in. Please try again.');
    expect(auth.busy()).toBe(false);
  });

  it('allows retrying logout after a redirect error', () => {
    const auth = TestBed.inject(AuthService);
    sdk.logout.mockReturnValueOnce(throwError(() => new Error('Network unavailable')));
    auth.signOut();
    expect(auth.error()).toContain('Could not sign out');
    expect(auth.busy()).toBe(false);
    auth.signOut();
    expect(sdk.logout).toHaveBeenCalledTimes(2);
    expect(auth.error()).toBeNull();
  });
});
