import { Injectable, inject, signal } from '@angular/core';
import { FeedService } from '../feed/feed.service';
import { FeedEvent, RiskLevel, TabletState } from '../feed/feed-event';

export type Stage = 'received' | 'sealed' | 'verified' | 'executed' | 'rejected';

export interface Transfer {
  tx_id: string;
  amount: number;
  dest: string;
  recipient: string;
  requester: string;
  requested_at: string;
  stage: Stage;
  sealed: { device: string; counter: number; sig: string; at: string } | null;
  verified_at: string | null;
  executed: { glyphs: string; lore: string; risk_level: RiskLevel; bank_ref: string; at: string } | null;
  rejected: { reason: string; at: string; failed_stage: Stage } | null;
}

export interface LogEntry {
  at: string;
  text: string;
  tone: 'info' | 'good' | 'bad';
}

export interface DashboardState {
  tablet: { device: string; state: TabletState } | null;
  transfer: Transfer | null;
  log: LogEntry[];
}

export const INITIAL_STATE: DashboardState = { tablet: null, transfer: null, log: [] };

export function reduceFeedEvent(state: DashboardState, event: FeedEvent): DashboardState {
  if (event.type === 'tablet_status') {
    const entry: LogEntry = {
      at: new Date().toISOString(),
      text: 'Tablet ' + event.device + ' ' + event.state,
      tone: event.state === 'connected' ? 'info' : 'bad',
    };
    return { ...state, tablet: { device: event.device, state: event.state }, log: [entry, ...state.log].slice(0, 30) };
  }

  if (event.type === 'transfer_pending') {
    const transfer: Transfer = {
      tx_id: event.tx_id,
      amount: event.amount,
      dest: event.dest,
      recipient: event.recipient,
      requester: event.requester,
      requested_at: event.at,
      stage: 'received',
      sealed: null,
      verified_at: null,
      executed: null,
      rejected: null,
    };
    const entry: LogEntry = { at: event.at, text: 'Transfer ' + event.tx_id + ' received, awaiting tablet', tone: 'info' };
    return { ...state, transfer, log: [entry, ...state.log].slice(0, 30) };
  }

  const current = state.transfer;
  if (current === null || current.tx_id !== event.tx_id) return state;

  if (event.type === 'transfer_sealed') {
    if (current.stage !== 'received') return state;
    const transfer: Transfer = {
      ...current,
      stage: 'sealed',
      sealed: { device: event.device, counter: event.counter, sig: event.sig, at: event.at },
    };
    const entry: LogEntry = { at: event.at, text: 'Sealed by ' + event.device + ', counter ' + event.counter, tone: 'info' };
    return { ...state, transfer, log: [entry, ...state.log].slice(0, 30) };
  }

  if (event.type === 'transfer_verified') {
    if (current.stage !== 'sealed') return state;
    const transfer: Transfer = { ...current, stage: 'verified', verified_at: event.at };
    const entry: LogEntry = { at: event.at, text: 'Signature verified for ' + event.tx_id, tone: 'good' };
    return { ...state, transfer, log: [entry, ...state.log].slice(0, 30) };
  }

  if (event.type === 'transfer_executed') {
    if (current.stage !== 'sealed' && current.stage !== 'verified') return state;
    const transfer: Transfer = {
      ...current,
      stage: 'executed',
      verified_at: current.verified_at === null ? event.at : current.verified_at,
      executed: { glyphs: event.glyphs, lore: event.lore, risk_level: event.risk_level, bank_ref: event.bank_ref, at: event.at },
    };
    const entry: LogEntry = { at: event.at, text: 'Funds moved for ' + event.tx_id + ' (' + event.bank_ref + ')', tone: 'good' };
    return { ...state, transfer, log: [entry, ...state.log].slice(0, 30) };
  }

  if (event.type === 'transfer_rejected') {
    if (current.stage === 'executed' || current.stage === 'rejected') return state;
    let failedStage: Stage = 'sealed';
    if (current.stage === 'sealed') failedStage = 'verified';
    if (current.stage === 'verified') failedStage = 'executed';
    const transfer: Transfer = {
      ...current,
      stage: 'rejected',
      rejected: { reason: event.reason, at: event.at, failed_stage: failedStage },
    };
    const entry: LogEntry = { at: event.at, text: 'Rejected ' + event.tx_id + ': ' + event.reason, tone: 'bad' };
    return { ...state, transfer, log: [entry, ...state.log].slice(0, 30) };
  }

  return state;
}

@Injectable({ providedIn: 'root' })
export class TransferStore {
  readonly state = signal<DashboardState>(INITIAL_STATE);

  constructor() {
    inject(FeedService).events.subscribe((event) => this.state.update((s) => reduceFeedEvent(s, event)));
  }
}
