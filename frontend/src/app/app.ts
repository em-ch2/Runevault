import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthService } from './auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    @if (auth.loading()) {
      <main class="auth-page"><section class="slab auth-card" role="status">Checking your session…</section></main>
    } @else {
      <router-outlet />
    }
  `,
})
export class App {
  readonly auth = inject(AuthService);
}
