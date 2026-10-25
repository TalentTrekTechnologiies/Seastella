import { useEffect, useState } from 'react';
import { fetchOwnWhatsApp, saveOwnWhatsApp } from '@/api/account';
import { ApiError } from '@/api/client';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { LoadingState } from '@/design-system/States';

/**
 * A person's own WhatsApp number for alerts.
 *
 * <p>Only the person can give it, because only they can agree to it: Meta
 * forbids messaging anyone who has not opted in, so the number is kept only
 * with the box ticked. Which alerts arrive is the Platform Admin's choice;
 * whether they arrive on this phone at all is the person's.
 */
export function WhatsAppDialog({ onClose }: { onClose: () => void }) {
  const [loaded, setLoaded] = useState(false);
  const [saved, setSaved] = useState<string | undefined>();
  const [number, setNumber] = useState('');
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    fetchOwnWhatsApp()
      .then((w) => {
        setSaved(w.optedIn ? w.number : undefined);
        setNumber(w.optedIn && w.number ? w.number : '');
        setConsent(w.optedIn);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Could not reach Thawe Marine. Try again.'))
      .finally(() => setLoaded(true));
  }, []);

  const save = async (value: string, agreed: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const w = await saveOwnWhatsApp(value, agreed);
      setDone(
        w.optedIn && w.number
          ? `Alerts will come on WhatsApp to ${w.number}.`
          : 'WhatsApp alerts are off. You still get them in the app and by email.',
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not reach Thawe Marine. Try again.');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <Dialog
        title="WhatsApp alerts"
        onClose={onClose}
        width={460}
        footer={
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <p className="otp__note">{done}</p>
      </Dialog>
    );
  }

  return (
    <Dialog
      title="WhatsApp alerts"
      subtitle={saved ? `Currently ${saved}` : 'Not set up for you'}
      onClose={onClose}
      width={460}
      footer={
        <>
          {saved && (
            <Button disabled={busy} onClick={() => save('', false)}>
              Stop WhatsApp alerts
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !loaded || !number.trim() || !consent} onClick={() => save(number, consent)}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      {!loaded ? (
        <LoadingState rows={2} />
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (number.trim() && consent) save(number, consent);
          }}
          style={{ display: 'grid', gap: 16 }}
        >
          <p className="otp__note" style={{ margin: 0 }}>
            Urgent alerts — a new request, an invoice to accept, equipment in the red — can also reach you on
            WhatsApp. You still get everything in the app and by email.
          </p>
          <Field
            label="WhatsApp number"
            htmlFor="wa-number"
            hint="With the country code, for example +91 98765 43210. A 10-digit Indian mobile number is fine as it is."
          >
            <input
              id="wa-number"
              className="input"
              type="tel"
              autoComplete="tel"
              inputMode="tel"
              maxLength={24}
              value={number}
              onChange={(e) => setNumber(e.target.value)}
            />
          </Field>
          <label className="checkline">
            <input id="wa-consent" type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
            <span>
              I agree to receive Thawe Marine alerts on WhatsApp
              <small>You can stop them here at any time.</small>
            </span>
          </label>
          <button type="submit" hidden />
          <FormError message={error} />
        </form>
      )}
    </Dialog>
  );
}
