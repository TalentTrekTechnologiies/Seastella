import { useState } from 'react';
import { changeOwnEmail } from '@/api/account';
import { ApiError } from '@/api/client';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { useAuth } from './AuthContext';

/**
 * Changing one's own sign-in address.
 *
 * <p>The address is how this person signs in and where a forgotten-password
 * link goes, so it needs the current password, and the new address is typed
 * twice: a typo here would lock them out of resetting their password. Other
 * devices are signed out; this one carries on with a fresh session.
 *
 * <p>It is also the only way a Platform Admin can correct their own address.
 */
export function ChangeEmailDialog({ onClose }: { onClose: () => void }) {
  const { user, adoptSession } = useAuth();
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [current, setCurrent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setError(null);
    const address = next.trim();
    if (address.toLowerCase() !== confirm.trim().toLowerCase()) {
      setError('The two email addresses do not match.');
      return;
    }
    setBusy(true);
    try {
      const signedIn = await changeOwnEmail(current, address);
      adoptSession(signedIn);
      setDone(signedIn.user.email);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach Thawe Marine. Try again.');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <Dialog
        title="Email changed"
        onClose={onClose}
        width={460}
        footer={
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <p className="otp__note">
          From now on, sign in with <b>{done}</b>. Password-reset emails will go there too. Thawe Marine has
          signed you out on every other device.
        </p>
      </Dialog>
    );
  }

  return (
    <Dialog
      title="Change email"
      subtitle={user ? `Currently ${user.email}` : undefined}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !next || !confirm || !current} onClick={() => submit()}>
            {busy ? 'Saving…' : 'Change email'}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} style={{ display: 'grid', gap: 16 }}>
        <Field label="New email" htmlFor="em-new" hint="You will sign in with this address from now on.">
          <input
            id="em-new"
            className="input"
            type="email"
            autoComplete="email"
            maxLength={254}
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
        </Field>
        <Field label="Confirm new email" htmlFor="em-confirm">
          <input
            id="em-confirm"
            className="input"
            type="email"
            autoComplete="off"
            maxLength={254}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </Field>
        <Field label="Current password" htmlFor="em-current" hint="To confirm it is you.">
          <input
            id="em-current"
            className="input"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </Field>
        {/* Enter submits from any field. */}
        <button type="submit" hidden />
        <FormError message={error} />
      </form>
    </Dialog>
  );
}
