import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchBands, saveBands, type MaintenanceBand } from '@/api/thresholds';
import { Button, ConsoleHeader, Plate } from '@/design-system/Console';
import { Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { errorText } from '@/features/admin/AdminParts';
import './bands.css';

/**
 * How much warning the platform gives before a service falls due (SoW §11).
 *
 * <p>Two of the four colours are definitions rather than settings: past the due
 * date is Overdue, and due today is Due. What a fleet decides is how far ahead
 * the warning starts — and the two warning bands must meet, so no spare falls
 * between two colours.
 */
export function MaintenanceBandsPage() {
  const client = useQueryClient();
  const bands = useQuery({ queryKey: ['maintenance-bands'], queryFn: fetchBands });
  const [urgent, setUrgent] = useState('');
  const [approaching, setApproaching] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!bands.data) return;
    const urgentBand = bands.data.find((b) => b.statusCode === 'URGENT');
    const approachingBand = bands.data.find((b) => b.statusCode === 'APPROACHING');
    setUrgent(String(urgentBand?.toDays ?? ''));
    setApproaching(String(approachingBand?.toDays ?? ''));
  }, [bands.data]);

  if (bands.error) return <ErrorState error={bands.error} onRetry={() => bands.refetch()} />;

  const save = async () => {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      client.setQueryData(['maintenance-bands'], await saveBands(Number(urgent), Number(approaching)));
      client.invalidateQueries({ queryKey: ['dashboard'] });
      client.invalidateQueries({ queryKey: ['vessel-maintenance'] });
      setSaved(true);
    } catch (e) {
      setError(errorText(e, 'The bands could not be changed.'));
    } finally {
      setBusy(false);
    }
  };

  const current = bands.data ?? [];
  const changed =
    current.length > 0 &&
    (String(current.find((b) => b.statusCode === 'URGENT')?.toDays ?? '') !== urgent ||
      String(current.find((b) => b.statusCode === 'APPROACHING')?.toDays ?? '') !== approaching);

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Maintenance bands', strong: true }]}
        title="Maintenance bands"
        subtitle="How much warning every vessel gets before a service falls due. Changing this re-colours every tracked spare at once."
      />

      {saved && (
        <div className="notice notice--ok" role="status">
          <Icon name="check" size={20} />
          <div>
            <p className="notice__title">Bands updated</p>
            <p className="notice__body">Every dashboard and every alert now uses the new ranges.</p>
          </div>
        </div>
      )}

      <Plate title="Warning ranges" subtitle="Days before the due date">
        {bands.isLoading ? (
          <LoadingState rows={3} />
        ) : (
          <>
            <div className="bands__form">
              <Field label="Urgent from due date up to" htmlFor="band-urgent" hint="Days. Red-amber, the last stretch before it is due.">
                <input id="band-urgent" className="input" type="number" min={1} max={364} value={urgent} onChange={(e) => setUrgent(e.target.value)} />
              </Field>
              <Field label="Approaching up to" htmlFor="band-approaching" hint="Days. Must reach further out than urgent.">
                <input id="band-approaching" className="input" type="number" min={2} max={365} value={approaching} onChange={(e) => setApproaching(e.target.value)} />
              </Field>
              <Button variant="primary" onClick={save} disabled={busy || !changed}>
                {busy ? 'Applying…' : 'Apply to the fleet'}
              </Button>
            </div>
            <FormError message={error} />
          </>
        )}
      </Plate>

      <Plate title="The ladder" subtitle="What each colour means once the change is applied" flush>
        <ul className="bands__ladder">
          {current.map((band) => (
            <BandRow key={band.statusCode} band={band} urgent={Number(urgent)} approaching={Number(approaching)} />
          ))}
        </ul>
      </Plate>
    </div>
  );
}

function BandRow({ band, urgent, approaching }: { band: MaintenanceBand; urgent: number; approaching: number }) {
  const preview =
    band.statusCode === 'URGENT'
      ? `Within ${urgent || '—'} days`
      : band.statusCode === 'APPROACHING'
        ? `${(urgent || 0) + 1} to ${approaching || '—'} days`
        : band.statusCode === 'NORMAL'
          ? `More than ${approaching || '—'} days away`
          : band.description;

  return (
    <li className="bands__row">
      <span className="bands__swatch" style={{ background: band.colour }} aria-hidden="true" />
      <div>
        <div className="bands__label">
          {band.label}
          {!band.configurable && <span className="bands__fixed">fixed</span>}
        </div>
        <div className="bands__desc">{preview}</div>
      </div>
    </li>
  );
}
