import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Glyph } from '../dashboard/glyph';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-signup',
  imports: [ReactiveFormsModule, RouterLink, Glyph],
  template: `
    <main class="auth-page">
      <section class="slab auth-card" aria-labelledby="signup-title">
        <div class="auth-glyphs" aria-hidden="true">
          <app-glyph [id]="2" [size]="40" /><app-glyph [id]="1" [size]="40" /><app-glyph [id]="0" [size]="40" />
        </div>
        <p class="eyebrow">Poneglyph</p>
        <h1 id="signup-title">Create account</h1>
        <p class="muted">Demo accounts live only in this browser.</p>

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <label for="signup-name">Name</label>
          <input id="signup-name" type="text" formControlName="name" autocomplete="name" maxlength="60" />
          @if (form.controls.name.touched && form.controls.name.invalid) {
            <p class="field-error">Enter your name.</p>
          }

          <label for="signup-email">Email</label>
          <input id="signup-email" type="email" formControlName="email" autocomplete="email" />
          @if (form.controls.email.touched && form.controls.email.invalid) {
            <p class="field-error">Enter a valid email.</p>
          }

          <label for="signup-password">Password</label>
          <input id="signup-password" type="password" formControlName="password" autocomplete="new-password" />
          @if (form.controls.password.touched && form.controls.password.invalid) {
            <p class="field-error">At least 8 characters.</p>
          }

          <label for="signup-confirm">Confirm password</label>
          <input id="signup-confirm" type="password" formControlName="confirm" autocomplete="new-password" />
          @if (form.controls.confirm.touched && form.controls.confirm.value !== form.controls.password.value) {
            <p class="field-error">Passwords do not match.</p>
          }

          @if (error()) {
            <p class="form-error" role="alert">{{ error() }}</p>
          }
          <button class="btn btn-primary" type="submit" [disabled]="busy()">
            {{ busy() ? 'Carving…' : 'Create account' }}
          </button>
        </form>

        <p class="auth-switch">Already have an account? <a routerLink="/login">Sign in</a></p>
      </section>
    </main>
  `,
})
export class Signup {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(60)] }),
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(8)] }),
    confirm: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    if (this.form.controls.confirm.value !== this.form.controls.password.value) {
      this.error.set('Passwords do not match.');
      return;
    }
    this.busy.set(true);
    const message = await this.auth.signUp(
      this.form.controls.name.value,
      this.form.controls.email.value,
      this.form.controls.password.value,
    );
    this.busy.set(false);
    if (message !== null) {
      this.error.set(message);
      return;
    }
    await this.router.navigateByUrl('/');
  }
}
