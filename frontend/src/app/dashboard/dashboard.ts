import { AfterViewInit, Component, ElementRef, HostListener, Injector, OnDestroy, OnInit, ViewChild, afterNextRender, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, interval } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { PendingTransactionResponse, PipelineMetrics, TransactionApiService, TransactionRecord, approvalError, transactionError } from '../transactions/transaction-api.service';
import { TransferTransportEvent } from '../feed/feed-event';
import { ApprovalsChart } from './approvals-chart';
import { FolderPanel } from './folder-panel';
import { mergeLiveEvent } from './live-merge';
import { FeedService } from '../feed/feed.service';
import { MockScenario } from '../feed/mock-feed';
import { GLYPH_NAMES, Glyph } from './glyph';
import { PicoApproval } from './pico-approval';
import { Stage, TransferStore } from './transfer-store';
import { glideTo, slideUp } from './motion';

const STAGE_ORDER: Stage[] = ['received', 'sealed', 'verified', 'executed'];
type NavSection = 'dashboard' | 'transactions' | 'approvals' | 'signoff' | 'history' | 'analytics';

@Component({
  selector: 'app-dashboard',
  imports: [ApprovalsChart, Glyph, FolderPanel, PicoApproval, ReactiveFormsModule],
  templateUrl: './dashboard.html',
})
export class Dashboard implements OnInit, AfterViewInit, OnDestroy {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);
  @ViewChild('navIndicator') private navIndicator?: ElementRef<HTMLElement>;
  readonly auth = inject(AuthService);
  readonly feed = inject(FeedService);
  readonly store = inject(TransferStore);
  readonly api = inject(TransactionApiService);
  readonly mode = signal<'live' | 'demo'>('live');
  readonly flowStarted = signal(false);
  readonly secondaryView = signal<string | null>(null);
  readonly historyStep = signal<number | null>(null);
  readonly historyStepTitles = ['Request created', 'Physical approval', 'Gemini review', 'Bank outcome'];

  selectHistory(id: string): void {
    this.select(id);
    this.historyStep.set(3);
  }

  inspectHistoryStep(step: number): void {
    if (this.historyStep() === null || !this.selected() || step < 0 || step > 3) return;
    this.secondaryView.set(null);
    this.historyStep.set(step);
  }

  historyStageRecorded(step: number): boolean {
    const transaction = this.selected();
    if (!transaction) return false;
    if (step === 0) return true;
    if (step === 1) return !!transaction.verifiedAt;
    if (step === 2) return !!transaction.geminiDecisionReason;
    return !!transaction.bankOutcome;
  }
  readonly demoView = signal<string | null>(null);
  readonly demoPanel = computed(() => this.demoView() ?? (this.transfer()?.stage === 'executed' ? 'receipt-title' : 'pending-title'));
  readonly flowStep = computed(() => {
    if (!this.flowStarted()) return 0;
    const transaction = this.selected();
    if (!transaction || transaction.status === 'PENDING') return 1;
    if (['EXECUTED', 'FAILED', 'OUTCOME_UNKNOWN', 'SIMULATED'].includes(transaction.status)
      || (transaction.geminiDecisionReason && transaction.geminiDecisionReason !== 'GEMINI_APPROVED')) return 3;
    return 2;
  });
  readonly currentPanel = computed(() => this.secondaryView()
    ?? (this.historyStep() !== null ? 'history-trace-title'
      : this.flowStep() === 0 ? 'live-create-title' : this.flowStep() === 1 ? 'authorize-title' : 'live-status-title'));
  readonly flowBlocked = computed(() => this.flowStep() === 3 && this.selected()?.status !== 'EXECUTED');

  startNewTransfer(): void {
    this.historyStep.set(null);
    this.flowStarted.set(false);
    this.secondaryView.set(null);
    this.selectedId.set(null);
    this.created.set(null);
    this.apiError.set(null);
  }
  readonly openFolders = signal<ReadonlySet<string>>(new Set(['live-create-title', 'pending-title']));

  toggleFolder(id: string): void {
    this.openFolders.update(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  openFolder(id: string): void {
    this.openFolders.update(current => new Set([...current, id]));
    if (id === 'authorize-title' || id === 'live-status-title') {
      this.flowStarted.set(true);
      this.secondaryView.set(null);
    }
  }

  openLiveFolder(id: string): void {
    this.switchMode('live');
    this.secondaryView.set(['live-create-title', 'authorize-title'].includes(id) ? null : id);
  }
  readonly workflowSteps = [
    { title: 'Create request', detail: 'Destination & amount' },
    { title: 'Approve on Pico', detail: 'Enter the six glyphs' },
    { title: 'Review & transfer', detail: 'Automatic checks' },
    { title: 'Result', detail: 'Receipt or next steps' },
  ];
  readonly workflowProgress = computed(() => {
    if (this.mode() === 'demo') {
      const transfer = this.transfer();
      if (!transfer) return 0;
      if (transfer.stage === 'executed') return 4;
      if (transfer.stage === 'rejected') return 3;
      return transfer.stage === 'received' ? 1 : 2;
    }
    return this.flowStarted() && this.selected()?.status === 'EXECUTED' ? 4 : this.flowStep();
  });
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
  private approvalPoll: Subscription | null = null;
  private listRequest: Subscription | null = null;
  private readonly feedSubscriptions: Subscription[] = [];
  readonly metrics = signal<PipelineMetrics | null>(null);
  // Maker-checker: transfers waiting for a second approver (CFO role, approve:transactions).
  readonly canApprove = signal(false);
  readonly approvalQueue = signal<TransactionRecord[]>([]);
  readonly highValueApprovals = computed(() => this.approvalQueue().filter((transaction) => transaction.stepUpRequired).length);
  readonly approvingId = signal<string | null>(null);
  readonly approvalNotice = signal<string | null>(null);
  readonly approvalProblem = signal<string | null>(null);
  readonly metricsError = signal<string | null>(null);
  readonly liveOpen = computed(() => this.feed.connection() === 'open');
  // Polling is only a fallback: it runs while the live socket is down and a transfer is in flight.
  private readonly pollingFallback = effect(() => {
    this.feed.connection();
    this.records();
    this.selectedId();
    this.syncPolling();
  });

  // Slides the visible panel up whenever it changes; a mode switch also staggers in the bars above it.
  private lastMode: 'live' | 'demo' | null = null;
  private readonly panelMotion = effect(() => {
    const mode = this.mode();
    if (mode === 'live') this.currentPanel(); else this.demoPanel();
    const modeChanged = this.lastMode !== null && this.lastMode !== mode;
    this.lastMode = mode;
    afterNextRender(() => {
      const root = this.host.nativeElement;
      const panel = root.querySelector('.guided-flow > app-folder-panel:not([hidden])');
      const bars = modeChanged ? [...root.querySelectorAll('.mock-bar, .flow-navigation')] : [];
      slideUp([...bars, ...(panel ? [panel] : [])], { distance: modeChanged ? 20 : 28 });
    }, { injector: this.injector });
  });

  // History and Analytics follow the visible view, so the "Back" buttons also return the highlight.
  private readonly navChoice = signal<NavSection>('dashboard');
  readonly activeNav = computed<NavSection>(() => {
    if (this.mode() === 'demo') return 'dashboard';
    const view = this.secondaryView();
    if (view === 'live-list-title' || this.historyStep() !== null) return 'history';
    if (view === 'metrics-title') return 'analytics';
    if (view === 'signoff-title') return 'signoff';
    const choice = this.navChoice();
    return choice === 'transactions' || choice === 'approvals' ? choice : 'dashboard';
  });
  private readonly navMotion = effect(() => {
    this.activeNav();
    afterNextRender(() => this.restNavIndicator(), { injector: this.injector });
  });

  navTo(section: NavSection, panelId?: string): void {
    this.navChoice.set(section);
    if (panelId) this.openLiveFolder(panelId);
    else { this.switchMode('live'); this.historyStep.set(null); this.secondaryView.set(null); }
  }

  ngAfterViewInit(): void {
    this.restNavIndicator(true);
  }

  @HostListener('window:resize')
  onResize(): void {
    this.restNavIndicator(true);
  }

  hoverNav(event: Event): void {
    if (this.navIndicator) glideTo(this.navIndicator.nativeElement, event.currentTarget as HTMLElement);
  }

  restNavIndicator(instant = false): void {
    const current = this.host.nativeElement.querySelector<HTMLElement>('.sidebar nav .nav-current');
    if (this.navIndicator) glideTo(this.navIndicator.nativeElement, current, instant);
  }

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
    this.api.permissions().subscribe({
      next: (permissions) => {
        this.canApprove.set(permissions.includes('approve:transactions'));
        if (this.canApprove()) {
          this.loadApprovals();
          // The transfer feed is scoped to the requester; refresh the CFO queue independently.
          this.approvalPoll = interval(15000).subscribe(() => this.loadApprovals());
        }
      },
      error: () => this.canApprove.set(false),
    });
    this.feed.startLive();
  }

  openSignoff(): void {
    this.navTo('signoff', 'signoff-title');
    this.loadApprovals();
  }

  loadApprovals(): void {
    this.api.approvals().subscribe({
      next: (page) => { this.approvalQueue.set(page.content); this.approvalProblem.set(null); },
      error: (error: unknown) => this.approvalProblem.set(approvalError(error)),
    });
  }

  ownRequest(transaction: TransactionRecord): boolean {
    return transaction.requesterSubject !== null && transaction.requesterSubject === this.auth.subject();
  }

  // Called straight from the click so a step-up MFA popup is not blocked by the browser.
  approve(transaction: TransactionRecord): void {
    if (this.approvingId() || this.ownRequest(transaction)) return;
    this.approvingId.set(transaction.transactionId);
    this.approvalNotice.set(null);
    this.approvalProblem.set(null);
    this.api.approve(transaction.transactionId, !!transaction.stepUpRequired).subscribe({
      next: (result) => {
        this.approvingId.set(null);
        this.approvalNotice.set(`Released ${this.money(transaction.amountMinor)} to ${transaction.destination}: ${result.status}.`);
        this.loadApprovals();
        this.loadMetrics();
      },
      error: (error: unknown) => {
        this.approvingId.set(null);
        this.approvalProblem.set(approvalError(error));
        this.loadApprovals();
      },
    });
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    this.approvalPoll?.unsubscribe();
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
    const known = this.records().some((r) => r.transactionId === event.transactionId);
    if (!known) {
      this.refresh(true);
    } else {
      this.records.update((records) => records.map((r) => r.transactionId === event.transactionId ? mergeLiveEvent(r, event) : r));
      // Verification time, approval state, and expected balance are read from the API.
      if ((event.stage === 'VERIFIED' && event.outcome === 'APPROVED')
        || (event.stage === 'GEMINI' && event.outcome === 'APPROVED')
        || event.stage === 'EXECUTED') this.refresh(true);
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
        this.historyStep.set(null);
        this.created.set(created);
        this.openFolder('authorize-title');
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
    this.historyStep.set(null);
    this.selectedId.set(id);
    this.openFolder('live-status-title');
    this.syncPolling();
  }

  approvalExpired(transaction: TransactionRecord | null): boolean {
    const expiresAt = this.createdForSelection()?.expires_at
      ?? (transaction?.expiresAt ? Date.parse(transaction.expiresAt) / 1000 : 0);
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
    this.demoView.set(null);
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

  // Customer-facing wording; the operator wording above (decisionLabel) stays in History and Activity.
  customerDecision(code: string): string {
    switch (code) {
      case 'GEMINI_APPROVED': return 'Passed our security review';
      case 'GEMINI_HIGH_RISK': return 'Stopped by our security review';
      case 'GEMINI_LOW_CONFIDENCE': return 'We could not confirm your approval seal';
      default: return 'Our security review could not finish';
    }
  }

  customerBlockDetail(transaction: TransactionRecord): string {
    const reason = transaction.geminiDecisionReason === 'GEMINI_HIGH_RISK' && transaction.riskExplanation
      ? `${transaction.riskExplanation} ` : '';
    return `${reason}Nothing was sent to the bank and no money moved.`;
  }

  // Nessie only lowers the balance once a transfer settles, so a pending transfer has no new balance yet.
  balanceText(transaction: TransactionRecord): string {
    if (transaction.expectedBalanceMinor != null) return this.money(transaction.expectedBalanceMinor);
    if (transaction.newBalanceMinor != null) return this.money(transaction.newBalanceMinor);
    if (transaction.bankOutcome === 'FAILED') return 'Unchanged, nothing moved';
    if (transaction.bankOutcome === 'COMPLETED') return 'Not yet reported by the bank';
    if (transaction.bankOutcome === 'UNKNOWN') return 'Unknown until the bank outcome is confirmed';
    return 'Updates when the transfer settles';
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
        : transaction.awaitingApproval ? 'Passed review; waiting for a second approver to release it'
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
