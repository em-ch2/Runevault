import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { FeedService } from '../feed/feed.service';
import { TransactionApiService } from '../transactions/transaction-api.service';
import { Dashboard } from './dashboard';
import { INITIAL_STATE, TransferStore } from './transfer-store';

describe('Dashboard live transactions', () => {
  const pending = { transactionId: 'abc123', amountMinor: 50000, destination: 'acct-999',
    status: 'PENDING' as const, createdAt: new Date().toISOString(), verifiedAt: null,
    requesterSubject: 'auth0|user' };
  const api = { create: vi.fn(), list: vi.fn() };

  afterEach(() => vi.useRealTimers());

  beforeEach(() => {
    api.create.mockReset();
    api.list.mockReset().mockReturnValue(of({ content: [] }));
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        { provide: TransactionApiService, useValue: api },
        { provide: AuthService, useValue: { user: signal({ name: 'Ada', email: 'ada@example.com' }), busy: signal(false), error: signal(null), signOut: vi.fn() } },
        { provide: FeedService, useValue: { isMock: true, connection: signal('mock'), start: vi.fn(), stop: vi.fn() } },
        { provide: TransferStore, useValue: { state: signal(INITIAL_STATE) } },
      ],
    });
  });

  it('creates a pending backend request and displays its transaction ID', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const dashboard = fixture.componentInstance;
    dashboard.pendingForm.patchValue({ device: 'slab-01', amount: 50000,
      dest: 'acct-999', glyphs: '0-2-1-2-1' });
    api.create.mockReturnValue(of({ tx_id: 'abc123', status: 'PENDING', expires_at: 9999999999 }));
    dashboard.createPending();
    fixture.detectChanges();
    expect(api.create).toHaveBeenCalledWith({ device: 'slab-01', amount: 50000,
      dest: 'acct-999', glyphs: '0-2-1-2-1' });
    expect(fixture.nativeElement.textContent).toContain('abc123');
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

  it('shows the six-field Gemini assessment for a transfer', () => {
    api.list.mockReturnValue(of({ content: [{ ...pending, status: 'EXECUTED', verifiedAt: new Date().toISOString(),
      lore: 'The forge is lit to begin the great exchange.', translationConfidence: 0.92, riskScore: 72,
      riskFlags: ['new destination', 'large amount'], securityMessage: 'This amount is far above earlier transfers.' }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('The forge is lit to begin the great exchange.');
    expect(text).toContain('72/100');
    expect(text).toContain('Translation confidence 92%');
    expect(text).toContain('This amount is far above earlier transfers.');
    expect(text).toContain('new destination · large amount');
    expect(fixture.nativeElement.querySelector('.risk-high')).not.toBeNull();
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
    expect(fixture.nativeElement.textContent).toContain('GEMINI_LOW_CONFIDENCE');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('labels an expired pending authorization window and stops polling', () => {
    vi.useFakeTimers();
    api.list.mockReturnValue(of({ content: [{ ...pending,
      createdAt: new Date(Date.now() - 180000).toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Approval window expired');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});
