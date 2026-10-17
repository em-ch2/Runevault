export type TabletState = 'connected' | 'locked' | 'disconnected';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type TransportStage = 'PENDING' | 'VERIFIED' | 'GEMINI' | 'EXECUTING' | 'BANK_PENDING' | 'EXECUTED' | 'FAILED' | 'OUTCOME_UNKNOWN';
export type TransportOutcome = 'PENDING' | 'APPROVED' | 'REJECTED' | 'ACCEPTED_PENDING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN';
export type TransportStatus = 'PENDING' | 'SIMULATED' | 'VERIFIED' | 'EXECUTING' | 'BANK_PENDING' | 'EXECUTED' | 'FAILED' | 'OUTCOME_UNKNOWN';
export type TransportErrorState = 'REJECTED' | 'BLOCKED' | 'FAILED' | 'UNKNOWN';

export interface TransportReceipt {
  provider: string;
  receiptId: string | null;
  providerStatus: string | null;
  submittedAt: string | null;
}

// Live /ws/transfers envelope (docs/events.md). Every field is derived from committed backend records.
export interface TransferTransportEvent {
  eventId: string;
  transactionId: string;
  stage: TransportStage;
  outcome: TransportOutcome;
  status: TransportStatus;
  timestamp: string;
  payload: {
    lore: string | null;
    translationConfidence: number | null;
    riskScore: number | null;
    riskFlags: string[];
    securityMessage: string | null;
    explanation: string | null;
    reasonCode: string | null;
    receipt: TransportReceipt | null;
    newBalanceMinor: number | null;
    errorState: TransportErrorState | null;
  };
}

const STAGES: TransportStage[] = ['PENDING', 'VERIFIED', 'GEMINI', 'EXECUTING', 'BANK_PENDING', 'EXECUTED', 'FAILED', 'OUTCOME_UNKNOWN'];
const OUTCOMES: TransportOutcome[] = ['PENDING', 'APPROVED', 'REJECTED', 'ACCEPTED_PENDING', 'COMPLETED', 'FAILED', 'UNKNOWN'];
const STATUSES: TransportStatus[] = ['PENDING', 'SIMULATED', 'VERIFIED', 'EXECUTING', 'BANK_PENDING', 'EXECUTED', 'FAILED', 'OUTCOME_UNKNOWN'];
const ERROR_STATES: TransportErrorState[] = ['REJECTED', 'BLOCKED', 'FAILED', 'UNKNOWN'];
const INVALID = Symbol('invalid');

// Plain text only: null, or a string of 1..max characters. Anything else invalidates the event.
function text(value: unknown, max: number): string | null | typeof INVALID {
  if (value === null || value === undefined) return null;
  return typeof value === 'string' && value.length >= 1 && value.length <= max ? value : INVALID;
}

function bounded(value: unknown, max: number): number | null | typeof INVALID {
  if (value === null || value === undefined) return null;
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= max ? value : INVALID;
}

function receiptOf(value: unknown): TransportReceipt | null | typeof INVALID {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'object' || Array.isArray(value)) return INVALID;
  const r = value as Record<string, unknown>;
  const provider = text(r['provider'], 32);
  const receiptId = text(r['receiptId'], 64);
  const providerStatus = text(r['providerStatus'], 64);
  const submittedAt = text(r['submittedAt'], 40);
  if (provider === INVALID || provider === null || receiptId === INVALID || providerStatus === INVALID
    || submittedAt === INVALID || (submittedAt !== null && Number.isNaN(Date.parse(submittedAt)))) return INVALID;
  return { provider, receiptId, providerStatus, submittedAt };
}

export function parseTransportEvent(raw: unknown): TransferTransportEvent | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const e = raw as Record<string, unknown>;
  if (typeof e['eventId'] !== 'string' || !/^[0-9a-f-]{36}$/.test(e['eventId']) ||
      typeof e['transactionId'] !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(e['transactionId']) ||
      !STAGES.includes(e['stage'] as TransportStage) || !OUTCOMES.includes(e['outcome'] as TransportOutcome) ||
      !STATUSES.includes(e['status'] as TransportStatus) ||
      typeof e['timestamp'] !== 'string' || Number.isNaN(Date.parse(e['timestamp']))) return null;
  const p = e['payload'];
  if (typeof p !== 'object' || p === null || Array.isArray(p)) return null;
  const payload = p as Record<string, unknown>;

  const lore = text(payload['lore'], 2000);
  const securityMessage = text(payload['securityMessage'], 300);
  const explanation = text(payload['explanation'], 300);
  const confidence = bounded(payload['translationConfidence'], 1);
  const risk = bounded(payload['riskScore'], 100);
  const receipt = receiptOf(payload['receipt']);
  const code = payload['reasonCode'];
  const balance = payload['newBalanceMinor'];
  const errorState = payload['errorState'];
  const flagsRaw = payload['riskFlags'] ?? [];
  if (lore === INVALID || securityMessage === INVALID || explanation === INVALID || confidence === INVALID
    || risk === INVALID || receipt === INVALID) return null;
  if (code !== null && code !== undefined && (typeof code !== 'string' || !/^[A-Z0-9_]{1,64}$/.test(code))) return null;
  if (balance !== null && balance !== undefined && (typeof balance !== 'number' || !Number.isSafeInteger(balance) || balance < 0)) return null;
  if (errorState !== null && errorState !== undefined && !ERROR_STATES.includes(errorState as TransportErrorState)) return null;
  if (!Array.isArray(flagsRaw) || flagsRaw.length > 5
    || !flagsRaw.every((f) => typeof f === 'string' && f.length >= 1 && f.length <= 80)) return null;

  return {
    eventId: e['eventId'] as string,
    transactionId: e['transactionId'] as string,
    stage: e['stage'] as TransportStage,
    outcome: e['outcome'] as TransportOutcome,
    status: e['status'] as TransportStatus,
    timestamp: e['timestamp'] as string,
    payload: {
      lore,
      translationConfidence: confidence,
      riskScore: risk,
      riskFlags: flagsRaw as string[],
      securityMessage,
      explanation,
      reasonCode: (code ?? null) as string | null,
      receipt,
      newBalanceMinor: (balance ?? null) as number | null,
      errorState: (errorState ?? null) as TransportErrorState | null,
    },
  };
}

export interface TabletStatusEvent {
  type: 'tablet_status';
  device: string;
  state: TabletState;
}

export interface TransferPendingEvent {
  type: 'transfer_pending';
  tx_id: string;
  amount: number;
  dest: string;
  recipient: string;
  requester: string;
  at: string;
}

export interface TransferSealedEvent {
  type: 'transfer_sealed';
  tx_id: string;
  device: string;
  counter: number;
  sig: string;
  at: string;
}

export interface TransferVerifiedEvent {
  type: 'transfer_verified';
  tx_id: string;
  at: string;
}

// Glyphs arrive only with execution, after the backend accepted the seal.
export interface TransferExecutedEvent {
  type: 'transfer_executed';
  tx_id: string;
  glyphs: string;
  lore: string;
  risk_level: RiskLevel;
  bank_ref: string;
  at: string;
}

export interface TransferRejectedEvent {
  type: 'transfer_rejected';
  tx_id: string;
  reason: string;
  at: string;
}

export type FeedEvent =
  | TabletStatusEvent
  | TransferPendingEvent
  | TransferSealedEvent
  | TransferVerifiedEvent
  | TransferExecutedEvent
  | TransferRejectedEvent;

// The feed relays data that passed through the untrusted bridge, so every field is re-checked here.
export function parseFeedEvent(raw: unknown): FeedEvent | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const idPattern = /^[^"'`]{1,63}$/;
  const textPattern = /^[^"`]{1,120}$/;

  if (o['type'] === 'tablet_status') {
    if (typeof o['device'] !== 'string' || !idPattern.test(o['device'])) return null;
    if (o['state'] !== 'connected' && o['state'] !== 'locked' && o['state'] !== 'disconnected') return null;
    return { type: 'tablet_status', device: o['device'], state: o['state'] };
  }

  if (typeof o['tx_id'] !== 'string' || !idPattern.test(o['tx_id'])) return null;
  if (typeof o['at'] !== 'string' || Number.isNaN(Date.parse(o['at']))) return null;
  const tx_id = o['tx_id'];
  const at = o['at'];

  if (o['type'] === 'transfer_pending') {
    const amount = o['amount'];
    if (typeof amount !== 'number' || !Number.isInteger(amount) || amount < 1 || amount > 9_999_999_999) return null;
    if (typeof o['dest'] !== 'string' || !idPattern.test(o['dest'])) return null;
    if (typeof o['recipient'] !== 'string' || !textPattern.test(o['recipient'])) return null;
    if (typeof o['requester'] !== 'string' || !textPattern.test(o['requester'])) return null;
    return { type: 'transfer_pending', tx_id, amount, dest: o['dest'], recipient: o['recipient'], requester: o['requester'], at };
  }

  if (o['type'] === 'transfer_sealed') {
    const counter = o['counter'];
    if (typeof o['device'] !== 'string' || !idPattern.test(o['device'])) return null;
    if (typeof counter !== 'number' || !Number.isSafeInteger(counter) || counter < 0) return null;
    if (typeof o['sig'] !== 'string' || !/^[0-9a-f]{64}$/.test(o['sig'])) return null;
    return { type: 'transfer_sealed', tx_id, device: o['device'], counter, sig: o['sig'], at };
  }

  if (o['type'] === 'transfer_verified') {
    return { type: 'transfer_verified', tx_id, at };
  }

  if (o['type'] === 'transfer_executed') {
    if (typeof o['glyphs'] !== 'string' || o['glyphs'].length > 127 || !/^[0-2](-[0-2])*$/.test(o['glyphs'])) return null;
    if (typeof o['lore'] !== 'string' || o['lore'].length < 1 || o['lore'].length > 2000) return null;
    if (o['risk_level'] !== 'LOW' && o['risk_level'] !== 'MEDIUM' && o['risk_level'] !== 'HIGH') return null;
    if (typeof o['bank_ref'] !== 'string' || !idPattern.test(o['bank_ref'])) return null;
    return { type: 'transfer_executed', tx_id, glyphs: o['glyphs'], lore: o['lore'], risk_level: o['risk_level'], bank_ref: o['bank_ref'], at };
  }

  if (o['type'] === 'transfer_rejected') {
    if (typeof o['reason'] !== 'string' || !textPattern.test(o['reason'])) return null;
    return { type: 'transfer_rejected', tx_id, reason: o['reason'], at };
  }

  return null;
}
