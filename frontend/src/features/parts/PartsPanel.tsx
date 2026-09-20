import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { configurePart, countStock, fetchParts, type PartRow } from '@/api/parts';
import { Button, EmptyNote } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { errorText } from '@/features/admin/AdminParts';
import './parts.css';

/**
 * The replacement-part store on one vessel (SoW §9.5).
 *
 * <p>A count is what is on the shelf now, not a movement — that is how stock
 * is actually taken, and it means a missed issue corrects itself at the next
 * count. A count that drops below the minimum alerts the office straight away,
 * so "we're out of it" is never discovered at the moment it is needed.
 */
export function PartsPanel({
  vesselId,
  canSetMinimum,
}: {
  vesselId: number;
  /** The office sets what must be held; the bridge counts what is there. */
  canSetMinimum: boolean;
}) {
  const client = useQueryClient();
  const parts = useQuery({ queryKey: ['parts', vesselId], queryFn: () => fetchParts(vesselId) });
  const [counting, setCounting] = useState<PartRow | null>(null);
  const [configuring, setConfiguring] = useState<PartRow | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['parts', vesselId] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
    client.invalidateQueries({ queryKey: ['notifications'] });
  };

  const rows = parts.data ?? [];
  const short = rows.filter((p) => p.belowMinimum).length;

  if (parts.isLoading) return <LoadingState rows={3} />;
  if (rows.length === 0) {
    return <EmptyNote>No replacement parts are recorded for this vessel.</EmptyNote>;
  }

  return (
    <div className="parts">
      {short > 0 && (
        <p className="parts__summary">
          <b>{short}</b> {short === 1 ? 'part is' : 'parts are'} below the minimum to hold.
        </p>
      )}
      <ul className="parts__list">
        {rows.map((part) => (
          <li key={part.id} className={`parts__item${part.belowMinimum ? ' parts__item--short' : ''}`}>
            <div className="parts__main">
              <div className="parts__title">
                <b>{part.name}</b>
                {part.partNumber && <span className="mono">{part.partNumber}</span>}
                {part.belowMinimum && <Pill size="sm">Below minimum</Pill>}
              </div>
              <div className="parts__meta">
                {[part.spareName && `for ${part.spareName}`, part.location, part.manufacturer]
                  .filter(Boolean)
                  .join(' · ') || 'No location recorded'}
              </div>
            </div>
            <div className="parts__count">
              <b>{part.quantityOnHand}</b>
              <span>of {part.minimumQuantity} minimum</span>
            </div>
            <div className="parts__actions">
              <Button variant="ghost" onClick={() => setCounting(part)}>
                Count
              </Button>
              {canSetMinimum && (
                <Button variant="ghost" onClick={() => setConfiguring(part)}>
                  Minimum
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>

      {counting && (
        <CountDialog
          part={counting}
          onClose={() => setCounting(null)}
          onSaved={() => {
            setCounting(null);
            refresh();
          }}
        />
      )}
      {configuring && (
        <MinimumDialog
          part={configuring}
          onClose={() => setConfiguring(null)}
          onSaved={() => {
            setConfiguring(null);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function CountDialog({ part, onClose, onSaved }: { part: PartRow; onClose: () => void; onSaved: () => void }) {
  const [quantity, setQuantity] = useState(String(part.quantityOnHand));
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const counted = Number(quantity);
  const willBeShort = Number.isFinite(counted) && counted < part.minimumQuantity;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await countStock(part.id, counted, note.trim() || undefined);
      onSaved();
    } catch (e) {
      setError(errorText(e, 'The count could not be recorded.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Record a stock count"
      subtitle={`${part.name}${part.partNumber ? ` · ${part.partNumber}` : ''}`}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || quantity.trim() === '' || !Number.isFinite(counted)} onClick={save}>
            {busy ? 'Saving…' : 'Record count'}
          </Button>
        </>
      }
    >
      <Field label="How many are on board" htmlFor="part-count" hint="What is on the shelf now, not how many were used.">
        <input
          id="part-count"
          className="input"
          type="number"
          min={0}
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
        />
      </Field>
      <Field label="Note" htmlFor="part-note" hint="Optional: where they went, or what you noticed.">
        <input id="part-note" className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      {willBeShort && (
        <p className="otp__note">
          That is below the minimum of {part.minimumQuantity}. Recording it tells your Ship Manager and Technical Head,
          so a replacement can be ordered.
        </p>
      )}
      <FormError message={error} />
    </Dialog>
  );
}

function MinimumDialog({ part, onClose, onSaved }: { part: PartRow; onClose: () => void; onSaved: () => void }) {
  const [minimum, setMinimum] = useState(String(part.minimumQuantity));
  const [location, setLocation] = useState(part.location ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await configurePart(part.id, { minimumQuantity: Number(minimum), location: location.trim() || undefined });
      onSaved();
    } catch (e) {
      setError(errorText(e, 'The minimum could not be changed.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Minimum to hold"
      subtitle={part.name}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !Number.isFinite(Number(minimum))} onClick={save}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <Field label="Minimum quantity" htmlFor="part-min" hint="Below this, the vessel and the office are alerted.">
        <input id="part-min" className="input" type="number" min={0} value={minimum} onChange={(e) => setMinimum(e.target.value)} />
      </Field>
      <Field label="Where it is stored" htmlFor="part-location">
        <input id="part-location" className="input" maxLength={120} value={location} onChange={(e) => setLocation(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}
