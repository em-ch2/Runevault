import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { BehaviorSubject, firstValueFrom, Observable } from 'rxjs';
import { signedInGuard, signedOutGuard } from './auth.guard';

describe('authentication route guards', () => {
  const loading = new BehaviorSubject(true);
  const authenticated = new BehaviorSubject(false);

  beforeEach(() => {
    loading.next(true);
    authenticated.next(false);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Auth0Service, useValue: {
        isLoading$: loading.asObservable(), isAuthenticated$: authenticated.asObservable(),
      } }],
    });
  });

  function check(guard: typeof signedInGuard): Promise<boolean | UrlTree> {
    const result = TestBed.runInInjectionContext(() => guard(
      {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot,
    ));
    return firstValueFrom(result as Observable<boolean | UrlTree>);
  }

  it('waits for SDK initialization before protecting the dashboard', async () => {
    const decision = check(signedInGuard);
    loading.next(false);
    const result = await decision;
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login');
  });

  it('allows authenticated users and redirects them away from sign-in', async () => {
    authenticated.next(true);
    loading.next(false);
    expect(await check(signedInGuard)).toBe(true);
    const result = await check(signedOutGuard);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/');
  });
});
