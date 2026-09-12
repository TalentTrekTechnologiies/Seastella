import { useState } from 'react';
import { ApiError } from '@/api/client';
import { useAuth } from './AuthContext';
import './signin.css';

/**
 * Sign-in against the real authentication endpoint.
 *
 * <p>The demo accounts below are a convenience for filling the form — they
 * submit real credentials through `/api/v1/auth/login` and receive a real
 * token. There is deliberately no client-side role switch: a control that
 * changed role without re-authenticating would be a lie about what the server
 * will allow.
 */
export function SignIn() {
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(email, password);
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : 'Could not reach the server. Is the API running?',
      );
      setBusy(false);
    }
  }

  function fill(demoEmail: string) {
    setEmail(demoEmail);
    setPassword(DEMO_PASSWORD);
    setError(null);
  }

  return (
    <div className="signin">
      <div className="signin__panel">
        <div className="signin__brand">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 2l1.8 5.6H19l-4.4 3.4 1.7 5.4L12 13l-4.3 3.4 1.7-5.4L5 7.6h5.2z"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinejoin="round"
            />
            <path d="M4 20h16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <div>
            <h1>SeaStella</h1>
            <p>Maritime Navigation Equipment Asset &amp; Service Management</p>
          </div>
        </div>

        <form onSubmit={submit} className="signin__form">
          <label className="field">
            <span>Email</span>
            <input
              id="signin-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label className="field">
            <span>Password</span>
            <input
              id="signin-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          {error && (
            <p className="signin__error" role="alert">
              {error}
            </p>
          )}

          <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
            {busy ? 'Signing in\u2026' : 'Sign in'}
          </button>
        </form>

        <div className="signin__demo">
          <p className="signin__demo-title">Demo accounts</p>
          <p className="signin__demo-note">
            Seeded users. Each signs in for real and receives a role-scoped token.
          </p>
          <ul>
            {DEMO_ACCOUNTS.map((a) => (
              <li key={a.email}>
                <button type="button" onClick={() => fill(a.email)}>
                  <span className="signin__demo-role">{a.role}</span>
                  <span className="signin__demo-email mono">{a.email}</span>
                  <span className="signin__demo-scope">{a.scope}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

const DEMO_PASSWORD = 'SeaStella#Demo2026';

const DEMO_ACCOUNTS = [
  {
    role: 'Platform Administrator',
    email: 'admin@seastella.example',
    scope: 'Entire platform',
  },
  {
    role: 'Technical Head',
    email: 'tech.head@acme-shipmanagement.example',
    scope: 'Acme fleet (4 vessels)',
  },
  {
    role: 'Ship Manager',
    email: 'd.fernandes@acme-shipmanagement.example',
    scope: 'Kestrel Trader, Brahmaputra',
  },
  {
    role: 'Captain',
    email: 'master.kestrel@acme-shipmanagement.example',
    scope: 'MV Kestrel Trader only',
  },
  {
    role: 'Service Coordinator',
    email: 'coordinator@seastella.example',
    scope: 'Acme + Nordic (2 organizations)',
  },
  {
    role: 'Service Engineer',
    email: 't.okafor@marine-electronics.example',
    scope: 'Assigned jobs only',
  },
];
