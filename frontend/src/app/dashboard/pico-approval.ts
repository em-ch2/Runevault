import { Component, OnDestroy, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TransactionApiService } from '../transactions/transaction-api.service';
import { GLYPH_NAMES, Glyph } from './glyph';
import { PicoSerial } from './pico-serial';

// Replaces the terminal bridge: hands the pending transfer to the USB-connected Pico, shows each glyph as it is
// pressed, and relays the Pico's signed approval to the backend under the signed-in user's session.
@Component({
  selector: 'app-pico-approval',
  imports: [Glyph],
  template: `
    <section class="pico-link" aria-live="polite">
      @switch (pico.state()) {
        @case ('unsupported') {
          <p class="small muted">A live Pico connection needs Chrome or Edge. The terminal bridge still works in other browsers.</p>
        }
        @case ('disconnected') {
          <button class="btn btn-ghost" type="button" (click)="pico.connect(true)">Connect Pico</button>
          <p class="small muted">Plug in the Pico and connect it once. The dashboard reconnects to it after that.</p>
        }
        @case ('connecting') { <p class="small muted">Connecting to the Pico…</p> }
        @case ('ready') {
          <p class="pico-status"><span class="dot"></span>Pico connected · {{ message() }}</p>
          @if (txId()) {
            <div class="glyph-row pico-presses" aria-label="Glyphs pressed on the Pico">
              @for (slot of slots(); track $index) {
                <figure [class.press-wrong]="slot.wrong" [class.press-empty]="slot.glyph === null">
                  @if (slot.glyph !== null) {
                    <app-glyph [id]="slot.glyph" [size]="36" /><figcaption>{{ names[slot.glyph] }}</figcaption>
                  } @else {
                    <span aria-hidden="true">&#9671;</span><figcaption>&nbsp;</figcaption>
                  }
                </figure>
              }
            </div>
          }
        }
      }
      @if (pico.error() ?? problem(); as text) { <p class="form-error" role="alert">{{ text }}</p> }
    </section>
  `,
})
export class PicoApproval implements OnDestroy {
  readonly pico = inject(PicoSerial);
  private readonly api = inject(TransactionApiService);
  readonly txId = input<string | null>(null);
  readonly amount = input<number | null>(null);
  readonly dest = input<string | null>(null);
  readonly expected = input<number[]>([]);
  readonly expiresAt = input<number | null>(null);
  readonly authorized = output<void>();
  readonly names = GLYPH_NAMES;
  readonly phase = signal<'idle' | 'entering' | 'submitting' | 'retry' | 'done'>('idle');
  readonly problem = signal<string | null>(null);
  private sentFor: string | null = null;
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  readonly message = computed(() => {
    switch (this.phase()) {
      case 'entering': return 'press the glyphs, then Enter';
      case 'retry': return 'wrong glyphs, try again';
      case 'submitting': return 'checking your approval…';
      case 'done': return 'approval accepted';
      default: return 'waiting for a transfer';
    }
  });

  readonly slots = computed(() => {
    const presses = this.pico.presses();
    const expected = this.expected();
    return Array.from({ length: Math.max(expected.length, presses.length) }, (_, i) => ({
      glyph: presses[i] ?? null,
      wrong: presses[i] !== undefined && presses[i] !== expected[i],
    }));
  });

  // Sends each new pending transfer to the Pico once, as soon as both are available.
  private readonly handOff = effect(() => {
    const txId = this.txId(), amount = this.amount(), dest = this.dest();
    if (this.pico.state() !== 'ready' || !txId || amount === null || !dest) return;
    untracked(() => {
      if (this.sentFor === txId) return;
      this.sentFor = txId;
      this.problem.set(null);
      this.armExpiry();
      this.sign(txId, amount, dest);
    });
  });

  constructor() {
    void this.pico.connect(false);
  }

  ngOnDestroy(): void {
    if (this.expiryTimer) clearTimeout(this.expiryTimer);
  }

  private sign(txId: string, amount: number, dest: string): void {
    this.phase.set(this.phase() === 'retry' ? 'retry' : 'entering');
    this.pico.sign(txId, amount, dest).then(
      (line) => this.submit(txId, amount, dest, line),
      (reason: Error) => {
        if (this.sentFor !== txId || this.phase() === 'done') return;
        this.phase.set('idle');
        // After an unplug, hand the same transfer over again once the Pico reconnects.
        if (reason.message === 'disconnected') this.sentFor = null;
      },
    );
  }

  private submit(txId: string, amount: number, dest: string, line: string): void {
    this.phase.set('submitting');
    this.api.authorize(txId, line).subscribe({
      next: () => {
        this.phase.set('done');
        if (this.expiryTimer) clearTimeout(this.expiryTimer);
        this.authorized.emit();
      },
      error: (error: unknown) => {
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        if (status === 403 && error instanceof HttpErrorResponse && error.error?.error === 'Incorrect glyphs') {
          // Wrong glyphs leave the transfer pending, so give the Pico the same transfer again.
          this.phase.set('retry');
          this.sign(txId, amount, dest);
          return;
        }
        this.phase.set('idle');
        this.problem.set(status === 423 ? 'The Pico is locked after too many wrong attempts. An operator must unlock it.'
          : status === 409 ? 'This approval window has expired. Start a new request.'
          : status === 401 ? 'The backend did not accept the Pico’s signature. Check that RUNEVAULT_PICO_KEY matches the Pico’s key.'
          : 'Could not submit the approval. Check the transaction status before trying again.');
      },
    });
  }

  private armExpiry(): void {
    if (this.expiryTimer) clearTimeout(this.expiryTimer);
    const expiresAt = this.expiresAt();
    if (!expiresAt) return;
    this.expiryTimer = setTimeout(() => {
      if (this.phase() === 'done' || this.phase() === 'submitting') return;
      this.phase.set('idle');
      void this.pico.cancel();
    }, Math.max(0, expiresAt * 1000 - Date.now()));
  }
}
