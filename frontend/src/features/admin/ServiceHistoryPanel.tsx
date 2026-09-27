import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  fetchServiceHistory,
  recordService,
  removeServiceRecord,
  type ServiceRecord,
} from '@/api/admin';
import { Button, EmptyNote } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { todayInput, formatDate } from '@/lib/format';
import { errorText } from './AdminParts';
import { downloadServiceHistoryExcel } from '@/api/admin';
import './service-history.css';

/**
 * Everything that has been done to one piece of equipment (SoW §6.3, §9.3).
 *
 * <p>The question a surveyor or an auditor asks is not "when was this last
 * serviced" but "show me what has been done to it". Two kinds of answer sit in
 * one list: services the platform carried out, each carrying its request
 * number, and services recorded afterwards — work done before this platform
 * existed, or by a contractor who was never on it.
 *
 * <p>They are told apart on the row, because the first thing anyone reviewing
 * a history wants to know is whether the system observed it or somebody typed
 * it in. Only what was typed in can be taken back out.
 */
export function ServiceHistoryPanel({
  spareId,
  spareName,
  canRecord,
  onChanged,
}: {
  spareId: number;
  spareName: string;
  /** The Technical Head and Platform Admin keep master data; nobody else. */
  canRecord: boolean;
  onChanged?: () => void;
}) {
  const client = useQueryClient();
  const history = useQuery({
    queryKey: ['service-history', spareId],
    queryFn: () => fetchServiceHistory(spareId),
  });
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<ServiceRecord | null>(null);
  const [error, setError] = useState<string | null>(null);

  const rows = history.data ?? [];

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['service-history', spareId] });
    // A service date moves the next-due date, which moves every dashboard.
    client.invalidateQueries({ queryKey: ['vessel-fit'] });
    client.invalidateQueries({ queryKey: ['vessel-maintenance'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
    onChanged?.();
  };

  const remove = async (record: ServiceRecord) => {
    setError(null);
    try {
      await removeServiceRecord(spareId, record.id);
      setRemoving(null);
      refresh();
    } catch (e) {
      setError(errorText(e, 'That entry could not be removed.'));
    }
  };

  return (
    <div className="svchist">
      <div className="svchist__top">
        <p className="svchist__summary">
          {rows.length === 0
            ? 'No service has been recorded against this equipment yet.'
            : `${rows.length} service${rows.length === 1 ? '' : 's'} on record, most recent first.`}
        </p>
        <div className="svchist__actions">
          {rows.length > 0 && (
            <Button
              onClick={() =>
                downloadServiceHistoryExcel({ spareId }).catch((e) => setError(errorText(e, 'The download failed.')))
              }
            >
              Download Excel
            </Button>
          )}
          {canRecord && (
            <Button variant="primary" onClick={() => setAdding(true)}>
              Add work done
            </Button>
          )}
        </div>
      </div>

      {history.isLoading ? (
        <LoadingState rows={3} />
      ) : rows.length === 0 ? (
        <EmptyNote>
          Record any service, repair or part replaced on this equipment — including work done before it was on
          Thawe Marine, so the history comes with the vessel.
        </EmptyNote>
      ) : (
        <ol className="svchist__list">
          {rows.map((record) => (
            <li key={record.id} className="svchist__row">
              <div className="svchist__when">
                <b>{formatDate(record.serviceDate)}</b>
                <Pill size="sm" tone={record.source === 'PLATFORM' ? 'normal' : 'neutral'}>
                  {record.source === 'PLATFORM' ? 'On Thawe Marine' : 'Recorded'}
                </Pill>
              </div>
              <div className="svchist__what">
                <p className="svchist__work">{record.workPerformed}</p>
                <div className="svchist__meta">
                  {record.performedBy && <span>By {record.performedBy}</span>}
                  {record.partsUsed && <span>Parts: {record.partsUsed}</span>}
                  {record.requestNumber && <span className="mono">{record.requestNumber}</span>}
                  {record.recordedBy && <span>Entered by {record.recordedBy}</span>}
                </div>
                {record.notes && <p className="svchist__notes">{record.notes}</p>}
              </div>
              {canRecord && record.removable && (
                <Button variant="ghost" onClick={() => setRemoving(record)}>
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ol>
      )}

      <FormError message={error} />

      {adding && (
        <RecordServiceDialog
          spareId={spareId}
          spareName={spareName}
          onClose={() => setAdding(false)}
          onSaved={() => {
            setAdding(false);
            refresh();
          }}
        />
      )}

      {removing && (
        <Dialog
          title="Remove this entry"
          subtitle={`${formatDate(removing.serviceDate)} · ${spareName}`}
          onClose={() => setRemoving(null)}
          width={460}
          footer={
            <>
              <Button onClick={() => setRemoving(null)}>Cancel</Button>
              <Button variant="danger" onClick={() => void remove(removing)}>
                Remove
              </Button>
            </>
          }
        >
          <p className="otp__note">
            This was entered by hand, so it can be taken back out. If it was the most recent service, the next-due
            date moves back to whatever the history says before it.
          </p>
        </Dialog>
      )}
    </div>
  );
}

/**
 * Recording work the platform did not see.
 *
 * <p>Back-dating is the normal case here, not an exception — this exists so a
 * fleet can bring its existing history with it. The date is what the
 * maintenance engine counts from, so entering one re-colours the item on every
 * dashboard, and the dialog says so before it is saved.
 */
export function RecordServiceDialog({
  spareId,
  spareName,
  onClose,
  onSaved,
}: {
  spareId: number;
  spareName: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const today = todayInput();
  const [serviceDate, setServiceDate] = useState('');
  const [workPerformed, setWorkPerformed] = useState('');
  const [performedBy, setPerformedBy] = useState('');
  const [partsUsed, setPartsUsed] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = serviceDate !== '' && workPerformed.trim() !== '';

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await recordService(spareId, {
        serviceDate,
        workPerformed: workPerformed.trim(),
        performedBy: performedBy.trim() || undefined,
        partsUsed: partsUsed.trim() || undefined,
        notes: notes.trim() || undefined,
      });
      onSaved();
    } catch (e) {
      setError(errorText(e, 'That service could not be recorded.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Add work done"
      subtitle={spareName}
      onClose={onClose}
      width={560}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={save}>
            {busy ? 'Saving…' : 'Save to history'}
          </Button>
        </>
      }
    >
      <Field
        label="Date the work was done"
        htmlFor="svc-date"
        hint="Today or earlier. This is what the next service is counted from."
      >
        <input
          id="svc-date"
          className="input"
          type="date"
          max={today}
          value={serviceDate}
          onChange={(e) => setServiceDate(e.target.value)}
        />
      </Field>

      <Field label="What was done" htmlFor="svc-work" hint="As it would read on a service report.">
        <textarea
          id="svc-work"
          className="textarea"
          rows={3}
          maxLength={2000}
          value={workPerformed}
          onChange={(e) => setWorkPerformed(e.target.value)}
          placeholder="e.g. Magnetron replaced; performance test and calibration done, output within limits."
        />
      </Field>

      <div className="form-grid">
        <Field label="Performed by" htmlFor="svc-by" hint="The engineer or the servicing company.">
          <input
            id="svc-by"
            className="input"
            maxLength={200}
            value={performedBy}
            onChange={(e) => setPerformedBy(e.target.value)}
            placeholder="e.g. Marine Electronics Pte Ltd"
          />
        </Field>
        <Field label="Parts replaced / used" htmlFor="svc-parts" hint="Part names or numbers.">
          <input
            id="svc-parts"
            className="input"
            maxLength={1000}
            value={partsUsed}
            onChange={(e) => setPartsUsed(e.target.value)}
          />
        </Field>
      </div>

      <Field label="Notes" htmlFor="svc-notes" hint="Anything a surveyor would want alongside it.">
        <input id="svc-notes" className="input" maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>

      <p className="otp__note">
        Saving this moves this item's next-due date and its colour on every dashboard. Nothing is sent to anybody —
        this is a record of work already done.
      </p>
      <FormError message={error} />
    </Dialog>
  );
}
