import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, interval } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { PendingTransactionResponse, PipelineMetrics, TransactionApiService, TransactionRecord, transactionError } from '../transactions/transaction-api.service';
import { TransferTransportEvent } from '../feed/feed-event';
import { ApprovalsChart } from './approvals-chart';
import { Gauge } from './gauge';
import { mergeLiveEvent } from './live-merge';
import { FeedService } from '../feed/feed.service';
import { MockScenario } from '../feed/mock-feed';
import { GLYPH_NAMES, Glyph } from './glyph';
import { Stage, TransferStore } from './transfer-store';

const STAGE_ORDER: Stage[] = ['received', 'sealed', 'verified', 'executed'];

@Component({
  selector: 'app-dashboard',
  imports: [ApprovalsChart, Gauge, Glyph, ReactiveFormsModule],
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
  readonly pendingGlyphs = computed(() => {
    const created = this.createdForSelection();
    const transaction = this.selected();
    if (created === null || (transaction !== null && transaction.status !== 'PENDING')) return [];
    if (this.approvalExpired(transaction)) return [];
    return created.glyphs.split('-').map((g) => Number(g));
  });
  readonly pendingForm = new FormGroup({
    device: new FormControl('slab-01', { nonNullable: true, validators: [Validators.required, Validators.pattern('[A-Za-z0-9_-]{1,64}')] }),
    amount: new FormControl(50000, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.max(100000000)] }),
    dest: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern('[A-Za-z0-9_-]{1,64}')] }),
  });
  private poll: Subscription | null = null;
  private listRequest: Subscription | null = null;
  private readonly feedSubscriptions: Subscription[] = [];
  readonly liveEvents = signal<TransferTransportEvent[]>([]);
  readonly metrics = signal<PipelineMetrics | null>(null);
  readonly metricsError = signal<string | null>(null);
  readonly liveOpen = computed(() => this.feed.connection() === 'open');
  // Polling is only a fallback: it runs while the live socket is down and a transfer is in flight.
  private readonly pollingFallback = effect(() => {
    this.feed.connection();
    this.records();
    this.selectedId();
    this.syncPolling();
  });

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
    this.feedSubscriptions.push(
      this.feed.transportEvents.subscribe((event) => this.applyLiveEvent(event)),
      // Fetch the authoritative state again after every (re)connect, in case events were missed.
      this.feed.opened.subscribe(() => { this.refresh(true); this.loadMetrics(); }),
    );
    this.refresh();
    this.loadMetrics();
    this.feed.startLive();
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    this.listRequest?.unsubscribe();
    for (const subscription of this.feedSubscriptions) subscription.unsubscribe();
    this.feed.stop();
  }

  switchMode(mode: 'live' | 'demo'): void {
    if (this.mode() === mode) return;
    this.mode.set(mode);
    if (mode === 'demo') {
      this.stopPolling();
      this.listRequest?.unsubscribe();
      this.listRequest = null;
      this.feed.startDemo();
    } else {
      this.feed.stop();
      this.feed.startLive();
      this.refresh();
    }
  }

  applyLiveEvent(event: TransferTransportEvent): void {
    if (this.mode() !== 'live') return;
    this.liveEvents.update((events) => [event, ...events.filter((e) => e.eventId !== event.eventId)].slice(0, 12));
    const known = this.records().some((r) => r.transactionId === event.transactionId);
    if (!known) {
      this.refresh(true);
    } else {
      this.records.update((records) => records.map((r) => r.transactionId === event.transactionId ? mergeLiveEvent(r, event) : r));
      // verifiedAt is not part of the event, so fetch it once when the tablet's approval is accepted.
      if (event.stage === 'VERIFIED' && event.outcome === 'APPROVED') this.refresh(true);
    }
    if (event.stage === 'GEMINI' || (event.stage === 'VERIFIED' && event.outcome === 'REJECTED')) this.loadMetrics();
  }

  loadMetrics(): void {
    this.api.metrics(30).subscribe({
      next: (metrics) => { this.metrics.set(metrics); this.metricsError.set(null); },
      error: (error: unknown) => this.metricsError.set(transactionError(error)),
    });
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
        this.syncPolling();
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
      this.apiError.set('Enter a whole-cent amount, destination, and device.');
      return;
    }
    this.creating.set(true);
    this.apiError.set(null);
    const request = this.pendingForm.getRawValue();
    this.api.create(request).subscribe({
      next: (created) => {
        this.created.set(created);
        this.selectedId.set(created.tx_id);
        this.creating.set(false);
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
    this.syncPolling();
  }

  approvalExpired(transaction: TransactionRecord | null): boolean {
    const expiresAt = this.createdForSelection()?.expires_at
      ?? (transaction ? Date.parse(transaction.createdAt) / 1000 + 120 : 0);
    return expiresAt > 0 && Date.now() / 1000 >= expiresAt;
  }

  private awaitingApproval(): boolean {
    const transaction = this.selected();
    // Keep polling while the bank transfer and Gemini audit run.
    if (transaction?.status === 'VERIFIED' && transaction.geminiDecisionReason
      && transaction.geminiDecisionReason !== 'GEMINI_APPROVED') return false;
    if (transaction?.status === 'VERIFIED' || transaction?.status === 'EXECUTING'
      || transaction?.status === 'BANK_PENDING') return true;
    return (transaction?.status === 'PENDING' || (transaction === null && this.createdForSelection() !== null))
      && !this.approvalExpired(transaction);
  }

  private syncPolling(): void {
    if (this.mode() === 'live' && !this.liveOpen() && this.awaitingApproval()) this.startPolling();
    else this.stopPolling();
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

  // Display bands only; the backend's configured thresholds decide blocking.
  riskBand(score: number): 'low' | 'medium' | 'high' {
    return score <= 30 ? 'low' : score <= 60 ? 'medium' : 'high';
  }

  percent(fraction: number): string {
    return `${Math.round(fraction * 100)}%`;
  }

  decisionLabel(code: string | null | undefined): string {
    switch (code) {
      case 'GEMINI_APPROVED': return 'Approved by policy';
      case 'GEMINI_HIGH_RISK': return 'Blocked: risk above limit';
      case 'GEMINI_LOW_CONFIDENCE': return 'Blocked: translation confidence too low';
      case 'GEMINI_INVALID_RESPONSE': return 'Blocked: Gemini answer was invalid';
      case 'GEMINI_UNAVAILABLE': return 'Blocked: Gemini unavailable';
      default: return 'Awaiting Gemini review';
    }
  }

  bankOutcomeLabel(outcome: TransactionRecord['bankOutcome']): string {
    switch (outcome) {
      case 'COMPLETED': return 'Completed';
      case 'ACCEPTED_PENDING': return 'Accepted, settlement pending';
      case 'FAILED': return 'Rejected by bank, no money moved';
      case 'UNKNOWN': return 'Outcome unknown, check Nessie before any retry';
      default: return 'Not sent to the bank';
    }
  }

  eventLabel(event: TransferTransportEvent): string {
    const code = event.payload.reasonCode;
    switch (event.stage) {
      case 'PENDING': return 'Transfer created, waiting for the tablet';
      case 'VERIFIED': return event.outcome === 'APPROVED' ? 'Tablet signature verified'
        : `Tablet approval rejected${code ? ` (${code})` : ''}`;
      case 'GEMINI': return event.outcome === 'APPROVED' ? 'Gemini review passed policy' : this.decisionLabel(code);
      case 'EXECUTING': return 'Sending to the bank';
      case 'BANK_PENDING': return 'Bank accepted, settlement pending';
      case 'EXECUTED': return 'Bank confirmed the transfer';
      case 'FAILED': return 'Bank rejected the transfer';
      case 'OUTCOME_UNKNOWN': return 'Bank outcome unknown, review required';
    }
  }

  eventTone(event: TransferTransportEvent): 'good' | 'bad' | 'wait' {
    if (event.payload.errorState !== null) return 'bad';
    return event.stage === 'EXECUTED' || (event.stage === 'GEMINI' && event.outcome === 'APPROVED') ? 'good' : 'wait';
  }

  shortId(id: string): string {
    return id.length > 8 ? `${id.slice(0, 8)}…` : id;
  }

  liveStatus(transaction: TransactionRecord): string {
    switch (transaction.status) {
      case 'EXECUTED': return 'Completed';
      case 'BANK_PENDING': return 'Accepted by Nessie — pending';
      case 'FAILED': return 'Failed';
      case 'OUTCOME_UNKNOWN': return 'Outcome unknown — review required';
      case 'EXECUTING': return 'Execution claimed; processing pending';
      case 'VERIFIED': return transaction.geminiDecisionReason && transaction.geminiDecisionReason !== 'GEMINI_APPROVED'
        ? 'Physical approval verified; Gemini policy blocked transfer'
        : 'Physical approval verified';
      case 'SIMULATED': return 'Legacy simulation; physical approval unverified';
      case 'PENDING': return this.approvalExpired(transaction) ? 'Approval window expired' : 'Awaiting physical approval';
    }
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
