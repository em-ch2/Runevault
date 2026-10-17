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
        { provide: AuthService, useValue: { user: signal({ name: 'Ada', email: 'ada@example.com' }), signOut: vi.fn() } },
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

  it('polls pending status and stops once the backend reports authorization', () => {
    vi.useFakeTimers();
    const verifiedAt = new Date().toISOString();
    api.list.mockReturnValueOnce(of({ content: [pending] }))
      .mockReturnValueOnce(of({ content: [{ ...pending, status: 'VERIFIED', verifiedAt }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(3000);
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Physical approval verified');
    expect(fixture.nativeElement.textContent).not.toContain('Funds transferred');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(2);
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

  it('labels an expired pending authorization window and stops polling', () => {
    vi.useFakeTimers();
    api.list.mockReturnValue(of({ content: [{ ...pending,
      createdAt: new Date(Date.now() - 180000).toISOString() }] }));
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('approval window expired');
    vi.advanceTimersByTime(3000);
    expect(api.list).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});
