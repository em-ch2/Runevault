export type TabletState = 'connected' | 'locked' | 'disconnected';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

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
