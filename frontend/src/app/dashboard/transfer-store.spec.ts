import { parseFeedEvent } from '../feed/feed-event';
import { MOCK_SCRIPTS } from '../feed/mock-feed';
import { DashboardState, INITIAL_STATE, reduceFeedEvent } from './transfer-store';

function play(steps: { event: Record<string, unknown> }[]): DashboardState {
  let state = INITIAL_STATE;
  for (const step of steps) {
    const event = parseFeedEvent({ ...step.event, at: '2026-10-17T12:00:00.000Z' });
    expect(event).not.toBeNull();
    if (event !== null) state = reduceFeedEvent(state, event);
  }
  return state;
}

describe('reduceFeedEvent', () => {
  it('plays the approved mock through to executed with a receipt', () => {
    const state = play(MOCK_SCRIPTS.approved);
    expect(state.tablet?.state).toBe('connected');
    expect(state.transfer?.stage).toBe('executed');
    expect(state.transfer?.sealed?.counter).toBe(5);
    expect(state.transfer?.executed?.glyphs).toBe('0-1-2-1-2-0-1-2');
    expect(state.transfer?.rejected).toBeNull();
  });

  it('plays the rejected mock and marks the verify stage as failed', () => {
    const state = play(MOCK_SCRIPTS.rejected);
    expect(state.transfer?.stage).toBe('rejected');
    expect(state.transfer?.rejected?.failed_stage).toBe('verified');
    expect(state.transfer?.executed).toBeNull();
  });

  it('ignores events for a different transfer or out of order', () => {
    const pendingOnly = play(MOCK_SCRIPTS.approved.slice(0, 2));
    const other = reduceFeedEvent(pendingOnly, { type: 'transfer_verified', tx_id: 'other', at: '2026-10-17T12:00:00.000Z' });
    expect(other).toBe(pendingOnly);
    const skipped = reduceFeedEvent(pendingOnly, { type: 'transfer_verified', tx_id: 'rea433', at: '2026-10-17T12:00:00.000Z' });
    expect(skipped).toBe(pendingOnly);
  });
});
