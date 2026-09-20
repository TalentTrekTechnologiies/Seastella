import { useState } from 'react';
import {
  resendInvitation,
  sendPasswordReset,
  setAccountStatus,
  updateAccount,
  type AccountSummary,
  type LinkSent,
} from '@/api/admin';
import { ApiError } from '@/api/client';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Pill } from '@/design-system/StatusBadge';
import { formatDateTime, relativeTime } from '@/lib/format';
import './admin.css';

/**
 * What happened to an invitation or reset email.
 *
 * <p>Nobody but the account holder chooses or sees a password. When the email
 * reached the mail server this only confirms it. When it did not - email not
 * set up on this server, or the server refused it - the link is shown so it can
 * be passed on another way; it works once, for its holder, until it expires.
 */
export function LinkSentDialog({
  result,
  kind,
  onClose,
}: {
  result: LinkSent;
  kind: 'invitation' | 'reset';
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const first = result.user.fullName.split(' ')[0];
  const what = kind === 'invitation' ? 'invitation' : 'password reset link';
  const expires = formatDateTime(result.expiresAt);
  const link = result.email === 'SENT' ? null : result.link;

  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  const title =
    result.email === 'SENT'
      ? kind === 'invitation'
        ? 'Invitation sent'
        : 'Reset link sent'
      : kind === 'invitation'
        ? 'Account created. Invitation not emailed'
        : 'Reset link not emailed';

  return (
    <Dialog
      title={title}
      subtitle={`${result.user.fullName} · ${result.user.roleLabel}`}
      onClose={onClose}
      width={540}
      footer={
        <>
          {link && <Button onClick={copy}>{copied ? 'Copied' : 'Copy link'}</Button>}
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        </>
      }
    >
      {result.email === 'SENT' ? (
        <p className="otp__note">
          {kind === 'invitation'
            ? `We emailed ${first} at ${result.user.email}. They choose their own password from the link, which works once and expires ${expires}.`
            : `We emailed ${first} at ${result.user.email}. Their current password keeps working until they use the link, which expires ${expires}.`}
        </p>
      ) : (
        <>
          <p className="otp__note">
            {result.email === 'NOT_CONFIGURED'
              ? `Email is not set up on this server, so the ${what} was not sent.`
              : `The mail server did not accept the ${what}.`}{' '}
            Send this link to {first} yourself, by a channel only they can read.
          </p>
          <div className="otp">
            <div className="otp__row">
              <span className="otp__label">{kind === 'invitation' ? 'Invitation link' : 'Reset link'}</span>
              <span className="otp__value mono" id="account-link">
                {link}
              </span>
            </div>
            <div className="otp__row">
              <span className="otp__label">Expires</span>
              <span className="otp__value">{expires}. Works once.</span>
            </div>
          </div>
        </>
      )}
    </Dialog>
  );
}

/**
 * Suspend / reactivate and invitation / reset-link actions for account lists,
 * with the confirmation and result dialogs they need.
 */
export function useAccountActions(onChanged: () => void) {
  const [confirming, setConfirming] = useState<AccountSummary | null>(null);
  const [editing, setEditing] = useState<AccountSummary | null>(null);
  const [sent, setSent] = useState<{ result: LinkSent; kind: 'invitation' | 'reset' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const changeStatus = async (account: AccountSummary) => {
    setBusy(true);
    setError(null);
    try {
      await setAccountStatus(account.id, account.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED');
      setConfirming(null);
      onChanged();
    } catch (e) {
      setError(errorText(e, 'The account could not be changed.'));
    } finally {
      setBusy(false);
    }
  };

  const sendLink = async (account: AccountSummary) => {
    setError(null);
    const kind = account.status === 'INVITED' ? 'invitation' : 'reset';
    try {
      const result = kind === 'invitation' ? await resendInvitation(account.id) : await sendPasswordReset(account.id);
      setSent({ result, kind });
      onChanged();
    } catch (e) {
      setError(errorText(e, kind === 'invitation' ? 'The invitation could not be sent.' : 'The reset link could not be sent.'));
    }
  };

  const reactivating = confirming?.status === 'SUSPENDED';

  const dialogs = (
    <>
      {confirming && (
        <Dialog
          title={reactivating ? 'Reactivate account' : 'Suspend account'}
          subtitle={`${confirming.fullName} · ${confirming.roleLabel}`}
          onClose={() => setConfirming(null)}
          width={480}
          footer={
            <>
              <Button onClick={() => setConfirming(null)}>Cancel</Button>
              <Button variant={reactivating ? 'primary' : 'danger'} disabled={busy} onClick={() => changeStatus(confirming)}>
                {reactivating ? 'Reactivate' : 'Suspend'}
              </Button>
            </>
          }
        >
          <p className="otp__note">
            {reactivating
              ? 'They can sign in again with their existing password. If they never accepted their invitation, the account returns to Invited and you can send a new one.'
              : confirming.status === 'INVITED'
                ? 'Their invitation link stops working. Their records stay as they are.'
                : 'They are signed out at once and cannot sign in until reactivated. Their records stay as they are.'}
          </p>
          <FormError message={error} />
        </Dialog>
      )}
      {editing && (
        <EditAccountDialog
          account={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            onChanged();
          }}
        />
      )}
      {sent && <LinkSentDialog result={sent.result} kind={sent.kind} onClose={() => setSent(null)} />}
    </>
  );

  return { onStatus: setConfirming, onSendLink: sendLink, onEdit: setEditing, dialogs, error: confirming ? null : error };
}

/** Account row actions: suspend or reactivate, and resend an invitation or send a reset link. */
export function AccountLine({
  account,
  detail,
  canManage,
  onStatus,
  onSendLink,
  onEdit,
  extra,
}: {
  account: AccountSummary;
  detail?: React.ReactNode;
  canManage: boolean;
  onStatus: (account: AccountSummary) => void;
  onSendLink: (account: AccountSummary) => void;
  onEdit?: (account: AccountSummary) => void;
  extra?: React.ReactNode;
}) {
  const suspended = account.status === 'SUSPENDED';
  const invited = account.status === 'INVITED';
  return (
    <li className={`acct${suspended ? ' acct--off' : ''}`}>
      <div className="acct__who">
        <span className="acct__avatar" aria-hidden="true">
          {initials(account.fullName)}
        </span>
        <div>
          <div className="acct__name">
            {account.fullName}
            {suspended && <Pill size="sm">Suspended</Pill>}
            {invited && <Pill size="sm">Invited</Pill>}
          </div>
          <div className="acct__meta">
            {account.email} ·{' '}
            {invited
              ? `invited ${relativeTime(account.createdAt)}, not accepted yet`
              : account.lastLoginAt
                ? `signed in ${relativeTime(account.lastLoginAt)}`
                : 'not signed in yet'}
          </div>
          {detail && <div className="acct__detail">{detail}</div>}
        </div>
      </div>
      {(canManage || extra) && (
        <div className="acct__actions">
          {extra}
          {canManage && (
            <>
              {onEdit && (
                <Button variant="ghost" onClick={() => onEdit(account)}>
                  Edit
                </Button>
              )}
              {!suspended && (
                <Button variant="ghost" onClick={() => onSendLink(account)}>
                  {invited ? 'Resend invitation' : 'Send reset link'}
                </Button>
              )}
              <Button variant="ghost" onClick={() => onStatus(account)}>
                {suspended ? 'Reactivate' : 'Suspend'}
              </Button>
            </>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * Correcting a name or a sign-in address (IAM-08).
 *
 * <p>A misspelt name is cosmetic; the address is not — it is how that person
 * signs in and where their links are sent, so changing it ends their sessions
 * and cancels any invitation or reset link already out. The dialog says so
 * before it is saved, not after.
 */
function EditAccountDialog({
  account,
  onClose,
  onSaved,
}: {
  account: AccountSummary;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [fullName, setFullName] = useState(account.fullName);
  const [email, setEmail] = useState(account.email);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const emailChanged = email.trim().toLowerCase() !== account.email.toLowerCase();
  const nameChanged = fullName.trim() !== account.fullName;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await updateAccount(account.id, {
        fullName: nameChanged ? fullName.trim() : undefined,
        email: emailChanged ? email.trim() : undefined,
      });
      onSaved();
    } catch (e) {
      setError(errorText(e, 'The account could not be changed.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Edit account"
      subtitle={`${account.fullName} · ${account.roleLabel}`}
      onClose={onClose}
      width={480}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || (!nameChanged && !emailChanged) || fullName.trim() === '' || email.trim() === ''}
            onClick={save}
          >
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <Field label="Full name" htmlFor="acct-name">
        <input id="acct-name" className="input" maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
      </Field>
      <Field label="Sign-in address" htmlFor="acct-email">
        <input id="acct-email" className="input" type="email" maxLength={200} value={email} onChange={(e) => setEmail(e.target.value)} />
      </Field>
      {emailChanged && (
        <p className="otp__note">
          Changing the address signs {account.fullName.split(' ')[0]} out everywhere and cancels any invitation or reset
          link already sent. They sign in with the new address from then on.
        </p>
      )}
      <FormError message={error} />
    </Dialog>
  );
}

export function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

function initials(name: string) {
  return name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0])
    .join('')
    .toUpperCase();
}
