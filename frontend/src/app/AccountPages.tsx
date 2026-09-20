import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  acceptInvitation,
  completePasswordReset,
  fetchInvitation,
  fetchPasswordReset,
  requestPasswordReset,
  PASSWORD_MIN_LENGTH,
  type PendingAccount,
} from '@/api/account';
import { ApiError } from '@/api/client';
import type { LoginResponse } from '@/api/types';
import { useAuth } from './AuthContext';
import { AuthFrame } from './SignIn';

/**
 * The pages reached from account emails, before the person can sign in:
 * accepting an invitation, and choosing a new password from a reset link.
 * Both end with the person signed in on a password only they know.
 */

export function AcceptInvitationPage() {
  const { token = '' } = useParams();
  return (
    <LinkPage
      token={token}
      load={fetchInvitation}
      submit={acceptInvitation}
      title="Set up your account"
      intro={(p) => `Welcome, ${p.fullName.split(' ')[0]}. Choose the password you will sign in with.`}
      action="Activate account"
      expired={
        <>
          This invitation is no longer valid. It may have been used already, replaced by a newer one, or expired. Ask
          the person who invited you to send a new invitation.
        </>
      }
    />
  );
}

export function ResetPasswordPage() {
  const { token = '' } = useParams();
  return (
    <LinkPage
      token={token}
      load={fetchPasswordReset}
      submit={completePasswordReset}
      title="Choose a new password"
      intro={() => 'Setting a new password signs you out of SeaStella on every other device.'}
      action="Save password and sign in"
      expired={
        <>
          This reset link is no longer valid. It may have been used already, replaced by a newer one, or expired. Reset
          links last one hour. <Link to="/forgot-password" className="signin__link">Request a new link</Link>.
        </>
      }
    />
  );
}

function LinkPage({
  token,
  load,
  submit,
  title,
  intro,
  action,
  expired,
}: {
  token: string;
  load: (token: string) => Promise<PendingAccount>;
  submit: (token: string, password: string) => Promise<LoginResponse>;
  title: string;
  intro: (pending: PendingAccount) => string;
  action: string;
  expired: React.ReactNode;
}) {
  const { adoptSession } = useAuth();
  const navigate = useNavigate();
  const pending = useQuery({ queryKey: ['account-link', title, token], queryFn: () => load(token), retry: false });
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [gone, setGone] = useState(false);

  useDocumentTitle(`${title} · SeaStella`);

  const linkInvalid = gone || (pending.error instanceof ApiError && (pending.error.status === 410 || pending.error.status === 404));

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (password.length < PASSWORD_MIN_LENGTH) {
      setError(`Use at least ${PASSWORD_MIN_LENGTH} characters. A short phrase is easiest to remember.`);
      return;
    }
    if (password !== confirm) {
      setError('The two passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      adoptSession(await submit(token, password));
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 410) {
        setGone(true);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not reach SeaStella. Check your connection and try again.');
      }
      setBusy(false);
    }
  };

  return (
    <AuthFrame>
      <h2 className="signin__title">{title}</h2>

      {pending.isLoading ? (
        <p className="signin__subtitle" role="status">
          Checking your link…
        </p>
      ) : linkInvalid ? (
        <>
          <p className="signin__error" role="alert" style={{ marginTop: 18 }}>
            {expired}
          </p>
          <p className="signin__back">
            <Link to="/" className="signin__link">
              Go to sign in
            </Link>
          </p>
        </>
      ) : pending.error || !pending.data ? (
        <p className="signin__error" role="alert" style={{ marginTop: 18 }}>
          Could not check this link. Check your connection and reload the page.
        </p>
      ) : (
        <>
          <p className="signin__subtitle">{intro(pending.data)}</p>
          <div className="signin__who">
            <b>{pending.data.fullName}</b>
            <span>
              {pending.data.email} · {pending.data.roleLabel}
            </span>
          </div>

          <form onSubmit={onSubmit} className="signin__form">
            {/* Lets password managers save the new password against the right account. */}
            <input type="email" autoComplete="username" value={pending.data.email} readOnly hidden />
            <label className="field">
              <span>New password</span>
              <input
                id="new-password"
                type={show ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                minLength={PASSWORD_MIN_LENGTH}
                maxLength={128}
                required
                aria-describedby="password-rule"
              />
              <small id="password-rule" className="field__hint">
                At least {PASSWORD_MIN_LENGTH} characters. A short phrase you will remember works well, such as four
                unrelated words.
              </small>
            </label>
            <label className="field">
              <span>Confirm password</span>
              <input
                id="confirm-password"
                type={show ? 'text' : 'password'}
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
                maxLength={128}
                required
              />
            </label>
            <label className="field__check">
              <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} />
              Show passwords
            </label>

            {error && (
              <p className="signin__error" role="alert">
                {error}
              </p>
            )}

            <button type="submit" className="cbtn cbtn--primary cbtn--lg" disabled={busy}>
              {busy ? 'Saving…' : action}
            </button>
          </form>
        </>
      )}
    </AuthFrame>
  );
}

/** "Forgot password": the answer is the same whether or not the address has an account. */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useDocumentTitle('Reset your password · SeaStella');

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await requestPasswordReset(email.trim());
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach SeaStella. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame>
      <h2 className="signin__title">Reset your password</h2>
      {sent ? (
        <>
          <p className="signin__done" role="status">
            If <b>{email.trim()}</b> belongs to an active SeaStella account, we have emailed it a link to choose a new
            password. The link lasts one hour. If nothing arrives within a few minutes, check your spam folder or ask
            your administrator to send you a reset link.
          </p>
          <p className="signin__back">
            <Link to="/" className="signin__link">
              Back to sign in
            </Link>
          </p>
        </>
      ) : (
        <>
          <p className="signin__subtitle">Enter the email you sign in with and we will send you a reset link.</p>
          <form onSubmit={onSubmit} className="signin__form">
            <label className="field">
              <span>Email</span>
              <input
                id="forgot-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
                required
              />
            </label>
            {error && (
              <p className="signin__error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" className="cbtn cbtn--primary cbtn--lg" disabled={busy}>
              {busy ? 'Sending…' : 'Send reset link'}
            </button>
          </form>
          <p className="signin__back">
            <Link to="/" className="signin__link">
              Back to sign in
            </Link>
          </p>
        </>
      )}
    </AuthFrame>
  );
}

function useDocumentTitle(title: string) {
  useEffect(() => {
    const previous = document.title;
    document.title = title;
    return () => {
      document.title = previous;
    };
  }, [title]);
}