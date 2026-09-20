import { useState } from 'react';
import { ApiError } from '@/api/client';
import {
  answerCheck,
  finishChecks,
  startChecks,
  type ChecksSession,
  type ChecksView,
} from '@/api/troubleshooting';
import { Button, EmptyNote, Plate } from '@/design-system/Console';
import { Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { Pill } from '@/design-system/StatusBadge';
import { formatDateTime } from '@/lib/format';

/**
 * The Automated Troubleshooting Assistant on a request (SoW §6.1).
 *
 * <p>For the Captain, while it is their move: pick the problem, answer the
 * checks one at a time, then record what was found. For everyone else, and
 * afterwards: the full log of what was asked and answered, which the
 * Coordinator reviews and the engineer receives as context (§6.2).
 */
export function GuidedChecks({
  requestId,
  view,
  spareName,
  onChanged,
}: {
  requestId: number;
  view: ChecksView;
  spareName: string;
  onChanged: (view: ChecksView) => void;
}) {
  const s = view.session;

  if (view.canStart) return <StartChecks requestId={requestId} view={view} spareName={spareName} onChanged={onChanged} />;
  if (s && view.canAnswer && s.status === 'IN_PROGRESS' && s.currentStep) {
    return <AskCheck requestId={requestId} session={s} onChanged={onChanged} />;
  }
  if (s && view.canAnswer && s.status === 'OUTCOME_REACHED') {
    return <FinishChecks requestId={requestId} session={s} onChanged={onChanged} />;
  }
  return <ChecksLog session={s} />;
}

function StartChecks({ requestId, view, spareName, onChanged }: { requestId: number; view: ChecksView; spareName: string; onChanged: (v: ChecksView) => void }) {
  const [problem, setProblem] = useState<number | 'other' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await startChecks(requestId, problem === 'other' ? null : problem));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The checks could not be started.');
      setBusy(false);
    }
  };

  return (
    <Plate title="Guided checks" subtitle={`Quick checks on ${spareName} before the request goes to your Ship Manager`}>
      <div className="checks">
        <p className="checks__lead">What best describes the problem?</p>
        <div className="checks__choices" role="radiogroup" aria-label="Problem">
          {view.problemTypes.map((p) => (
            <label key={p.id} className={`checks__choice${problem === p.id ? ' checks__choice--on' : ''}`}>
              <input type="radio" name="problem" checked={problem === p.id} onChange={() => setProblem(p.id)} />
              <span>{p.label}</span>
            </label>
          ))}
          <label className={`checks__choice${problem === 'other' ? ' checks__choice--on' : ''}`}>
            <input type="radio" name="problem" checked={problem === 'other'} onChange={() => setProblem('other')} />
            <span>Something else</span>
          </label>
        </div>
        <div className="checks__bar">
          <Button variant="primary" size="lg" disabled={busy || problem === null} onClick={start}>
            <Icon name="check" size={16} />
            {busy ? 'Starting…' : 'Start checks'}
          </Button>
        </div>
        <FormError message={error} />
      </div>
    </Plate>
  );
}

function AskCheck({ requestId, session, onChanged }: { requestId: number; session: ChecksSession; onChanged: (v: ChecksView) => void }) {
  const step = session.currentStep!;
  const [note, setNote] = useState('');
  const [noting, setNoting] = useState(false);
  const [busy, setBusy] = useState<'yes' | 'no' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const answer = async (yes: boolean) => {
    setBusy(yes ? 'yes' : 'no');
    setError(null);
    try {
      onChanged(await answerCheck(requestId, step.id, yes, note.trim() || undefined));
      setNote('');
      setNoting(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The answer could not be saved.');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Plate title="Guided checks" count={`Check ${step.number}`} subtitle={session.flowName} action={session.sampleContent ? <SampleTag /> : undefined}>
      <div className="checks">
        <div className="checks__question" aria-live="polite">
          <p className="checks__prompt" id="check-prompt">
            {step.prompt}
          </p>
          {step.helpText && <p className="checks__help">{step.helpText}</p>}
        </div>

        {noting ? (
          <Field label="Note" htmlFor="check-note" hint="Optional: what you saw, a reading or an error code.">
            <input id="check-note" className="input" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          </Field>
        ) : (
          <button type="button" className="checks__add-note" onClick={() => setNoting(true)}>
            Add a note to this answer
          </button>
        )}

        <div className="checks__answers" aria-describedby="check-prompt">
          <Button variant="primary" size="xl" disabled={busy !== null} onClick={() => answer(true)}>
            {busy === 'yes' ? 'Saving…' : 'Yes'}
          </Button>
          <Button variant="secondary" size="xl" disabled={busy !== null} onClick={() => answer(false)}>
            {busy === 'no' ? 'Saving…' : 'No'}
          </Button>
        </div>
        <FormError message={error} />
        {session.answers.length > 0 && <AnswerList answers={session.answers} />}
      </div>
    </Plate>
  );
}

function FinishChecks({ requestId, session, onChanged }: { requestId: number; session: ChecksSession; onChanged: (v: ChecksView) => void }) {
  const [rootCause, setRootCause] = useState('');
  const [tempFix, setTempFix] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const needsFix = session.outcome === 'TEMPORARY_FIX';

  const finish = async () => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await finishChecks(requestId, rootCause, tempFix));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The result could not be saved.');
      setBusy(false);
    }
  };

  return (
    <Plate title="Guided checks" subtitle={session.flowName} action={session.sampleContent ? <SampleTag /> : undefined}>
      <div className="checks">
        <div className={`checks__outcome checks__outcome--${session.outcome?.toLowerCase()}`}>
          <Icon name={session.outcome === 'UNRESOLVED' ? 'wrench' : 'check'} size={22} />
          <div>
            <p className="checks__outcome-title">{OUTCOME_TITLE[session.outcome ?? 'UNRESOLVED']}</p>
            <p className="checks__outcome-body">{OUTCOME_BODY[session.outcome ?? 'UNRESOLVED']}</p>
          </div>
        </div>

        {needsFix && (
          <Field label="Temporary fix applied" htmlFor="check-fix" hint="Required: what is keeping the equipment working for now.">
            <textarea id="check-fix" className="textarea" rows={3} value={tempFix} maxLength={1000} onChange={(e) => setTempFix(e.target.value)} />
          </Field>
        )}
        <Field label="Likely cause" htmlFor="check-cause" hint="Optional: what you found, including any error code shown.">
          <textarea id="check-cause" className="textarea" rows={3} value={rootCause} maxLength={1000} onChange={(e) => setRootCause(e.target.value)} />
        </Field>

        <div className="checks__bar">
          <Button variant="primary" size="lg" disabled={busy || (needsFix && tempFix.trim() === '')} onClick={finish}>
            {busy ? 'Saving…' : 'Save and finish checks'}
          </Button>
        </div>
        <FormError message={error} />
        <AnswerList answers={session.answers} />
      </div>
    </Plate>
  );
}

function ChecksLog({ session }: { session?: ChecksSession }) {
  if (!session) {
    return (
      <Plate title="Guided checks">
        <EmptyNote>No guided checks have been run on this request.</EmptyNote>
      </Plate>
    );
  }
  const finished = session.status === 'COMPLETED';
  return (
    <Plate
      title="Guided checks"
      subtitle={`${session.flowName} · run by ${session.startedBy ?? 'the Captain'} · ${formatDateTime(session.startedAt)}`}
      action={session.sampleContent ? <SampleTag /> : undefined}
    >
      <div className="checks">
        <div className="checks__result">
          {finished && session.outcome ? (
            <Pill tone={session.outcome === 'RESOLVED' ? 'accent' : 'neutral'}>{session.outcomeLabel}</Pill>
          ) : (
            <Pill>In progress</Pill>
          )}
          {session.completedAt && <span className="checks__when">Finished {formatDateTime(session.completedAt)}</span>}
        </div>
        {session.temporaryFixNote && (
          <p className="req-reason">
            <b>Temporary fix applied</b>
            {session.temporaryFixNote}
          </p>
        )}
        {session.rootCauseNote && (
          <p className="req-reason">
            <b>Likely cause</b>
            {session.rootCauseNote}
          </p>
        )}
        <AnswerList answers={session.answers} />
      </div>
    </Plate>
  );
}

function AnswerList({ answers }: { answers: ChecksSession['answers'] }) {
  return (
    <ol className="checks__log" aria-label="Checks answered">
      {answers.map((a) => (
        <li key={a.number}>
          <span className="checks__log-n">{a.number}</span>
          <div>
            <p className="checks__log-q">{a.prompt}</p>
            {a.note && <p className="checks__log-note">“{a.note}”</p>}
          </div>
          <span className={`checks__log-a checks__log-a--${a.answer.toLowerCase()}`}>{a.answer === 'YES' ? 'Yes' : 'No'}</span>
        </li>
      ))}
    </ol>
  );
}

function SampleTag() {
  return (
    <span className="checks__sample" title="Seastella supplies the approved checks (SoW §16). These are sample checks for the demonstration.">
      Sample checks
    </span>
  );
}

const OUTCOME_TITLE: Record<string, string> = {
  RESOLVED: 'The checks resolved it',
  TEMPORARY_FIX: 'Working on a temporary fix',
  UNRESOLVED: 'The checks did not resolve it',
};

const OUTCOME_BODY: Record<string, string> = {
  RESOLVED: 'Record what the cause was, then submit the request. The Coordinator can close it without cost.',
  TEMPORARY_FIX: 'Describe the temporary fix. The Coordinator decides whether a service visit is still needed.',
  UNRESOLVED: 'Record anything you found, then submit the request for your Ship Manager’s approval.',
};
