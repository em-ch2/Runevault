import { TestBed } from '@angular/core/testing';
import { TransactionRecord } from '../transactions/transaction-api.service';
import { AuthorizationEvidence } from './authorization-evidence';

describe('Authorization evidence', () => {
  const base: TransactionRecord = {
    transactionId: 'tx-1', amountMinor: 50000, destination: 'acct-1', status: 'PENDING',
    createdAt: '2026-10-18T10:00:00Z', verifiedAt: null, requesterSubject: null,
  };

  function render(transaction: TransactionRecord): HTMLElement {
    TestBed.configureTestingModule({ imports: [AuthorizationEvidence] });
    const fixture = TestBed.createComponent(AuthorizationEvidence);
    fixture.componentRef.setInput('transaction', transaction);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders recorded physical, policy, approver, MFA, and banking evidence', () => {
    const panel = render({ ...base, status: 'EXECUTED', requesterSubject: 'auth0|maker',
      verifiedAt: '2026-10-18T10:01:00Z', riskScore: 12,
      geminiDecisionReason: 'GEMINI_APPROVED', approverSubject: 'auth0|cfo',
      approvedAt: '2026-10-18T10:03:00Z', approvalStepUp: true,
      bankOutcome: 'COMPLETED', bankSubmittedAt: '2026-10-18T10:03:30Z',
      bankOutcomeAt: '2026-10-18T10:04:00Z' });
    expect(panel.textContent).toContain('auth0|maker');
    expect(panel.textContent).toContain('Verified');
    expect(panel.textContent).toContain('Risk score 12 / 100');
    expect(panel.textContent).toContain('Approved by policy');
    expect(panel.textContent).toContain('auth0|cfo');
    expect(panel.textContent).toContain('Approval MFA');
    expect(panel.textContent).toContain('Used');
    expect(panel.textContent).toContain('COMPLETED');
    expect(panel.querySelectorAll('dd').length).toBe(12);
  });

  it('shows missing and awaiting evidence without treating a receipt as verification', () => {
    const panel = render({ ...base, bankReceiptId: 'receipt-1', awaitingApproval: true });
    expect(panel.textContent).toContain('Awaiting approval');
    expect(panel.textContent).toContain('Not recorded');
    expect(panel.querySelectorAll('dd')[1].textContent).toBe('Not recorded');
    expect(panel.textContent).not.toContain('receipt-1');
  });

  it('shows the persisted policy rejection reason', () => {
    const panel = render({ ...base, verifiedAt: '2026-10-18T10:01:00Z',
      geminiDecisionReason: 'GEMINI_HIGH_RISK',
      geminiDecisionExplanation: 'Risk score 90 is above the limit.' });
    expect(panel.textContent).toContain('Blocked by policy');
    expect(panel.textContent).toContain('Risk score 90 is above the limit.');
  });
});
