import { Routes } from '@angular/router';
import { signedInGuard, signedOutGuard } from './auth/auth.guard';
import { Login } from './auth/login';
import { Signup } from './auth/signup';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [signedOutGuard], title: 'Sign in · Poneglyph' },
  { path: 'signup', component: Signup, canActivate: [signedOutGuard], title: 'Create account · Poneglyph' },
  { path: '', loadComponent: () => import('./dashboard/dashboard').then((module) => module.Dashboard),
    canActivate: [signedInGuard], title: 'Poneglyph' },
  { path: '**', redirectTo: '' },
];
