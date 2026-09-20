import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import { fetchHourHistory, recordHours, type HourHistory } from '@/api/runningHours';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { formatDate, formatHours } from '@/lib/format';

/**
 * The Captain records an hour-meter reading (SoW s7, RBAC: Captain on own
 * vessel). The server checks the reading against the last one and against the
 * 24 hours a day a meter can run, and re-projects the running-hour due date.
 */
export function RecordHoursDialog({
  spareId,
  spareName,
  onClose,
  onRecorded,
}: {
  spareId: number;
  spareName: string;
  onClose: () => void;
  onRecorded: (history: HourHistory) => void;
}) {
  const history = useQuery({ queryKey: ['running-hours', spareId], queryFn: () => fetchHourHistory(spareId) });
  const [hours, setHours] = useState('');
  const [date, setDate] = useState(() => localToday());
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const current = history.data?.currentHours;
  const valid = /^\d+(\.\d{1,2})?$/.test(hours.trim()) && date !== '';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onRecorded(await recordHours(spareId, { readingHours: hours.trim(), readingDate: date, note: note.trim() || undefined }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The reading could not be saved. Try again.');
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Record running hours"
      subtitle={spareName}
      onClose={onClose}
      width={520}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Saving…' : 'Save reading'}
          </Button>
        </>
      }
    >
      <div className="hours-now">
        <div>
          <span className="hours-now__label">Last recorded</span>
          <b className="hours-now__value">{current ? formatHours(current) : '—'}</b>
        </div>
        <div>
          <span className="hours-now__label">Taken</span>
          <b className="hours-now__date">
            {history.data?.lastReadingDate ? formatDate(history.data.lastReadingDate) : 'No reading yet'}
          </b>
        </div>
      </div>

      <Field label="Hour meter reading" htmlFor="hours-value" hint="As shown on the meter, in hours.">
        <input
          id="hours-value"
          className="input"
          inputMode="decimal"
          value={hours}
          onChange={(e) => setHours(e.target.value)}
          placeholder={current ? `${current} or more` : 'e.g. 6120'}
        />
      </Field>

      <Field label="Reading date" htmlFor="hours-date">
        <input id="hours-date" type="date" className="input" value={date} max={localToday()} onChange={(e) => setDate(e.target.value)} />
      </Field>

      <Field label="Note" htmlFor="hours-note" hint="Optional.">
        <input id="hours-note" className="input" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
      </Field>

      {history.data && history.data.readings.length > 0 && (
        <div className="hours-log">
          <span className="hours-now__label">Recent readings</span>
          <ul>
            {history.data.readings.slice(0, 4).map((r) => (
              <li key={r.id}>
                <span>{formatDate(r.readingDate)}</span>
                <b>{formatHours(r.readingHours)}</b>
                <span className="hours-log__who">{r.recordedBy}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <FormError message={error ?? (history.error instanceof Error ? history.error.message : null)} />
    </Dialog>
  );
}

/** Today on the device's own calendar, as yyyy-mm-dd. */
function localToday() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
