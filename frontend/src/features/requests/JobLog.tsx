import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, fetchObjectUrl } from '@/api/client';
import { addJobLogEntry, fetchJobLog, type JobLogEntry, type JobLogKind } from '@/api/jobLog';
import { attachmentPath } from '@/api/conversation';
import { Button, EmptyNote, Plate } from '@/design-system/Console';
import { Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { formatDateTime } from '@/lib/format';

/** What each step is called, and the mark beside it on the timeline. */
const STEP: Record<JobLogKind, { label: string; mark: string }> = {
  ARRIVED: { label: 'Arrived on board', mark: '🚢' },
  STARTED: { label: 'Work started', mark: '▶' },
  UPDATE: { label: 'Progress update', mark: '📝' },
  WAITING: { label: 'Waiting', mark: '⏸' },
  RESUMED: { label: 'Work resumed', mark: '▶' },
  FINISHED: { label: 'Work finished', mark: '✅' },
  LEFT: { label: 'Left the vessel', mark: '👋' },
  REPORTED: { label: 'Report submitted', mark: '📄' },
};

/** The buttons the engineer taps, in the order a job usually goes. */
const BUTTONS: JobLogKind[] = ['ARRIVED', 'STARTED', 'UPDATE', 'WAITING', 'RESUMED', 'FINISHED', 'LEFT'];

/**
 * JOB LOG — from arriving aboard to leaving, what the engineer did and when
 * (SoW §6.3). The engineer on the job writes it with one tap per step; the
 * time fills in as now and can be corrected for something logged later.
 * Everyone who can see the request reads it; it carries no cost.
 */
export function JobLog({ requestId, prominent = false }: { requestId: number; prominent?: boolean }) {
  const client = useQueryClient();
  const log = useQuery({ queryKey: ['job-log', requestId], queryFn: () => fetchJobLog(requestId), refetchInterval: 30_000 });
  const [adding, setAdding] = useState<JobLogKind | null>(null);

  if (!log.data) return null;
  const { entries, canWrite } = log.data;
  if (entries.length === 0 && !canWrite) return null;
  const times = summarise(entries);

  return (
    <Plate
      title="Job log"
      count={entries.length || undefined}
      subtitle={canWrite ? 'Tap a step as it happens. The time fills in as now — change it if you are logging later.' : 'What the engineer did on this job, and when.'}
      tone={prominent && canWrite ? 'attention' : undefined}
    >
      <div className="joblog">
        {(times.started || times.arrived) && (
          <dl className="joblog__sum">
            {times.arrived && <Sum label="Arrived" value={formatDateTime(times.arrived)} />}
            {times.started && <Sum label="Started" value={formatDateTime(times.started)} />}
            {times.finished && <Sum label="Finished" value={formatDateTime(times.finished)} />}
            {times.working > 0 && <Sum label={times.finished ? 'Time working' : 'Working so far'} value={duration(times.working)} />}
            {times.waiting > 0 && <Sum label="Time waiting" value={duration(times.waiting)} />}
            {times.onBoard > 0 && <Sum label={times.left ? 'Time on board' : 'On board so far'} value={duration(times.onBoard)} />}
          </dl>
        )}

        {canWrite && !adding && (
          <div className="joblog__buttons">
            {BUTTONS.filter((k) => k !== 'STARTED' || !entries.some((e) => e.kind === 'STARTED' && !e.automatic)).map((k) => (
              <button key={k} type="button" className="joblog__btn" onClick={() => setAdding(k)}>
                <span aria-hidden="true">{STEP[k].mark}</span>
                {STEP[k].label}
              </button>
            ))}
          </div>
        )}
        {canWrite && adding && (
          <EntryForm
            requestId={requestId}
            kind={adding}
            onCancel={() => setAdding(null)}
            onSaved={() => {
              setAdding(null);
              void client.invalidateQueries({ queryKey: ['job-log', requestId] });
              // "Work started" also starts the job: the request's status and next steps change.
              void client.invalidateQueries({ queryKey: ['service-request', requestId] });
              void client.invalidateQueries({ queryKey: ['dashboard'] });
            }}
          />
        )}

        {entries.length === 0 ? (
          <EmptyNote>Nothing logged yet. Start with “Arrived on board” when you get to the vessel.</EmptyNote>
        ) : (
          <ol className="joblog__list">
            {entries.map((e) => (
              <Entry key={e.id} entry={e} />
            ))}
          </ol>
        )}
      </div>
    </Plate>
  );
}

function Sum({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Entry({ entry: e }: { entry: JobLogEntry }) {
  return (
    <li className={`joblog__item joblog__item--${e.kind.toLowerCase()}`}>
      <span className="joblog__mark" aria-hidden="true">
        {STEP[e.kind].mark}
      </span>
      <div className="joblog__body">
        <div className="joblog__top">
          <b>{STEP[e.kind].label}</b>
          <time dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time>
        </div>
        {e.note && <p className="joblog__note">{e.note}</p>}
        {e.photo && <Photo photo={e.photo} />}
        <span className="joblog__who">
          {e.automatic ? 'Recorded automatically' : e.authorName}
          {e.recordedAt && !e.automatic && laterThan(e.recordedAt, e.occurredAt) && ` · logged ${formatDateTime(e.recordedAt)}`}
        </span>
      </div>
    </li>
  );
}

function Photo({ photo }: { photo: NonNullable<JobLogEntry['photo']> }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!photo.image) return;
    let got: string | null = null;
    let cancelled = false;
    fetchObjectUrl(attachmentPath(photo.documentId))
      .then((u) => {
        got = u;
        if (cancelled) URL.revokeObjectURL(u);
        else setUrl(u);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (got) URL.revokeObjectURL(got);
    };
  }, [photo.documentId, photo.image]);
  if (!photo.image) return <span className="joblog__file"><Icon name="file" size={16} /> {photo.fileName}</span>;
  return url ? <img className="joblog__photo" src={url} alt={photo.fileName} /> : null;
}

function EntryForm({
  requestId,
  kind,
  onCancel,
  onSaved,
}: {
  requestId: number;
  kind: JobLogKind;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [when, setWhen] = useState(localNow());
  const [note, setNote] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);
  const needsNote = kind === 'UPDATE' || kind === 'WAITING';

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await addJobLogEntry(requestId, { kind, occurredAt: new Date(when).toISOString(), note: note.trim() || undefined }, photo);
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="joblog__form">
      <p className="joblog__formtitle">
        <span aria-hidden="true">{STEP[kind].mark}</span> {STEP[kind].label}
      </p>
      <Field label="When" htmlFor="joblog-when">
        <input id="joblog-when" className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
      </Field>
      <Field
        label={kind === 'WAITING' ? 'Waiting for what?' : kind === 'UPDATE' ? 'What was done?' : 'Note (optional)'}
        htmlFor="joblog-note"
      >
        <textarea
          id="joblog-note"
          className="textarea"
          rows={3}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder={kind === 'WAITING' ? 'e.g. Spare magnetron coming from shore' : kind === 'UPDATE' ? 'e.g. Replaced display fan, testing now' : ''}
        />
      </Field>
      <div className="joblog__photoin">
        <input
          ref={file}
          className="sr-only"
          type="file"
          accept="image/png,image/jpeg,image/webp,video/mp4,application/pdf"
          onChange={(e) => setPhoto(e.target.files?.[0] ?? null)}
        />
        <Button variant="ghost" onClick={() => file.current?.click()}>
          <Icon name="photo" size={16} />
          {photo ? photo.name : 'Add a photo (optional)'}
        </Button>
      </div>
      <FormError message={error} />
      <div className="joblog__formactions">
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={busy || (needsNote && !note.trim() && !photo)} onClick={save}>
          {busy ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </div>
  );
}

/**
 * The times read off the log: working time runs from each start or resume to
 * the next wait or finish; waiting from each wait to the next resume or
 * finish; time on board from the first arrival to the last departure.
 */
function summarise(entries: JobLogEntry[]) {
  const t = (e?: JobLogEntry) => (e ? new Date(e.occurredAt).getTime() : 0);
  const first = (k: JobLogKind) => entries.find((e) => e.kind === k);
  const last = (k: JobLogKind) => [...entries].reverse().find((e) => e.kind === k);
  const now = Date.now();

  let working = 0;
  let waiting = 0;
  let workFrom: number | null = null;
  let waitFrom: number | null = null;
  for (const e of entries) {
    const at = t(e);
    if (e.kind === 'STARTED' || e.kind === 'RESUMED') {
      if (waitFrom !== null) waiting += at - waitFrom;
      waitFrom = null;
      if (workFrom === null) workFrom = at;
    } else if (e.kind === 'WAITING') {
      if (workFrom !== null) working += at - workFrom;
      workFrom = null;
      if (waitFrom === null) waitFrom = at;
    } else if (e.kind === 'FINISHED') {
      if (workFrom !== null) working += at - workFrom;
      if (waitFrom !== null) waiting += at - waitFrom;
      workFrom = null;
      waitFrom = null;
    }
  }
  if (workFrom !== null) working += now - workFrom;
  if (waitFrom !== null) waiting += now - waitFrom;

  const arrived = first('ARRIVED');
  const left = last('LEFT');
  const onBoard = arrived ? (left ? t(left) : now) - t(arrived) : 0;
  return {
    arrived: arrived?.occurredAt,
    started: first('STARTED')?.occurredAt,
    finished: last('FINISHED')?.occurredAt,
    left: left?.occurredAt,
    working: Math.max(0, working),
    waiting: Math.max(0, waiting),
    onBoard: Math.max(0, onBoard),
  };
}

function duration(ms: number) {
  const minutes = Math.round(ms / 60000);
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  const mins = minutes % 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${mins}m`;
  return `${mins}m`;
}

function laterThan(recorded: string, occurred: string) {
  return new Date(recorded).getTime() - new Date(occurred).getTime() > 15 * 60000;
}

/** Now, as a datetime-local input wants it: local time, to the minute. */
function localNow() {
  const d = new Date();
  d.setSeconds(0, 0);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
