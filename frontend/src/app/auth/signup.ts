import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Glyph } from '../dashboard/glyph';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-signup',
  imports: [RouterLink, Glyph],
  template: `
    <main class="auth-page">
      <section class="slab auth-card" aria-labelledby="signup-title">
        <div class="auth-glyphs" aria-hidden="true">
          <app-glyph [id]="2" [size]="40" /><app-glyph [id]="1" [size]="40" /><app-glyph [id]="0" [size]="40" />
        </div>
        <p class="eyebrow">Poneglyph</p>
        <h1 id="signup-title">Create account</h1>
        <p class="muted">Create your account securely with Auth0.</p>

        @if (auth.error()) {
          <p class="form-error" role="alert">{{ auth.error() }}</p>
        }
        <button class="btn btn-primary" type="button" (click)="auth.signUp()" [disabled]="auth.loading() || auth.busy()">
          {{ auth.loading() ? 'Checking session…' : auth.busy() ? 'Opening…' : 'Create account with Auth0' }}
        </button>

        <p class="auth-switch">Already have an account? <a routerLink="/login">Sign in</a></p>
      </section>
    </main>
  `,
})
export class Signup {
  readonly auth = inject(AuthService);
}
