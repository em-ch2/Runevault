import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { AuthService as Auth0Service } from '@auth0/auth0-angular';
import { firstValueFrom, Subject } from 'rxjs';
import { environment } from '../../environments/environment';
import { FeedEvent, parseFeedEvent, parseTransportEvent, TransferTransportEvent } from './feed-event';
import { MOCK_SCRIPTS, MockScenario } from './mock-feed';

export type FeedConnection = 'idle' | 'mock' | 'connecting' | 'open' | 'closed';

// The only place the dashboard talks to the live feed. Swap the backend in via environment.feedUrl.
@Injectable({ providedIn: 'root' })
export class FeedService {
  readonly events = new Subject<FeedEvent>();
  readonly transportEvents = new Subject<TransferTransportEvent>();
  readonly connection = signal<FeedConnection>('idle');
  readonly isMock = environment.feedUrl === '';

  private socket: WebSocket | null = null;
  private reconnectDelayMs = 1000;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private mockTimers: ReturnType<typeof setTimeout>[] = [];
  private stopped = true;
  private readonly http = inject(HttpClient);
  private readonly auth0 = inject(Auth0Service);

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    if (this.isMock) {
      this.playMock('approved');
    } else {
      this.connect();
    }
  }

  stop(): void {
    this.stopped = true;
    for (const timer of this.mockTimers) clearTimeout(timer);
    this.mockTimers = [];
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    if (this.socket !== null) {
      this.socket.onclose = null;
      this.socket.close();
      this.socket = null;
    }
    this.connection.set('idle');
  }

  playMock(scenario: MockScenario): void {
    if (!this.isMock) return;
    for (const timer of this.mockTimers) clearTimeout(timer);
    this.mockTimers = [];
    this.connection.set('mock');
    let elapsed = 0;
    for (const step of MOCK_SCRIPTS[scenario]) {
      elapsed += step.afterMs;
      this.mockTimers.push(
        setTimeout(() => {
          const event = parseFeedEvent({ ...step.event, at: new Date().toISOString() });
          if (event !== null) this.events.next(event);
        }, elapsed),
      );
    }
  }

  private async connect(): Promise<void> {
    this.connection.set('connecting');
    let ticket: string;
    try {
      const token = await firstValueFrom(this.auth0.getAccessTokenSilently({
        authorizationParams: { audience: environment.auth0.audience },
      }));
      const issued = await firstValueFrom(this.http.post<{ ticket: string; expiresAt: string }>(
        '/api/ws-ticket', {}, { headers: new HttpHeaders({ Authorization: `Bearer ${token}` }) },
      ));
      ticket = issued.ticket;
      if (this.stopped) return;
    } catch {
      this.retry();
      return;
    }
    let socket: WebSocket;
    try {
      socket = new WebSocket(environment.feedUrl, [`runevault.ticket.${ticket}`]);
    } catch {
      this.retry();
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.reconnectDelayMs = 1000;
      this.connection.set('open');
    };
    socket.onmessage = (message: MessageEvent) => {
      if (typeof message.data !== 'string' || message.data.length > 8192) return;
      let raw: unknown;
      try {
        raw = JSON.parse(message.data);
      } catch {
        return;
      }
      const transport = parseTransportEvent(raw);
      if (transport !== null) { this.transportEvents.next(transport); return; }
      const event = parseFeedEvent(raw);
      if (event !== null) this.events.next(event);
    };
    socket.onclose = () => {
      this.socket = null;
      this.connection.set('closed');
      if (this.stopped) return;
      this.retry();
    };
  }

  private retry(): void {
    if (this.stopped) return;
    this.connection.set('closed');
    this.reconnectTimer = setTimeout(() => { void this.connect(); }, this.reconnectDelayMs);
    this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 15000);
  }
}
