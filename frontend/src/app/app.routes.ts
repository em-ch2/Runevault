import { Routes } from '@angular/router';
import { signedInGuard, signedOutGuard } from './auth/auth.guard';
import { Login } from './auth/login';
import { Signup } from './auth/signup';
import { Dashboard } from './dashboard/dashboard';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [signedOutGuard], title: 'Sign in · Poneglyph' },
  { path: 'signup', component: Signup, canActivate: [signedOutGuard], title: 'Create account · Poneglyph' },
  { path: '', component: Dashboard, canActivate: [signedInGuard], title: 'Poneglyph' },
  { path: '**', redirectTo: '' },
];
