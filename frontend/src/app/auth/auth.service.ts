import { Injectable, signal } from '@angular/core';

export interface AuthUser {
  name: string;
  email: string;
}

interface StoredUser {
  name: string;
  email: string;
  salt: string;
  hash: string;
}

export const DEMO_EMAIL = 'demo@poneglyph.dev';
export const DEMO_PASSWORD = 'glyphstone';

const USERS_KEY = 'poneglyph.users';
const SESSION_KEY = 'poneglyph.session';

// Mock, browser-only accounts for the demo. Replace with Auth0 (authorization code + PKCE) keeping
// the same surface: user signal, signUp, signIn, signOut. Returned strings are error messages.
@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly user = signal<AuthUser | null>(null);

  constructor() {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      if (raw !== null) {
        const parsed = JSON.parse(raw) as AuthUser;
        if (typeof parsed.name === 'string' && typeof parsed.email === 'string') this.user.set(parsed);
      }
    } catch {
      this.user.set(null);
    }
  }

  async signUp(name: string, email: string, password: string): Promise<string | null> {
    const cleanName = name.trim();
    const cleanEmail = email.trim().toLowerCase();
    if (cleanName.length < 1 || cleanName.length > 60) return 'Enter a name up to 60 characters.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail) || cleanEmail.length > 120) return 'Enter a valid email.';
    if (password.length < 8) return 'Password must be at least 8 characters.';
    if (cleanEmail === DEMO_EMAIL) return 'That email is reserved for the demo account.';

    let users: StoredUser[] = [];
    try {
      users = JSON.parse(localStorage.getItem(USERS_KEY) ?? '[]') as StoredUser[];
    } catch {
      users = [];
    }
    if (users.some((u) => u.email === cleanEmail)) return 'An account with that email already exists.';

    const saltBytes = crypto.getRandomValues(new Uint8Array(16));
    const salt = Array.from(saltBytes, (b) => b.toString(16).padStart(2, '0')).join('');
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(salt + ':' + password));
    const hash = Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
    users.push({ name: cleanName, email: cleanEmail, salt, hash });
    try {
      localStorage.setItem(USERS_KEY, JSON.stringify(users));
    } catch {
      return 'This browser blocked local storage, so the account could not be saved.';
    }

    this.startSession({ name: cleanName, email: cleanEmail });
    return null;
  }

  async signIn(email: string, password: string): Promise<string | null> {
    const cleanEmail = email.trim().toLowerCase();
    if (cleanEmail === DEMO_EMAIL && password === DEMO_PASSWORD) {
      this.startSession({ name: 'Demo Approver', email: DEMO_EMAIL });
      return null;
    }

    let users: StoredUser[] = [];
    try {
      users = JSON.parse(localStorage.getItem(USERS_KEY) ?? '[]') as StoredUser[];
    } catch {
      users = [];
    }
    const found = users.find((u) => u.email === cleanEmail);
    if (found === undefined) return 'Email or password is incorrect.';
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(found.salt + ':' + password));
    const hash = Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
    if (hash !== found.hash) return 'Email or password is incorrect.';

    this.startSession({ name: found.name, email: found.email });
    return null;
  }

  signOut(): void {
    try {
      localStorage.removeItem(SESSION_KEY);
    } catch {
      // Session is still cleared in memory.
    }
    this.user.set(null);
  }

  private startSession(user: AuthUser): void {
    try {
      localStorage.setItem(SESSION_KEY, JSON.stringify(user));
    } catch {
      // Session lasts until reload.
    }
    this.user.set(user);
  }
}
