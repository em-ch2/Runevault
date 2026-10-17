import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const signedInGuard: CanActivateFn = () => {
  if (inject(AuthService).user() !== null) return true;
  return inject(Router).createUrlTree(['/login']);
};

export const signedOutGuard: CanActivateFn = () => {
  if (inject(AuthService).user() === null) return true;
  return inject(Router).createUrlTree(['/']);
};
