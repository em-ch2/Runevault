import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Glyph } from '../dashboard/glyph';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  imports: [RouterLink, Glyph],
  template: `
    <main class="auth-page">
      <section class="slab auth-card" aria-labelledby="login-title">
        <div class="auth-glyphs" aria-hidden="true">
          <app-glyph [id]="0" [size]="40" /><app-glyph [id]="1" [size]="40" /><app-glyph [id]="2" [size]="40" />
        </div>
        <p class="eyebrow">Poneglyph</p>
        <h1 id="login-title">Sign in</h1>
        <p class="muted">Watch transfers wait for the stone.</p>

        @if (auth.error()) {
          <p class="form-error" role="alert">{{ auth.error() }}</p>
        }
        <button class="btn btn-primary" type="button" (click)="auth.signIn()" [disabled]="auth.loading() || auth.busy()">
          {{ auth.loading() ? 'Checking session…' : auth.busy() ? 'Opening…' : 'Sign in with Auth0' }}
        </button>

        <p class="auth-switch">New here? <a routerLink="/signup">Create an account</a></p>
      </section>
    </main>
  `,
})
export class Login {
  readonly auth = inject(AuthService);
}
