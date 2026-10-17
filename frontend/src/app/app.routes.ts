import { Routes } from '@angular/router';
import { signedInGuard, signedOutGuard } from './auth/auth.guard';
import { Login } from './auth/login';
import { Signup } from './auth/signup';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [signedOutGuard], title: 'Sign in · Runevault' },
  { path: 'signup', component: Signup, canActivate: [signedOutGuard], title: 'Create account · Runevault' },
  { path: '', loadComponent: () => import('./dashboard/dashboard').then((module) => module.Dashboard),
    canActivate: [signedInGuard], title: 'Runevault' },
  { path: '**', redirectTo: '' },
];
