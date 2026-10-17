import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, interval } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { PendingTransactionResponse, TransactionApiService, TransactionRecord, transactionError } from '../transactions/transaction-api.service';
import { FeedService } from '../feed/feed.service';
import { MockScenario } from '../feed/mock-feed';
import { GLYPH_NAMES, Glyph } from './glyph';
import { Stage, TransferStore } from './transfer-store';

const STAGE_ORDER: Stage[] = ['received', 'sealed', 'verified', 'executed'];

@Component({
  selector: 'app-dashboard',
  imports: [Glyph, ReactiveFormsModule],
  templateUrl: './dashboard.html',
})
export class Dashboard implements OnInit, OnDestroy {
  readonly auth = inject(AuthService);
  readonly feed = inject(FeedService);
  readonly store = inject(TransferStore);
  readonly api = inject(TransactionApiService);
  readonly mode = signal<'live' | 'demo'>('live');
  readonly records = signal<TransactionRecord[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly created = signal<PendingTransactionResponse | null>(null);
  readonly loadingRecords = signal(false);
  readonly creating = signal(false);
  readonly apiError = signal<string | null>(null);
  readonly selected = computed(() => this.records().find((record) => record.transactionId === this.selectedId()) ?? null);
  readonly createdForSelection = computed(() => this.created()?.tx_id === this.selectedId() ? this.created() : null);
  readonly pendingForm = new FormGroup({
    device: new FormControl('slab-01', { nonNullable: true, validators: [Validators.required, Validators.pattern('[A-Za-z0-9_-]{1,64}')] }),
    amount: new FormControl(50000, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.max(100000000)] }),
    dest: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern('[A-Za-z0-9_-]{1,64}')] }),
    glyphs: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern('[0-2](-[0-2]){0,63}')] }),
  });
  private poll: Subscription | null = null;
  private listRequest: Subscription | null = null;

  readonly glyphNames = GLYPH_NAMES;
  readonly stages: { key: Stage; label: string }[] = [
    { key: 'received', label: 'Received' },
    { key: 'sealed', label: 'Sealed by tablet' },
    { key: 'verified', label: 'Verified' },
    { key: 'executed', label: 'Executed' },
  ];

  readonly transfer = computed(() => this.store.state().transfer);
  readonly tablet = computed(() => this.store.state().tablet);
  readonly log = computed(() => this.store.state().log);

  readonly statusText = computed(() => {
    const t = this.transfer();
    if (t === null) return 'No pending transfer';
    if (t.stage === 'received') return 'Awaiting tablet';
    if (t.stage === 'sealed') return 'Sealed, verifying signature';
    if (t.stage === 'verified') return 'Physical approval verified';
    if (t.stage === 'executed') return 'Executed';
    return 'Rejected, nothing moved';
  });

  readonly glyphIds = computed(() => {
    const t = this.transfer();
    if (t === null || t.executed === null) return [];
    return t.executed.glyphs.split('-').map((g) => Number(g));
  });

  readonly feedLabel = computed(() => {
    const c = this.feed.connection();
    if (c === 'mock') return 'Mock feed';
    if (c === 'open') return 'Live feed';
    if (c === 'connecting') return 'Connecting…';
    if (c === 'closed') return 'Feed offline, retrying';
    return 'Feed idle';
  });

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    this.listRequest?.unsubscribe();
    this.feed.stop();
  }

  switchMode(mode: 'live' | 'demo'): void {
    if (this.mode() === mode) return;
    if (mode === 'demo' && !this.feed.isMock) return;
    this.mode.set(mode);
    if (mode === 'demo') {
      this.poll?.unsubscribe();
      this.poll = null;
      this.listRequest?.unsubscribe();
      this.listRequest = null;
      this.feed.start();
    } else {
      this.feed.stop();
      this.refresh();
    }
  }

  refresh(polling = false): void {
    if (this.mode() !== 'live' || this.listRequest) return;
    if (!polling) this.loadingRecords.set(true);
    const request = this.api.list().subscribe({
      next: (page) => {
        this.records.set(page.content);
        if (this.selectedId() === null && page.content.length) this.selectedId.set(page.content[0].transactionId);
        this.apiError.set(null);
        this.loadingRecords.set(false);
        this.listRequest = null;
        if (this.awaitingApproval()) this.startPolling();
        else this.stopPolling();
      },
      error: (error: unknown) => {
        this.apiError.set(transactionError(error));
        this.loadingRecords.set(false);
        this.listRequest = null;
        if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) this.stopPolling();
      },
    });
    this.listRequest = request.closed ? null : request;
  }

  createPending(): void {
    this.pendingForm.markAllAsTouched();
    const amount = this.pendingForm.controls.amount.value;
    if (this.pendingForm.invalid || !Number.isInteger(amount)) {
      this.apiError.set('Enter a whole-cent amount, destination, device, and glyph sequence using 0-2.');
      return;
    }
    this.creating.set(true);
    this.apiError.set(null);
    this.api.create(this.pendingForm.getRawValue()).subscribe({
      next: (created) => {
        this.created.set(created);
        this.selectedId.set(created.tx_id);
        this.creating.set(false);
        this.startPolling();
        this.refresh();
      },
      error: (error: unknown) => {
        this.apiError.set(transactionError(error));
        this.creating.set(false);
      },
    });
  }

  select(id: string): void {
    this.selectedId.set(id);
    if (this.awaitingApproval()) this.startPolling();
    else this.stopPolling();
  }

  approvalExpired(transaction: TransactionRecord | null): boolean {
    const expiresAt = this.createdForSelection()?.expires_at
      ?? (transaction ? Date.parse(transaction.createdAt) / 1000 + 120 : 0);
    return expiresAt > 0 && Date.now() / 1000 >= expiresAt;
  }

  private awaitingApproval(): boolean {
    const transaction = this.selected();
    // Keep polling while the bank transfer and Gemini audit run.
    if (transaction?.status === 'EXECUTING') return true;
    return (transaction?.status === 'PENDING' || (transaction === null && this.createdForSelection() !== null))
      && !this.approvalExpired(transaction);
  }

  private startPolling(): void {
    if (this.poll || this.mode() !== 'live') return;
    this.poll = interval(3000).subscribe(() => this.refresh(true));
  }

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = null;
  }

  replay(scenario: MockScenario): void {
    this.feed.playMock(scenario);
  }

  signOut(): void {
    this.auth.signOut();
  }

  stepState(key: Stage): 'done' | 'active' | 'waiting' | 'failed' {
    const t = this.transfer();
    if (t === null) return 'waiting';
    const index = STAGE_ORDER.indexOf(key);
    if (t.stage === 'rejected' && t.rejected !== null) {
      const failedIndex = STAGE_ORDER.indexOf(t.rejected.failed_stage);
      if (index < failedIndex) return 'done';
      if (index === failedIndex) return 'failed';
      return 'waiting';
    }
    const currentIndex = STAGE_ORDER.indexOf(t.stage);
    if (index <= currentIndex) return 'done';
    if (index === currentIndex + 1) return 'active';
    return 'waiting';
  }

  money(cents: number): string {
    return (cents / 100).toLocaleString('en-US', { style: 'currency', currency: 'USD' });
  }

  expiry(seconds: number): string {
    return this.time(new Date(seconds * 1000).toISOString());
  }

  time(iso: string): string {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    });
  }
}
