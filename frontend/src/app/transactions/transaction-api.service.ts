import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { switchMap } from 'rxjs';
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
}

export interface MinutePoint {
  minute: string;
  approved: number;
  blocked: number;
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

  // Approvals versus blocks per minute from the pipeline_events table (Tiger Data).
  metrics(minutes = 30) {
    return this.token().pipe(switchMap((token) => this.http.get<PipelineMetrics>(
      '/api/metrics/pipeline', {
        params: { minutes },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }
}
