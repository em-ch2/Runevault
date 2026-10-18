import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { TransactionRecord } from '../transactions/transaction-api.service';

/** Displays only fields recorded on the persisted transaction and returned by the scoped API. */
@Component({
  selector: 'app-authorization-evidence',
  imports: [DatePipe],
  template: `
    <section class="authorization-evidence" aria-label="Authorization evidence">
      <h3>Authorization evidence</h3>
      <dl class="facts">
        <div><dt>Requester</dt><dd class="mono">{{ transaction().requesterSubject ?? 'Not recorded' }}</dd></div>
        <div><dt>Physical authorization</dt><dd>{{ transaction().verifiedAt ? 'Verified' : 'Not recorded' }}</dd></div>
        <div><dt>Verified at</dt><dd>{{ transaction().verifiedAt ? (transaction().verifiedAt | date:'medium') : 'Not recorded' }}</dd></div>
        <div><dt>Gemini assessment</dt><dd>{{ transaction().riskScore != null ? 'Risk score ' + transaction().riskScore + ' / 100' : 'Not recorded' }}</dd></div>
        <div><dt>Policy result</dt><dd>{{ policyResult(transaction().geminiDecisionReason) }}</dd></div>
        <div><dt>Rejection reason</dt><dd>{{ rejectionReason(transaction()) }}</dd></div>
        <div><dt>Independent approver</dt><dd class="mono">{{ transaction().approverSubject ?? (transaction().awaitingApproval ? 'Awaiting approval' : 'Not recorded') }}</dd></div>
        <div><dt>Approved at</dt><dd>{{ transaction().approvedAt ? (transaction().approvedAt | date:'medium') : (transaction().awaitingApproval ? 'Awaiting approval' : 'Not recorded') }}</dd></div>
        @if (transaction().approvalStepUp != null) {
          <div><dt>Historical MFA record</dt><dd>{{ transaction().approvalStepUp ? 'MFA used' : 'MFA not used' }} (approved under a retired rule)</dd></div>
        }
        <div><dt>Banking outcome</dt><dd>{{ transaction().bankOutcome ?? 'Not recorded' }}</dd></div>
        <div><dt>Bank submitted at</dt><dd>{{ transaction().bankSubmittedAt ? (transaction().bankSubmittedAt | date:'medium') : 'Not recorded' }}</dd></div>
        <div><dt>Outcome recorded at</dt><dd>{{ transaction().bankOutcomeAt ? (transaction().bankOutcomeAt | date:'medium') : 'Not recorded' }}</dd></div>
      </dl>
    </section>
  `,
})
export class AuthorizationEvidence {
  readonly transaction = input.required<TransactionRecord>();

  policyResult(reason: TransactionRecord['geminiDecisionReason']): string {
    if (!reason) return 'Not recorded';
    return reason === 'GEMINI_APPROVED' ? 'Approved by policy' : 'Blocked by policy';
  }

  rejectionReason(transaction: TransactionRecord): string {
    if (transaction.geminiDecisionReason && transaction.geminiDecisionReason !== 'GEMINI_APPROVED')
      return transaction.geminiDecisionExplanation || transaction.geminiDecisionReason;
    if (transaction.bankOutcome === 'FAILED' || transaction.bankOutcome === 'UNKNOWN')
      return transaction.bankReason || 'Not recorded';
    return 'Not recorded';
  }
}
