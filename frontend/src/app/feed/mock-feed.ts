export type MockScenario = 'approved' | 'rejected';

export interface MockStep {
  afterMs: number;
  event: Record<string, unknown>;
}

// Timestamps ("at") are stamped when each step plays.
export const MOCK_SCRIPTS: Record<MockScenario, MockStep[]> = {
  approved: [
    { afterMs: 400, event: { type: 'tablet_status', device: 'slab-01', state: 'connected' } },
    {
      afterMs: 1600,
      event: {
        type: 'transfer_pending',
        tx_id: 'rea433',
        amount: 444222,
        dest: 'acc_0020',
        recipient: 'East Blue Relief Fund',
        requester: 'Nami',
      },
    },
    {
      afterMs: 5000,
      event: {
        type: 'transfer_sealed',
        tx_id: 'rea433',
        device: 'slab-01',
        counter: 5,
        sig: '60be1f55c2a94e7d8b1f03a6e5d7c4b29f8e1a0d3c6b5f47e2d9a8c1b0f3e6d5',
      },
    },
    { afterMs: 2000, event: { type: 'transfer_verified', tx_id: 'rea433' } },
    {
      afterMs: 2200,
      event: {
        type: 'transfer_executed',
        tx_id: 'rea433',
        glyphs: '0-1-2-1-2-0-1-2',
        lore:
          'The Key was turned beneath a watchful Eye, and the Gate stood open. Twice more the Eye kept vigil as travellers crossed the threshold, until the Key sealed the passage once again and the Eye saw the last of them safely through the Gate.',
        risk_level: 'LOW',
        bank_ref: 'nessie-tr-5f2a91',
      },
    },
  ],
  rejected: [
    { afterMs: 400, event: { type: 'tablet_status', device: 'slab-01', state: 'connected' } },
    {
      afterMs: 1600,
      event: {
        type: 'transfer_pending',
        tx_id: 'rea434',
        amount: 990000,
        dest: 'acc_0666',
        recipient: 'East Blue Harbor',
        requester: 'Nami',
      },
    },
    {
      afterMs: 5000,
      event: {
        type: 'transfer_sealed',
        tx_id: 'rea434',
        device: 'slab-01',
        counter: 5,
        sig: '9d41c07be3f25a6d8e0b1c4f7a2d5e8b3c6f9a0d1e4b7c2f5a8d3e6b9c0f1a2d',
      },
    },
    {
      afterMs: 2000,
      event: {
        type: 'transfer_rejected',
        tx_id: 'rea434',
        reason: 'Replay detected: counter 5 was already used by slab-01. Nothing moved.',
      },
    },
  ],
};
