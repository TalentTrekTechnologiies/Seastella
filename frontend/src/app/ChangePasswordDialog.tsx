import { useState } from 'react';
import { changeOwnPassword, PASSWORD_MIN_LENGTH } from '@/api/account';
import { ApiError } from '@/api/client';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { useAuth } from './AuthContext';

/**
 * Changing one's own password. Needs the current one; every other session on
 * other devices ends, and this one carries on with a fresh token.
 */
export function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const { user, adoptSession } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setError(null);
    if (next.length < PASSWORD_MIN_LENGTH) {
      setError(`Use at least ${PASSWORD_MIN_LENGTH} characters for the new password.`);
      return;
    }
    if (next !== confirm) {
      setError('The two new passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      adoptSession(await changeOwnPassword(current, next));
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach SeaStella. Try again.');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <Dialog
        title="Password changed"
        onClose={onClose}
        width={460}
        footer={
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <p className="otp__note">
          Use your new password next time you sign in. SeaStella has signed you out on every other device.
        </p>
      </Dialog>
    );
  }

  return (
    <Dialog
      title="Change password"
      subtitle={user?.email}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !current || !next || !confirm} onClick={() => submit()}>
            {busy ? 'Saving…' : 'Change password'}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} style={{ display: 'grid', gap: 16 }}>
        <input type="email" autoComplete="username" value={user?.email ?? ''} readOnly hidden />
        <Field label="Current password" htmlFor="pw-current">
          <input
            id="pw-current"
            className="input"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </Field>
        <Field
          label="New password"
          htmlFor="pw-new"
          hint={`At least ${PASSWORD_MIN_LENGTH} characters. A short phrase you will remember works well.`}
        >
          <input
            id="pw-new"
            className="input"
            type="password"
            autoComplete="new-password"
            maxLength={128}
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
        </Field>
        <Field label="Confirm new password" htmlFor="pw-confirm">
          <input
            id="pw-confirm"
            className="input"
            type="password"
            autoComplete="new-password"
            maxLength={128}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </Field>
        {/* Enter submits from any field. */}
        <button type="submit" hidden />
        <FormError message={error} />
      </form>
    </Dialog>
  );
}
