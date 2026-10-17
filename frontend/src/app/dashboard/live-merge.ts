import { TransferTransportEvent } from '../feed/feed-event';
import { TransactionRecord } from '../transactions/transaction-api.service';

const BANK_STAGES = ['BANK_PENDING', 'EXECUTED', 'FAILED', 'OUTCOME_UNKNOWN'];
const BANK_OUTCOMES: Record<string, TransactionRecord['bankOutcome']> = {
  ACCEPTED_PENDING: 'ACCEPTED_PENDING', COMPLETED: 'COMPLETED', FAILED: 'FAILED', UNKNOWN: 'UNKNOWN',
};

// Applies one live event to a transaction record. The event's status is the persisted backend status;
// other fields only overwrite when the event actually carries them, so a sparse event never erases data.
export function mergeLiveEvent(record: TransactionRecord, event: TransferTransportEvent): TransactionRecord {
  const p = event.payload;
  const next: TransactionRecord = { ...record, status: event.status };
  if (p.lore !== null) next.lore = p.lore;
  if (p.translationConfidence !== null) next.translationConfidence = p.translationConfidence;
  if (p.riskScore !== null) next.riskScore = p.riskScore;
  if (p.riskFlags.length) next.riskFlags = p.riskFlags;
  if (p.securityMessage !== null) next.securityMessage = p.securityMessage;
  if (p.explanation !== null) next.geminiDecisionExplanation = p.explanation;
  if (event.stage === 'GEMINI' && p.reasonCode !== null)
    next.geminiDecisionReason = p.reasonCode as TransactionRecord['geminiDecisionReason'];
  if (p.receipt !== null) {
    next.bankProvider = p.receipt.provider;
    next.bankReceiptId = p.receipt.receiptId;
    next.bankStatus = p.receipt.providerStatus;
    next.bankSubmittedAt = p.receipt.submittedAt;
  }
  if (BANK_STAGES.includes(event.stage)) {
    next.bankOutcome = BANK_OUTCOMES[event.outcome] ?? next.bankOutcome;
    next.bankReason = p.reasonCode;
    next.bankOutcomeAt = event.timestamp;
    next.newBalanceMinor = p.newBalanceMinor;
  }
  return next;
}
