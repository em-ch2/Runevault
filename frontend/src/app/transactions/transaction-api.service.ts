import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { map, switchMap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface PendingTransactionRequest {
  device: string;
  amount: number;
  dest: string;
}

export interface PendingTransactionResponse {
  tx_id: string;
  status: 'PENDING';
  expires_at: number;
  glyphs: string;
}

export interface TransactionRecord {
  transactionId: string;
  amountMinor: number;
  destination: string;
  status: 'PENDING' | 'SIMULATED' | 'VERIFIED' | 'EXECUTING' | 'BANK_PENDING' | 'EXECUTED' | 'FAILED' | 'OUTCOME_UNKNOWN';
  createdAt: string;
  expiresAt?: string | null;
  verifiedAt: string | null;
  requesterSubject: string | null;
  bankReceiptId?: string | null;
  bankStatus?: string | null;
  bankProvider?: string | null;
  bankOutcome?: 'ACCEPTED_PENDING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN' | null;
  bankReason?: string | null;
  bankSubmittedAt?: string | null;
  bankOutcomeAt?: string | null;
  newBalanceMinor?: number | null;
  // Bank-reported balance minus every completed transfer; the sandbox never deducts transfers itself.
  expectedBalanceMinor?: number | null;
  lore?: string | null;
  translationConfidence?: number | null;
  riskScore?: number | null;
  riskFlags?: string[] | null;
  riskExplanation?: string | null;
  securityMessage?: string | null;
  geminiDecisionReason?: 'GEMINI_APPROVED' | 'GEMINI_UNAVAILABLE' | 'GEMINI_INVALID_RESPONSE' | 'GEMINI_LOW_CONFIDENCE' | 'GEMINI_HIGH_RISK' | null;
  geminiDecisionExplanation?: string | null;
  geminiDecisionAt?: string | null;
  approverSubject?: string | null;
  approvedAt?: string | null;
  approvalStepUp?: boolean | null;
  awaitingApproval?: boolean;
  stepUpRequired?: boolean;
}

// OIDC multi-factor policy; asking Auth0 for it makes the step-up Action require MFA.
const MFA_ACR = 'http://schemas.openid.net/pape/policies/2007/06/multi-factor';

export function approvalError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const code = (error.error as { error?: string } | null)?.error;
    if (code === 'self_approval') return 'You requested this transfer, so a different approver must release it.';
    if (code === 'step_up_required') return 'This amount needs multi-factor verification. Approve again and complete MFA.';
    if (code === 'not_eligible' || error.status === 409) return 'This transfer can no longer be approved. Refresh the list.';
  }
  return transactionError(error);
}

export interface MinutePoint {
  minute: string;
  approved: number;
  blocked: number;
}

// The treasury balance Nessie reports now, and the remaining balance Runevault calculates after every transfer
// it sent (the sandbox never deducts them).
export interface BankBalance {
  reportedBalanceMinor: number | null;
  sentTransfers: number;
  sentTransfersMinor: number;
  remainingBalanceMinor: number | null;
  asOf: string;
}

export interface PipelineMetrics {
  from: string;
  to: string;
  source: string;
  approved: number;
  blocked: number;
  minutes: MinutePoint[];
  policy: { minTranslationConfidence: number; maxRiskScore: number };
}

export interface PolicyDecisionMetrics {
  from: string;
  to: string;
  source: string;
  measure: 'gemini-policy-decisions';
  approved: number;
  blocked: number;
  minutes: MinutePoint[];
}

export interface AuditEventRecord {
  id: number;
  timestamp: string;
  transactionId: string | null;
  deviceId: string | null;
  eventType: string;
  outcome: string;
  reason: string;
  actorSubject: string | null;
}

export interface AuditEventPage {
  content: AuditEventRecord[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface AuditFilters {
  transactionId?: string;
  outcome?: string;
  from?: string;
  to?: string;
}

interface TransactionPage {
  content: TransactionRecord[];
}

export function transactionError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 400) return 'Check the amount, destination, and device.';
    if (error.status === 401) return 'Your session expired. Sign in again.';
    if (error.status === 403) return 'Your account does not have permission for this action.';
    if (error.status === 0 || error.status >= 500) return 'The backend is unavailable. Try again shortly.';
    return 'The transaction request failed. Please try again.';
  }
  return 'Could not get an access token. Sign in again.';
}

@Injectable({ providedIn: 'root' })
export class TransactionApiService {
  private readonly http = inject(HttpClient);
  private readonly auth0 = inject(Auth0Service);
  private readonly endpoint = '/api/transactions';

  private token() {
    return this.auth0.getAccessTokenSilently({
      authorizationParams: { audience: environment.auth0.audience },
    });
  }

  auditEvents(page: number, filters: AuditFilters) {
    return this.token().pipe(switchMap((token) => this.http.get<AuditEventPage>('/api/audit-events', {
      params: { page, size: 20, ...filters },
      headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
    })));
  }

  policyDecisionMetrics(minutes = 30) {
    return this.token().pipe(switchMap((token) => this.http.get<PolicyDecisionMetrics>(
      '/api/metrics/policy-decisions', {
        params: { minutes },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }

  create(request: PendingTransactionRequest) {
    return this.token().pipe(switchMap((token) => this.http.post<PendingTransactionResponse>(
      this.endpoint, request, { headers: new HttpHeaders({ Authorization: `Bearer ${token}` }) },
    )));
  }

  // Relays the Pico's signed line unchanged; the backend verifies its signature.
  authorize(txId: string, signedLine: string) {
    return this.token().pipe(switchMap((token) => this.http.post<{ status: string }>(
      `${this.endpoint}/${encodeURIComponent(txId)}/authorize`, signedLine, {
        headers: new HttpHeaders({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }),
      },
    )));
  }

  list() {
    return this.token().pipe(switchMap((token) => this.http.get<TransactionPage>(
      this.endpoint, {
        params: { page: 0, size: 100 },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }

  // Display only: the backend checks every permission again on each request.
  permissions() {
    return this.token().pipe(map((token) => {
      if (!token) return [];
      try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        return Array.isArray(payload.permissions) ? payload.permissions as string[] : [];
      } catch {
        return [];
      }
    }));
  }

  approvals() {
    return this.token().pipe(switchMap((token) => this.http.get<TransactionPage>(
      `${this.endpoint}/approvals`, {
        params: { page: 0, size: 100 },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }

  // Large transfers open an Auth0 popup that requires MFA first. Subscribe straight from the click
  // handler so the browser treats the popup as user-initiated.
  approve(txId: string, stepUp: boolean) {
    const token = stepUp
      ? this.auth0.getAccessTokenWithPopup({
        authorizationParams: { audience: environment.auth0.audience, acr_values: MFA_ACR },
      }).pipe(map((value) => {
        if (!value) throw new Error('MFA was not completed');
        return value;
      }))
      : this.token();
    return token.pipe(switchMap((value) => this.http.post<{ status: string }>(
      `${this.endpoint}/${encodeURIComponent(txId)}/approve`, null, {
        headers: new HttpHeaders({ Authorization: `Bearer ${value}` }),
      },
    )));
  }

  // Approvals versus blocks per minute from the pipeline_events table (Tiger Data).
  bankBalance() {
    return this.token().pipe(switchMap((token) => this.http.get<BankBalance>(
      '/api/bank/balance', { headers: new HttpHeaders({ Authorization: `Bearer ${token}` }) },
    )));
  }

  metrics(minutes = 30) {
    return this.token().pipe(switchMap((token) => this.http.get<PipelineMetrics>(
      '/api/metrics/pipeline', {
        params: { minutes },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }
}
