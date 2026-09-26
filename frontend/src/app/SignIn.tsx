import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useAuth } from './AuthContext';
import './signin.css';
import './brand.css';

/**
 * Sign-in against the authentication endpoint. Accounts are created by
 * invitation and nobody's password is shown anywhere, so there is nothing to
 * pre-fill.
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
      setError(err instanceof ApiError ? err.message : 'Could not reach Thawe Marine. Check your connection and try again.');
      setBusy(false);
    }
  }

  return (
    <AuthFrame>
      <h2 className="signin__title">Sign in</h2>
      <p className="signin__subtitle">Use your Thawe Marine account.</p>

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
            <Link to="/forgot-password" className="signin__link">
              Forgot password?
            </Link>
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

    </AuthFrame>
  );
}

/** The signed-out frame: the ocean panel carries the identity, the card carries the task. */
export function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <div className="signin">
      <section className="signin__ocean">
        <div className="signin__brand">
          <span className="brand-logo" role="img" aria-label="Thawe Marine Services">
            <img className="brand-logo__wing" src={`${import.meta.env.BASE_URL}thawe-logo-wing.png`} alt="" />
            <img className="brand-logo__name" src={`${import.meta.env.BASE_URL}thawe-logo-name.png`} alt="" />
          </span>
        </div>

        <div className="signin__pitch">
          <h1>Every radar, gyro and EPIRB across your fleet — serviced on time.</h1>
          <p>
            Thawe Marine tracks the navigation and GMDSS equipment fit of every vessel, shows what is falling due, and
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
