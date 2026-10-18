import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { DecisionExplanation as Explanation, TransactionApiService, TransactionRecord, transactionError }
  from '../transactions/transaction-api.service';

// Fields whose change means the recorded evidence changed; anything else reuses the last explanation.
export function evidenceKey(t: TransactionRecord): string {
  return [t.transactionId, t.status, t.verifiedAt, t.geminiDecisionReason, t.geminiDecisionAt, t.approvedAt,
    t.awaitingApproval, t.bankOutcome, t.bankStatus, t.bankOutcomeAt].join('|');
}

/** Read-only: explains the recorded result as plain text and can never approve, release, or change a transfer. */
@Component({
  selector: 'app-decision-explanation',
  template: `
    <section class="decision-explanation" aria-label="Decision explanation">
      <button class="btn btn-ghost" type="button" (click)="explain()" [disabled]="loading() || !!result()">
        {{ loading() ? 'Explaining…' : 'Explain decision' }}
      </button>
      @if (result(); as explanation) {
        <div role="status">
          <p class="muted small">{{ explanation.source === 'gemini'
            ? 'AI explanation of the recorded evidence. It does not change or re-check this decision.'
            : 'AI explanation unavailable. Showing the recorded policy reason.' }}</p>
          <p>{{ explanation.summary }}</p>
          @if (explanation.keyFactors.length) {
            <ul>@for (factor of explanation.keyFactors; track $index) { <li>{{ factor }}</li> }</ul>
          }
          <p><strong>Required next step:</strong> {{ explanation.requiredNextStep }}</p>
          @if (explanation.source === 'gemini') {
            <p><strong>Recorded policy reason:</strong> {{ explanation.recordedReason }}</p>
          }
          @if (explanation.limitations.length) {
            <p class="muted small">Limitations: {{ explanation.limitations.join(' ') }}</p>
          }
        </div>
      } @else if (error(); as message) {
        <div role="alert">
          <p class="status status-bad">{{ message }}</p>
          @if (transaction().geminiDecisionExplanation; as reason) {
            <p><strong>Recorded policy reason:</strong> {{ reason }}</p>
          }
        </div>
      }
    </section>
  `,
})
export class DecisionExplanation {
  private readonly api = inject(TransactionApiService);
  readonly transaction = input.required<TransactionRecord>();
  private readonly results = signal(new Map<string, Explanation>());
  private readonly failed = signal<{ key: string; message: string } | null>(null);
  readonly loading = signal(false);
  readonly result = computed(() => this.results().get(evidenceKey(this.transaction())) ?? null);
  readonly error = computed(() => {
    const failure = this.failed();
    return failure?.key === evidenceKey(this.transaction()) ? failure.message : null;
  });

  explain(): void {
    const transaction = this.transaction();
    const key = evidenceKey(transaction);
    if (this.loading() || this.results().has(key)) return;
    this.loading.set(true);
    this.failed.set(null);
    this.api.explanation(transaction.transactionId).subscribe({
      next: (explanation) => {
        this.results.update((current) => {
          const next = new Map(current);
          next.set(key, explanation);
          if (next.size > 50) next.delete(next.keys().next().value!);
          return next;
        });
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.failed.set({ key, message: error instanceof HttpErrorResponse && error.status === 429
          ? 'Too many explanation requests. Try again in a minute.'
          : error instanceof HttpErrorResponse && error.status === 404
          ? 'This transaction is not available to your account.'
          : transactionError(error) });
        this.loading.set(false);
      },
    });
  }
}
