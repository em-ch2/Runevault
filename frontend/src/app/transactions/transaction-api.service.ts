import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { switchMap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface PendingTransactionRequest {
  device: string;
  amount: number;
  dest: string;
  glyphs: string;
}

export interface PendingTransactionResponse {
  tx_id: string;
  status: 'PENDING';
  expires_at: number;
}

export interface TransactionRecord {
  transactionId: string;
  amountMinor: number;
  destination: string;
  status: 'PENDING' | 'SIMULATED';
  createdAt: string;
  requesterSubject: string | null;
}

interface TransactionPage {
  content: TransactionRecord[];
}

export function transactionError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 400) return 'Check the amount, destination, device, and glyph sequence.';
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

  list() {
    return this.token().pipe(switchMap((token) => this.http.get<TransactionPage>(
      this.endpoint, {
        params: { page: 0, size: 100 },
        headers: new HttpHeaders({ Authorization: `Bearer ${token}` }),
      },
    )));
  }
}
