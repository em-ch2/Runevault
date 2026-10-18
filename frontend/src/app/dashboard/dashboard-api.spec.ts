import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { afterEach, vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { FeedConnection, FeedService } from '../feed/feed.service';
import { TransferTransportEvent } from '../feed/feed-event';
import { TransactionApiService } from '../transactions/transaction-api.service';
import { Dashboard } from './dashboard';
import { INITIAL_STATE, TransferStore } from './transfer-store';

describe('Dashboard live transactions', () => {
  const pending = { transactionId: 'abc123', amountMinor: 50000, destination: 'acct-999',
    status: 'PENDING' as const, createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 120000).toISOString(), verifiedAt: null,
    requesterSubject: 'auth0|user' };
  const api = { create: vi.fn(), list: vi.fn(), metrics: vi.fn() };
  const metrics = { from: '2026-10-17T12:00:00Z', to: '2026-10-17T12:02:00Z', source: 'pipeline-events', approved: 3, blocked: 2,
    policy: { minTranslationConfidence: 0.8, maxRiskScore: 30 },
    minutes: [
      { minute: '2026-10-17T12:00:00Z', approved: 1, blocked: 0 },
      { minute: '2026-10-17T12:01:00Z', approved: 2, blocked: 1 },
      { minute: '2026-10-17T12:02:00Z', approved: 0, blocked: 1 },
    ] };
  let feed: { liveEnabled: boolean; connection: ReturnType<typeof signal<FeedConnection>>; startLive: ReturnType<typeof vi.fn>;
    startDemo: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn>; playMock: ReturnType<typeof vi.fn>;
    transportEvents: Subject<TransferTransportEvent>; opened: Subject<void> };
  const event = (changes: Partial<TransferTransportEvent> = {}, payload: Partial<TransferTransportEvent['payload']> = {}): TransferTransportEvent => ({
    eventId: crypto.randomUUID(), transactionId: 'abc123', stage: 'GEMINI', outcome: 'REJECTED', status: 'VERIFIED',
    timestamp: new Date().toISOString(), ...changes,
    payload: { lore: null, translationConfidence: null, riskScore: null, riskFlags: [], securityMessage: null,
      explanation: null, reasonCode: null, receipt: null, newBalanceMinor: null, errorState: null, ...payload },
  });

  afterEach(() => vi.useRealTimers());

  beforeEach(() => {
    api.create.mockReset();
    api.list.mockReset().mockReturnValue(of({ content: [] }));
    api.metrics.mockReset().mockReturnValue(of(metrics));
    feed = { liveEnabled: true, connection: signal<FeedConnection>('closed'), startLive: vi.fn(), startDemo: vi.fn(),
      stop: vi.fn(), playMock: vi.fn(), transportEvents: new Subject(), opened: new Subject() };
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        { provide: TransactionApiService, useValue: api },
        { provide: AuthService, useValue: { user: signal({ name: 'Ada', email: 'ada@example.com' }), busy: signal(false), error: signal(null), signOut: vi.fn() } },
        { provide: FeedService, useValue: feed },
        { provide: TransferStore, useValue: { state: signal(INITIAL_STATE) } },
      ],
    });
  });

  it('guides the user from creation through physical approval to the final outcome', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const dashboard = fixture.componentInstance;
    expect(dashboard.currentPanel()).toBe('live-create-title');
    dashboard.records.set([pending]);
    dashboard.select(pending.transactionId);
    expect(dashboard.currentPanel()).toBe('authorize-title');
    dashboard.records.set([{ ...pending, status: 'VERIFIED', verifiedAt: new Date().toISOString() }]);
    expect(dashboard.currentPanel()).toBe('live-status-title');
    expect(dashboard.flowStep()).toBe(2);
    dashboard.records.set([{ ...pending, status: 'BANK_PENDING' }]);
    expect(dashboard.flowStep()).toBe(2);
    dashboard.records.set([{ ...pending, status: 'OUTCOME_UNKNOWN' }]);
    expect(dashboard.flowStep()).toBe(3);
    expect(dashboard.flowBlocked()).toBe(true);
    expect(dashboard.workflowProgress()).toBe(3);
    dashboard.records.set([{ ...pending, status: 'EXECUTED' }]);
    expect(dashboard.workflowProgress()).toBe(4);
    dashboard.secondaryView.set('live-list-title');
    expect(dashboard.currentPanel()).toBe('live-list-title');
    dashboard.secondaryView.set(null);
    expect(dashboard.currentPanel()).toBe('live-status-title');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.guided-flow > app-folder-panel:not([hidden])').length).toBe(1);
    dashboard.startNewTransfer();
    expect(dashboard.currentPanel()).toBe('live-create-title');
    fixture.destroy();
  });

  it('traces historical steps without exposing an approval form or submitting another request', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'EXECUTED',
      verifiedAt: '2026-10-17T12:01:00Z', geminiDecisionReason: 'GEMINI_APPROVED',
      geminiDecisionAt: '2026-10-17T12:02:00Z', bankOutcome: 'COMPLETED', bankReceiptId: 'receipt-123' }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const dashboard = fixture.componentInstance;
    dashboard.selectHistory(pending.transactionId);
    fixture.detectChanges();
    expect(dashboard.historyStep()).toBe(3);
    expect(dashboard.currentPanel()).toBe('history-trace-title');
    const steps = fixture.nativeElement.querySelectorAll('.workflow-step-button') as NodeListOf<HTMLButtonElement>;
    for (let step = 0; step < 4; step++) {
      steps[step].click();
      fixture.detectChanges();
      expect(dashboard.historyStep()).toBe(step);
      expect(dashboard.currentPanel()).toBe('history-trace-title');
      const visible = fixture.nativeElement.querySelector('.guided-flow > app-folder-panel:not([hidden])');
      expect(visible.querySelector('form')).toBeNull();
    }
    expect(fixture.nativeElement.textContent).toContain('receipt-123');
    expect(api.create).not.toHaveBeenCalled();
    dashboard.records.set([pending]);
    expect(dashboard.historyStageRecorded(1)).toBe(false);
    expect(dashboard.historyStageRecorded(2)).toBe(false);
    expect(dashboard.historyStageRecorded(3)).toBe(false);
    dashboard.startNewTransfer();
    expect(dashboard.historyStep()).toBeNull();
    fixture.destroy();
  });

  it('creates a pending request without client-supplied glyphs and displays the server-generated glyphs', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const dashboard = fixture.componentInstance;
    dashboard.pendingForm.patchValue({ device: 'slab-01', amount: 50000, dest: 'acct-999' });
    api.create.mockReturnValue(of({ tx_id: 'abc123', status: 'PENDING', expires_at: 9999999999,
      glyphs: '0-2-1-2-1' }));
    dashboard.createPending();
    fixture.detectChanges();
    expect(api.create).toHaveBeenCalledWith({ device: 'slab-01', amount: 50000, dest: 'acct-999' });
    expect(fixture.nativeElement.textContent).toContain('abc123');
    expect(dashboard.pendingGlyphs()).toEqual([0, 2, 1, 2, 1]);
    expect(dashboard.openFolders().has('authorize-title')).toBe(true);
    expect(dashboard.openFolders().has('live-create-title')).toBe(true);
    fixture.destroy();
  });

  it('keeps polling after physical verification while banking is unresolved', () => {
    vi.useFakeTimers();
    const verifiedAt = new Date().toISOString();
    api.list.mockReturnValueOnce(of({ content: [pending] }))
      .mockReturnValue(of({ content: [{ ...pending, status: 'VERIFIED', verifiedAt }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(3000);
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Physical approval verified');
    expect(fixture.nativeElement.textContent).not.toContain('Funds transferred');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(3);
    fixture.destroy();
  });

  it('shows the lore and a plain review result without operator scores', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'EXECUTED', verifiedAt: new Date().toISOString(),
      geminiDecisionReason: 'GEMINI_APPROVED',
      lore: 'The forge is lit to begin the great exchange.', translationConfidence: 0.92, riskScore: 12,
      riskFlags: ['new destination', 'large amount'], securityMessage: 'This amount is far above earlier transfers.' }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('The forge is lit to begin the great exchange.');
    expect(text).toContain('Passed our security review');
    // Scores, confidence and flags are operator detail; they stay in the History trace.
    expect(text).not.toContain('12 / 100');
    expect(text).not.toContain('Translation confidence');
    expect(text).not.toContain('large amount');
    expect(text).not.toContain('This amount is far above earlier transfers.');
    fixture.destroy();
  });

  it('does not label historical simulations as physical approvals', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'SIMULATED' }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Legacy simulation; physical approval unverified');
    fixture.destroy();
  });

  it('shows an execution claim without claiming funds were transferred', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'EXECUTING', verifiedAt: new Date().toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Execution claimed; processing pending');
    expect(fixture.nativeElement.textContent).not.toContain('Funds transferred');
    fixture.destroy();
  });

  it('renders each durable bank outcome accurately', () => {
    const cases = [
      ['BANK_PENDING', 'Accepted by Nessie — pending'],
      ['EXECUTED', 'Completed'],
      ['FAILED', 'Failed'],
      ['OUTCOME_UNKNOWN', 'Outcome unknown — review required'],
    ] as const;
    for (const [status, label] of cases) {
      api.list.mockReturnValue(of({ content: [{ ...pending, status, verifiedAt: new Date().toISOString(),
        bankProvider: 'nessie', bankStatus: status, bankReceiptId: status === 'BANK_PENDING' ? 'receipt-1' : null }] }));
      const fixture = TestBed.createComponent(Dashboard);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(label);
      if (status === 'BANK_PENDING') expect(fixture.nativeElement.textContent).not.toContain('Completed');
      fixture.destroy();
    }
  });

  it('continues polling executing and bank-pending records', () => {
    vi.useFakeTimers();
    for (const status of ['EXECUTING', 'BANK_PENDING'] as const) {
      api.list.mockReset().mockReturnValue(of({ content: [{ ...pending, status,
        verifiedAt: new Date().toISOString() }] }));
      const fixture = TestBed.createComponent(Dashboard);
      fixture.detectChanges();
      expect(api.list).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(3000);
      expect(api.list).toHaveBeenCalledTimes(2);
      fixture.destroy();
    }
  });

  it('shows a Gemini block after physical verification and stops polling', () => {
    vi.useFakeTimers();
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'VERIFIED',
      verifiedAt: new Date().toISOString(), geminiDecisionReason: 'GEMINI_LOW_CONFIDENCE' }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Gemini policy blocked transfer');
    expect(fixture.nativeElement.textContent).toContain('We could not confirm your approval seal');
    expect(fixture.nativeElement.textContent).toContain('no money moved');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('labels an expired pending authorization window and stops polling', () => {
    vi.useFakeTimers();
    api.list.mockReturnValue(of({ content: [{ ...pending,
      expiresAt: new Date(Date.now() - 1000).toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Approval window expired');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('applies live events to the selected transfer without polling while the socket is open', () => {
    vi.useFakeTimers();
    feed.connection.set('open');
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'VERIFIED', verifiedAt: new Date().toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    feed.transportEvents.next(event({}, { riskScore: 72, translationConfidence: 0.9, reasonCode: 'GEMINI_HIGH_RISK',
      errorState: 'BLOCKED', explanation: 'Risk score 72 is above the limit of 30, so banking was blocked.',
      securityMessage: 'Unusually large for this destination.' }));
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Stopped by our security review');
    expect(text).toContain('no money moved');
    expect(text).not.toContain('Risk score 72 is above the limit of 30');
    expect(text).not.toContain('72 / 100');
    vi.advanceTimersByTime(9000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('shows the bank receipt card with outcome, timestamps and the confirmed balance', () => {
    feed.connection.set('open');
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'EXECUTING', verifiedAt: new Date().toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    feed.transportEvents.next(event({ stage: 'BANK_PENDING', outcome: 'ACCEPTED_PENDING', status: 'BANK_PENDING' },
      { reasonCode: 'ACCEPTED_PENDING', newBalanceMinor: 450000,
        receipt: { provider: 'nessie', receiptId: 'receipt-7', providerStatus: 'pending', submittedAt: new Date().toISOString() } }));
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Bank receipt · Accepted, settlement pending');
    expect(text).toContain('receipt-7');
    expect(text).toContain('$4,500.00');
    expect(text).not.toContain('Completed');
    fixture.destroy();
  });

  it('does not report a new balance when the bank outcome is unknown', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'OUTCOME_UNKNOWN', verifiedAt: new Date().toISOString(),
      bankProvider: 'nessie', bankStatus: 'NETWORK_UNCERTAIN', bankOutcome: 'UNKNOWN', newBalanceMinor: null }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Outcome unknown, check Nessie before any retry');
    expect(text).toContain('Unknown until the bank outcome is confirmed');
    fixture.destroy();
  });

  it('resynchronizes from the API and metrics each time the live socket reopens', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(feed.startLive).toHaveBeenCalled();
    expect(api.list).toHaveBeenCalledTimes(1);
    feed.connection.set('open');
    feed.opened.next();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(api.metrics).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });

  it('refreshes when an event names a transfer it has not loaded yet', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    feed.transportEvents.next(event({ transactionId: 'made-in-another-tab', stage: 'PENDING', outcome: 'PENDING', status: 'PENDING' }));
    expect(api.list).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });

  it('charts approvals versus blocks with a legend, totals and a table view', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.chart-legend')?.textContent).toContain('Approved 3');
    expect(el.querySelector('.chart-legend')?.textContent).toContain('Blocked 2');
    expect(el.querySelectorAll('.chart-bar-approved').length).toBe(2);
    expect(el.querySelectorAll('.chart-bar-blocked').length).toBe(2);
    expect(el.querySelectorAll('.chart-table tbody tr').length).toBe(3);
    expect(el.querySelector('svg.chart')?.getAttribute('aria-label')).toContain('3 approved, 2 blocked');
    fixture.destroy();
  });
});
