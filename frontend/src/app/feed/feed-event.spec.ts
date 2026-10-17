import { parseFeedEvent } from './feed-event';

describe('parseFeedEvent', () => {
  const at = '2026-10-17T12:00:00.000Z';
  const pending = { type: 'transfer_pending', tx_id: 'rea433', amount: 444222, dest: 'acc_0020', recipient: 'Saltstone', requester: 'Mara', at };

  it('accepts a valid pending transfer', () => {
    expect(parseFeedEvent(pending)).toEqual(pending);
  });

  it('rejects amounts outside 1 to 9,999,999,999 cents or non-integers', () => {
    expect(parseFeedEvent({ ...pending, amount: 0 })).toBeNull();
    expect(parseFeedEvent({ ...pending, amount: 10_000_000_000 })).toBeNull();
    expect(parseFeedEvent({ ...pending, amount: 12.5 })).toBeNull();
    expect(parseFeedEvent({ ...pending, amount: '444222' })).toBeNull();
    expect(parseFeedEvent({ ...pending, amount: 9_999_999_999 })).not.toBeNull();
  });

  it('rejects tx_id or dest with quotes or 64+ characters', () => {
    expect(parseFeedEvent({ ...pending, tx_id: 'rea"433' })).toBeNull();
    expect(parseFeedEvent({ ...pending, dest: "acc'1" })).toBeNull();
    expect(parseFeedEvent({ ...pending, tx_id: 'x'.repeat(64) })).toBeNull();
    expect(parseFeedEvent({ ...pending, tx_id: 'x'.repeat(63) })).not.toBeNull();
  });

  it('rejects malformed glyph sequences and signatures', () => {
    const executed = { type: 'transfer_executed', tx_id: 'rea433', glyphs: '0-1-2', lore: 'A tale.', risk_level: 'LOW', bank_ref: 'b1', at };
    expect(parseFeedEvent(executed)).not.toBeNull();
    expect(parseFeedEvent({ ...executed, glyphs: '0-3-1' })).toBeNull();
    expect(parseFeedEvent({ ...executed, glyphs: '0--1' })).toBeNull();
    const sealed = { type: 'transfer_sealed', tx_id: 'rea433', device: 'slab-01', counter: 5, sig: 'a'.repeat(64), at };
    expect(parseFeedEvent(sealed)).not.toBeNull();
    expect(parseFeedEvent({ ...sealed, sig: 'zz' })).toBeNull();
    expect(parseFeedEvent({ ...sealed, counter: -1 })).toBeNull();
  });

  it('rejects unknown types and non-objects', () => {
    expect(parseFeedEvent({ type: 'nope', tx_id: 'a', at })).toBeNull();
    expect(parseFeedEvent('hello')).toBeNull();
    expect(parseFeedEvent(null)).toBeNull();
    expect(parseFeedEvent([pending])).toBeNull();
  });
});
