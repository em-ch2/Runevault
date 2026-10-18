import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { DecisionExplanation as Explanation, TransactionApiService, TransactionRecord }
  from '../transactions/transaction-api.service';
import { DecisionExplanation } from './decision-explanation';

describe('Decision explanation', () => {
  const blocked: TransactionRecord = {
    transactionId: 'tx-1', amountMinor: 50000, destination: 'acct-1', status: 'VERIFIED',
    createdAt: '2026-10-18T10:00:00Z', verifiedAt: '2026-10-18T10:01:00Z', requesterSubject: 'auth0|maker',
    geminiDecisionReason: 'GEMINI_HIGH_RISK', geminiDecisionAt: '2026-10-18T10:01:05Z',
    geminiDecisionExplanation: 'Risk score 80 is above the limit of 30, so banking was blocked.',
  };
  const answer: Explanation = {
    source: 'gemini', summary: '<b>Blocked</b> by the recorded policy.',
    keyFactors: ['Deterministic policy: the risk limit was exceeded.'],
    requiredNextStep: 'None in Runevault.', limitations: ['Risk features were not stored.'],
    recordedReason: blocked.geminiDecisionExplanation!, generatedAt: '2026-10-18T10:05:00Z',
  };
  const api = { explanation: vi.fn() };

  function render(transaction: TransactionRecord) {
    TestBed.configureTestingModule({
      imports: [DecisionExplanation], providers: [{ provide: TransactionApiService, useValue: api }],
    });
    const fixture = TestBed.createComponent(DecisionExplanation);
    fixture.componentRef.setInput('transaction', transaction);
    fixture.detectChanges();
    return fixture;
  }

  const click = (element: HTMLElement) => (element.querySelector('button') as HTMLButtonElement).click();

  beforeEach(() => api.explanation.mockReset());

  it('requests nothing until asked, then renders model text as plain text', () => {
    api.explanation.mockReturnValue(of(answer));
    const fixture = render(blocked);
    const element = fixture.nativeElement as HTMLElement;
    expect(api.explanation).not.toHaveBeenCalled();
    click(element);
    fixture.detectChanges();
    expect(api.explanation).toHaveBeenCalledWith('tx-1');
    expect(element.textContent).toContain('<b>Blocked</b> by the recorded policy.');
    expect(element.querySelector('b')).toBeNull();
    expect(element.textContent).toContain('Deterministic policy: the risk limit was exceeded.');
    expect(element.textContent).toContain('Required next step:');
    expect(element.textContent).toContain('Risk score 80 is above the limit of 30');
  });

  it('does not ask again for unchanged evidence, but does once the evidence changes', () => {
    api.explanation.mockReturnValue(of(answer));
    const fixture = render(blocked);
    const element = fixture.nativeElement as HTMLElement;
    click(element);
    fixture.detectChanges();
    click(element);
    fixture.componentRef.setInput('transaction', { ...blocked });
    fixture.detectChanges();
    expect((element.querySelector('button') as HTMLButtonElement).disabled).toBe(true);
    expect(api.explanation).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('transaction', { ...blocked, status: 'FAILED', bankOutcome: 'FAILED' });
    fixture.detectChanges();
    click(element);
    expect(api.explanation).toHaveBeenCalledTimes(2);
  });

  it('shows the recorded deterministic reason when the request fails', () => {
    api.explanation.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));
    const fixture = render(blocked);
    const element = fixture.nativeElement as HTMLElement;
    click(element);
    fixture.detectChanges();
    expect(element.textContent).toContain('Too many explanation requests');
    expect(element.textContent).toContain('Risk score 80 is above the limit of 30, so banking was blocked.');
  });
});
