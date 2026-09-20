import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, DEMO_MODE } from '@/api/client';
import { useAuth } from './AuthContext';
import './signin.css';

/**
 * Sign-in against the real authentication endpoint.
 *
 * <p>Against a live backend this is an ordinary sign-in form: accounts are
 * created by invitation and nobody's password is shown anywhere, so there is
 * nothing to pre-fill.
 *
 * <p>In the frontend-only demo build the seeded accounts sign in with one
 * click, through the same login call, answered by the in-browser demo API; a
 * banner says the data is a seed snapshot so nobody mistakes it for live
 * operations.
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
      setError(err instanceof ApiError ? err.message : 'Could not reach SeaStella. Check your connection and try again.');
      setBusy(false);
    }
  }

  async function chooseDemoAccount(demoEmail: string) {
    setEmail(demoEmail);
    setError(null);
    setBusy(true);
    try {
      // The password lives only in the demo module, so a normal build never carries it.
      const { DEMO_PASSWORD } = await import('@/demo/demoApi');
      await signIn(demoEmail, DEMO_PASSWORD);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not sign in.');
      setBusy(false);
    }
  }

  return (
    <AuthFrame>
      <h2 className="signin__title">Sign in</h2>
      <p className="signin__subtitle">Use your SeaStella account.</p>

      {DEMO_MODE && (
        <p className="signin__banner" role="note">
          <strong>Demo</strong> — dashboards run on sample data captured from the SeaStella seed. No live backend is
          connected. Choose a role below to explore.
        </p>
      )}

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
          <span className="field__row">
            Password
            {!DEMO_MODE && (
              <Link to="/forgot-password" className="signin__link">
                Forgot password?
              </Link>
            )}
          </span>
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

        <button type="submit" className="cbtn cbtn--primary cbtn--lg" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      {DEMO_MODE && (
        <div className="signin__demo">
          <p className="signin__demo-title">Sign in as</p>
          <p className="signin__demo-note">One click opens that role's dashboard, scoped exactly as the server scopes it.</p>
          <ul>
            {DEMO_ACCOUNTS.map((a) => (
              <li key={a.email}>
                <button type="button" onClick={() => chooseDemoAccount(a.email)} disabled={busy}>
                  <span className="signin__demo-role">{a.role}</span>
                  <span className="signin__demo-email mono">{a.email}</span>
                  <span className="signin__demo-scope">{a.scope}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </AuthFrame>
  );
}

/** The signed-out frame: the ocean panel carries the identity, the card carries the task. */
export function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <div className="signin">
      <section className="signin__ocean">
        <div className="signin__brand">
          {/* A fix on a chart: crossed bearings through a plotted point. */}
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="7.5" stroke="currentColor" strokeWidth="1.4" />
            <circle cx="12" cy="12" r="2" fill="currentColor" />
            <path d="M12 1.5v4M12 18.5v4M1.5 12h4M18.5 12h4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
          <div>
            <strong>SeaStella</strong>
            <span>Maritime operations</span>
          </div>
        </div>

        <div className="signin__pitch">
          <h1>Every radar, gyro and EPIRB across your fleet — serviced on time.</h1>
          <p>
            SeaStella tracks the navigation and GMDSS equipment fit of every vessel, shows what is falling due, and
            carries each service request from the bridge to the engineer.
          </p>
          <ul className="signin__points">
            <li>Maintenance status for every spare, decided by one engine</li>
            <li>Service requests from troubleshooting to completion report</li>
            <li>Engineer dispatch held behind invoice acceptance</li>
            <li>Six role views, each scoped to what that role may see</li>
          </ul>
        </div>

        <p className="signin__foot">Maritime Navigation Equipment Asset &amp; Service Management</p>
      </section>

      <div className="signin__side">
        <div className="signin__panel">{children}</div>
      </div>
    </div>
  );
}

const DEMO_ACCOUNTS = [
  { role: 'Platform Administrator', email: 'admin@seastella.example', scope: 'Entire platform' },
  { role: 'Technical Head', email: 'tech.head@acme-shipmanagement.example', scope: 'Acme fleet (4 vessels)' },
  { role: 'Ship Manager', email: 'd.fernandes@acme-shipmanagement.example', scope: 'Kestrel Trader, Brahmaputra' },
  { role: 'Captain', email: 'master.kestrel@acme-shipmanagement.example', scope: 'MV Kestrel Trader only' },
  { role: 'Service Coordinator', email: 'coordinator@seastella.example', scope: 'Acme + Nordic (2 organizations)' },
  { role: 'Service Engineer', email: 't.okafor@marine-electronics.example', scope: 'Assigned jobs only' },
];
