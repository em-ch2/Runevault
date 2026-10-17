import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { combineLatest, filter, map, take } from 'rxjs';

export const signedInGuard: CanActivateFn = () => {
  const auth = inject(Auth0Service);
  const router = inject(Router);
  return combineLatest([auth.isLoading$, auth.isAuthenticated$]).pipe(
    filter(([loading]) => !loading),
    take(1),
    map(([, authenticated]) => authenticated ? true : router.createUrlTree(['/login'])),
  );
};

export const signedOutGuard: CanActivateFn = () => {
  const auth = inject(Auth0Service);
  const router = inject(Router);
  return combineLatest([auth.isLoading$, auth.isAuthenticated$]).pipe(
    filter(([loading]) => !loading),
    take(1),
    map(([, authenticated]) => authenticated ? router.createUrlTree(['/']) : true),
  );
};
