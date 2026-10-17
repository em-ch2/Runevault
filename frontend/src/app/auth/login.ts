import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Glyph } from '../dashboard/glyph';
import { AuthService, DEMO_EMAIL, DEMO_PASSWORD } from './auth.service';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, Glyph],
  template: `
    <main class="auth-page">
      <section class="slab auth-card" aria-labelledby="login-title">
        <div class="auth-glyphs" aria-hidden="true">
          <app-glyph [id]="0" [size]="40" /><app-glyph [id]="1" [size]="40" /><app-glyph [id]="2" [size]="40" />
        </div>
        <p class="eyebrow">Poneglyph</p>
        <h1 id="login-title">Sign in</h1>
        <p class="muted">Watch transfers wait for the stone.</p>

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <label for="login-email">Email</label>
          <input id="login-email" type="email" formControlName="email" autocomplete="email" />
          @if (form.controls.email.touched && form.controls.email.invalid) {
            <p class="field-error">Enter a valid email.</p>
          }

          <label for="login-password">Password</label>
          <input id="login-password" type="password" formControlName="password" autocomplete="current-password" />
          @if (form.controls.password.touched && form.controls.password.invalid) {
            <p class="field-error">Enter your password.</p>
          }

          @if (error()) {
            <p class="form-error" role="alert">{{ error() }}</p>
          }
          <button class="btn btn-primary" type="submit" [disabled]="busy()">
            {{ busy() ? 'Opening…' : 'Sign in' }}
          </button>
        </form>

        <p class="auth-switch">New here? <a routerLink="/signup">Create an account</a></p>
        <p class="demo-hint">
          Demo account: <code>{{ demoEmail }}</code> / <code>{{ demoPassword }}</code>
          <button class="btn-link" type="button" (click)="fillDemo()">Use it</button>
        </p>
      </section>
    </main>
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly demoEmail = DEMO_EMAIL;
  readonly demoPassword = DEMO_PASSWORD;
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  fillDemo(): void {
    this.form.setValue({ email: DEMO_EMAIL, password: DEMO_PASSWORD });
    this.error.set(null);
  }

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.busy.set(true);
    const message = await this.auth.signIn(this.form.controls.email.value, this.form.controls.password.value);
    this.busy.set(false);
    if (message !== null) {
      this.error.set(message);
      return;
    }
    await this.router.navigateByUrl('/');
  }
}
