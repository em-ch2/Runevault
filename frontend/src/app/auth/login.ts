import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Glyph } from '../dashboard/glyph';
import { OceanScene } from '../dashboard/ocean-scene';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  imports: [RouterLink, Glyph, OceanScene],
  template: `
    <main class="auth-page"><div class="auth-layout"><aside class="auth-story" (mouseenter)="ocean.pace(2.4)" (mouseleave)="ocean.pace(1)"><a class="auth-brand" routerLink="/login"><span aria-hidden="true">&#10037;</span> Runevault</a><div class="auth-story-copy"><span class="gold-rule"></span><h2>A steadier course forward.</h2><p>Financial operations, securely charted.</p></div><app-ocean-scene #ocean class="auth-ocean" [boat]="false" aria-hidden="true" /></aside>
      <section class="slab auth-card" aria-labelledby="login-title">
        <div class="auth-glyphs" aria-hidden="true">
          <app-glyph [id]="0" [size]="40" /><app-glyph [id]="1" [size]="40" /><app-glyph [id]="2" [size]="40" />
        </div>
        <p class="eyebrow">Your workspace awaits</p>
        <h1 id="login-title">Welcome back.</h1>
        <p class="muted">Continue to your financial workspace.</p>

        @if (auth.error()) {
          <p class="form-error" role="alert">{{ auth.error() }}</p>
        }
        <button class="btn btn-primary" type="button" (click)="auth.signIn()" [disabled]="auth.loading() || auth.busy()">
          {{ auth.loading() ? 'Checking session…' : auth.busy() ? 'Opening…' : 'Sign in with Auth0' }}
        </button>

        <p class="auth-security">Protected sign-in &middot; Physical approval for every transfer</p><p class="auth-switch">New here? <a routerLink="/signup">Create an account</a></p>
      </section></div>
    </main>
  `,
})
export class Login {
  readonly auth = inject(AuthService);
}
