import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { environment } from '../../environments/environment';
import { TransactionApiService, transactionError } from './transaction-api.service';

describe('TransactionApiService', () => {
  const getAccessTokenSilently = vi.fn(() => of('api-access-token'));
  let api: TransactionApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    getAccessTokenSilently.mockClear();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: Auth0Service, useValue: { getAccessTokenSilently } },
    ] });
    api = TestBed.inject(TransactionApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts transaction details without glyphs using an audience-scoped token', () => {
    const body = { device: 'slab-01', amount: 50000, dest: 'acct-999' };
    let result: unknown;
    api.create(body).subscribe((response) => result = response);
    const request = http.expectOne('/api/transactions');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    expect(request.request.headers.get('Authorization')).toBe('Bearer api-access-token');
    expect(getAccessTokenSilently).toHaveBeenCalledWith({
      authorizationParams: { audience: environment.auth0.audience },
    });
    request.flush({ tx_id: 'abc123', status: 'PENDING', expires_at: 9999999999, glyphs: '0-2-1-2-1' });
    expect(result).toEqual({ tx_id: 'abc123', status: 'PENDING', expires_at: 9999999999, glyphs: '0-2-1-2-1' });
  });

  it('gets paged backend records with the token only on Runevault API requests', () => {
    api.list().subscribe();
    const request = http.expectOne((call) => call.url === '/api/transactions');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('100');
    expect(request.request.headers.get('Authorization')).toBe('Bearer api-access-token');
    request.flush({ content: [] });
    expect(http.match('/api/device/transactions').length).toBe(0);
  });

  it('explains validation, permission, session, and connectivity failures', () => {
    const failed = (status: number) => new HttpErrorResponse({ status, url: '/api/transactions' });
    expect(transactionError(failed(400))).toContain('amount');
    expect(transactionError(failed(401))).toContain('Sign in');
    expect(transactionError(failed(403))).toContain('permission');
    expect(transactionError(failed(0))).toContain('unavailable');
  });
});
