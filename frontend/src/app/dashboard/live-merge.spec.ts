import { TransferTransportEvent } from '../feed/feed-event';
import { TransactionRecord } from '../transactions/transaction-api.service';
import { mergeLiveEvent } from './live-merge';

describe('mergeLiveEvent', () => {
  const record: TransactionRecord = { transactionId: 'tx-1', amountMinor: 50000, destination: 'acct-999', status: 'EXECUTING',
    createdAt: '2026-10-17T12:00:00Z', verifiedAt: '2026-10-17T12:00:05Z', requesterSubject: 'auth0|a',
    lore: 'Stored lore.', riskScore: 10, translationConfidence: 0.9 };
  const event = (stage: TransferTransportEvent['stage'], outcome: TransferTransportEvent['outcome'],
    status: TransferTransportEvent['status'], payload: Partial<TransferTransportEvent['payload']> = {}): TransferTransportEvent => ({
    eventId: '11111111-1111-1111-1111-111111111111', transactionId: 'tx-1', stage, outcome, status,
    timestamp: '2026-10-17T12:00:09Z',
    payload: { lore: null, translationConfidence: null, riskScore: null, riskFlags: [], securityMessage: null, explanation: null,
      reasonCode: null, receipt: null, newBalanceMinor: null, errorState: null, ...payload } });

  it('takes the persisted status and never erases loaded data with empty fields', () => {
    const merged = mergeLiveEvent(record, event('EXECUTING', 'APPROVED', 'EXECUTING'));
    expect(merged.lore).toBe('Stored lore.');
    expect(merged.riskScore).toBe(10);
  });

  it('records bank outcome, receipt, timestamps and balance from bank stages', () => {
    const merged = mergeLiveEvent(record, event('EXECUTED', 'COMPLETED', 'EXECUTED', { reasonCode: 'CONFIRMED_COMPLETED',
      newBalanceMinor: 450000,
      receipt: { provider: 'nessie', receiptId: 'r-1', providerStatus: 'completed', submittedAt: '2026-10-17T12:00:08Z' } }));
    expect(merged.status).toBe('EXECUTED');
    expect(merged.bankOutcome).toBe('COMPLETED');
    expect(merged.bankReason).toBe('CONFIRMED_COMPLETED');
    expect(merged.bankReceiptId).toBe('r-1');
    expect(merged.bankSubmittedAt).toBe('2026-10-17T12:00:08Z');
    expect(merged.bankOutcomeAt).toBe('2026-10-17T12:00:09Z');
    expect(merged.newBalanceMinor).toBe(450000);
  });

  it('records a Gemini block reason and explanation without touching bank fields', () => {
    const merged = mergeLiveEvent({ ...record, status: 'VERIFIED' }, event('GEMINI', 'REJECTED', 'VERIFIED',
      { reasonCode: 'GEMINI_HIGH_RISK', explanation: 'Risk score 72 is above the limit of 30.', riskScore: 72, errorState: 'BLOCKED' }));
    expect(merged.geminiDecisionReason).toBe('GEMINI_HIGH_RISK');
    expect(merged.geminiDecisionExplanation).toBe('Risk score 72 is above the limit of 30.');
    expect(merged.riskScore).toBe(72);
    expect(merged.bankOutcome).toBeUndefined();
  });
});
