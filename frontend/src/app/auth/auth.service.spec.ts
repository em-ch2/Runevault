import { TestBed } from '@angular/core/testing';
import { AuthService, DEMO_EMAIL, DEMO_PASSWORD } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;

  beforeEach(() => {
    localStorage.clear();
    auth = TestBed.inject(AuthService);
  });

  it('signs up, signs out, and signs back in', async () => {
    expect(await auth.signUp('Ada', 'Ada@Example.com', 'correct horse')).toBeNull();
    expect(auth.user()?.email).toBe('ada@example.com');
    auth.signOut();
    expect(auth.user()).toBeNull();
    expect(await auth.signIn('ada@example.com', 'correct horse')).toBeNull();
    expect(auth.user()?.name).toBe('Ada');
  });

  it('rejects duplicate emails and wrong passwords', async () => {
    await auth.signUp('Ada', 'ada@example.com', 'correct horse');
    expect(await auth.signUp('Ada 2', 'ada@example.com', 'another one')).not.toBeNull();
    auth.signOut();
    expect(await auth.signIn('ada@example.com', 'wrong password')).not.toBeNull();
    expect(auth.user()).toBeNull();
  });

  it('never stores the plain password', async () => {
    await auth.signUp('Ada', 'ada@example.com', 'correct horse');
    expect(localStorage.getItem('poneglyph.users')).not.toContain('correct horse');
  });

  it('accepts the demo account', async () => {
    expect(await auth.signIn(DEMO_EMAIL, DEMO_PASSWORD)).toBeNull();
    expect(auth.user()?.email).toBe(DEMO_EMAIL);
  });
});
