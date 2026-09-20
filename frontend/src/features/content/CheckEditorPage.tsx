import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  describeTarget,
  discardDraft,
  fetchFlow,
  fetchProblemTypes,
  openDraft,
  OUTCOME_LABEL,
  publishFlow,
  retireFlow,
  saveDraft,
  type Branch,
  type CategoryProblemTypes,
  type CheckStep,
  type FlowDetail,
  type Outcome,
} from '@/api/content';
import { Button, ConsoleHeader, Plate } from '@/design-system/Console';
import { Dialog, Field } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDateTime } from '@/lib/format';
import '@/features/requests/requests.css';
import './content.css';

/**
 * One version of a set of guided checks (SoW §13).
 *
 * <p>A draft is edited here and saved as a whole; the server re-checks it and
 * lists what stops it being published - a check nothing leads to, answers that
 * loop without an outcome. Published and withdrawn versions are read-only: a
 * Captain may be part-way through them, so a change is always a new draft.
 */
export function CheckEditorPage() {
  const flowId = Number(useParams().flowId);
  const client = useQueryClient();
  const flow = useQuery({ queryKey: ['content-flow', flowId], queryFn: () => fetchFlow(flowId), enabled: Number.isFinite(flowId) });
  const catalogue = useQuery({ queryKey: ['problem-types'], queryFn: fetchProblemTypes });

  if (flow.error) return <ErrorState error={flow.error} onRetry={() => flow.refetch()} />;
  if (!flow.data || !catalogue.data) return <LoadingState rows={6} />;

  return (
    <Editor
      // A new server version (after a save, publish or reload) restarts the local edit state.
      key={`${flow.data.id}:${flow.data.version}`}
      detail={flow.data}
      catalogue={catalogue.data}
      onServerChange={(next) => {
        client.setQueryData(['content-flow', next.id], next);
        client.invalidateQueries({ queryKey: ['content-flows'] });
        client.invalidateQueries({ queryKey: ['content-flow'] });
      }}
      onDiscarded={() => {
        client.invalidateQueries({ queryKey: ['content-flows'] });
        client.invalidateQueries({ queryKey: ['content-flow'] });
      }}
      onReload={() => flow.refetch()}
    />
  );
}

interface Local {
  name: string;
  equipmentCategoryId: number | null;
  problemTypeId: number | null;
  sampleContent: boolean;
  startStepKey: string;
  steps: CheckStep[];
}

function fromDetail(d: FlowDetail): Local {
  return {
    name: d.name,
    equipmentCategoryId: d.target.equipmentCategoryId,
    problemTypeId: d.target.problemTypeId,
    sampleContent: d.sampleContent,
    startStepKey: d.startStepKey,
    steps: d.steps.map((s) => ({ key: s.key, prompt: s.prompt, helpText: s.helpText ?? '', yes: { ...s.yes }, no: { ...s.no } })),
  };
}

function Editor({
  detail,
  catalogue,
  onServerChange,
  onDiscarded,
  onReload,
}: {
  detail: FlowDetail;
  catalogue: CategoryProblemTypes[];
  onServerChange: (d: FlowDetail) => void;
  onDiscarded: () => void;
  onReload: () => void;
}) {
  const navigate = useNavigate();
  const editable = detail.status === 'DRAFT';
  const [local, setLocal] = useState<Local>(() => fromDetail(detail));
  const [busy, setBusy] = useState<null | 'save' | 'publish' | 'draft' | 'retire' | 'discard'>(null);
  const [error, setError] = useState<{ message: string; stale: boolean } | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [confirm, setConfirm] = useState<null | 'publish' | 'retire' | 'discard'>(null);

  const dirty = useMemo(() => JSON.stringify(local) !== JSON.stringify(fromDetail(detail)), [local, detail]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const run = async (kind: NonNullable<typeof busy>, action: () => Promise<void>) => {
    setBusy(kind);
    setError(null);
    try {
      await action();
    } catch (e) {
      const stale = e instanceof ApiError && e.status === 409 && /reload/i.test(e.message);
      setError({ message: e instanceof ApiError ? e.message : 'Could not reach SeaStella. Try again.', stale });
    } finally {
      setBusy(null);
      setConfirm(null);
    }
  };

  const save = () =>
    run('save', async () => {
      const next = await saveDraft(detail.id, {
        version: detail.version,
        name: local.name,
        equipmentCategoryId: local.equipmentCategoryId,
        problemTypeId: local.problemTypeId,
        sampleContent: local.sampleContent,
        startStepKey: local.startStepKey,
        steps: local.steps.map((s) => ({ ...s, helpText: s.helpText?.trim() ? s.helpText : null })),
      });
      onServerChange(next);
    });

  const publish = () =>
    run('publish', async () => {
      const next = await publishFlow(detail.id, detail.version);
      onServerChange(next);
    });

  const newDraft = () =>
    run('draft', async () => {
      const draft = await openDraft(detail.id);
      onServerChange(draft);
      navigate(`/platform/checks/${draft.id}`);
    });

  const retire = () =>
    run('retire', async () => {
      onServerChange(await retireFlow(detail.id));
    });

  const discard = () =>
    run('discard', async () => {
      await discardDraft(detail.id);
      onDiscarded();
      const fallback = detail.versions.find((v) => v.id !== detail.id);
      navigate(fallback ? `/platform/checks/${fallback.id}` : '/platform/checks', { replace: true });
    });

  const current = detail.versions.find((v) => v.status === 'PUBLISHED');
  const problems = editable && !dirty ? detail.problems : [];

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: <Link to="/platform/checks">Guided checks</Link> }, { label: detail.name, strong: true }]}
        title={detail.name}
        badge={<StatusPill detail={detail} />}
        subtitle={`${describeTarget(detail.target)} · version ${detail.flowVersion}`}
        actions={
          <div className="content-actions">
            <Button onClick={() => setPreviewing(true)} disabled={local.steps.length === 0}>
              <Icon name="chat" size={16} />
              Preview
            </Button>
            {editable ? (
              <>
                <Button variant="ghost" onClick={() => setConfirm('discard')} disabled={busy !== null}>
                  Discard draft
                </Button>
                <Button variant={dirty ? 'primary' : 'secondary'} onClick={save} disabled={!dirty || busy !== null}>
                  {busy === 'save' ? 'Saving…' : dirty ? 'Save draft' : 'Saved'}
                </Button>
                <Button
                  variant={dirty ? 'secondary' : 'primary'}
                  onClick={() => setConfirm('publish')}
                  disabled={dirty || busy !== null || detail.problems.length > 0}
                  title={dirty ? 'Save the draft first' : detail.problems.length ? 'Fix the problems listed first' : undefined}
                >
                  Publish
                </Button>
              </>
            ) : (
              <>
                {detail.status === 'PUBLISHED' && (
                  <Button variant="ghost" onClick={() => setConfirm('retire')} disabled={busy !== null}>
                    Withdraw
                  </Button>
                )}
                <Button variant="primary" onClick={newDraft} disabled={busy !== null}>
                  {detail.draftId ? 'Open the draft' : busy === 'draft' ? 'Creating draft…' : 'Edit as a new draft'}
                </Button>
              </>
            )}
          </div>
        }
      />

      {error && (
        <div className="notice notice--wait" role="alert">
          <Icon name="bell" size={20} />
          <div>
            <p className="notice__title">{error.message}</p>
            {error.stale && (
              <p className="notice__body">
                <button type="button" className="content-link" onClick={onReload}>
                  Reload the latest version
                </button>{' '}
                (your unsaved edits here will be lost).
              </p>
            )}
          </div>
        </div>
      )}
      <StatusNotice detail={detail} dirty={dirty} problems={problems} current={current} />

      {editable ? (
        <DetailsForm local={local} setLocal={setLocal} catalogue={catalogue} />
      ) : null}

      <Plate
        title="Checks"
        count={local.steps.length}
        subtitle={editable ? 'Each check is a yes/no question. Say where each answer leads: another check, or an outcome that ends the checks.' : 'Read-only: Captains may be part-way through this version.'}
        action={
          editable ? (
            <Button onClick={() => setLocal((l) => addStep(l))} disabled={local.steps.length >= 60}>
              <Icon name="check" size={16} />
              Add check
            </Button>
          ) : undefined
        }
      >
        <ol className="flow-steps">
          {local.steps.map((s, i) =>
            editable ? (
              <StepEditor key={s.key} index={i} local={local} setLocal={setLocal} />
            ) : (
              <StepReadOnly key={s.key} index={i} local={local} />
            ),
          )}
        </ol>
      </Plate>

      <Plate title="Versions" count={detail.versions.length} flush>
        <ul className="setup">
          {detail.versions.map((v) => (
            <li key={v.id} className={`setup-row${v.id === detail.id ? ' flow-version--here' : ''}`}>
              <div className="setup-row__main">
                <div className="setup-row__title">
                  {v.id === detail.id ? <span>Version {v.version}</span> : <Link to={`/platform/checks/${v.id}`}>Version {v.version}</Link>}
                  <Pill size="sm" tone={v.status === 'PUBLISHED' ? 'accent' : v.status === 'DRAFT' ? 'approaching' : 'neutral'}>
                    {v.status === 'PUBLISHED' ? 'Published' : v.status === 'DRAFT' ? 'Draft' : 'Retired'}
                  </Pill>
                  {v.id === detail.id && <span className="flow-version__here">viewing</span>}
                </div>
                <div className="setup-row__meta">
                  {v.publishedAt ? `Published ${formatDateTime(v.publishedAt)}` : 'Never published'}
                  {v.retiredAt ? ` · retired ${formatDateTime(v.retiredAt)}` : ''} · used on {v.requestCount}{' '}
                  {v.requestCount === 1 ? 'request' : 'requests'}
                </div>
              </div>
            </li>
          ))}
        </ul>
      </Plate>

      {previewing && <PreviewDialog local={local} name={local.name} onClose={() => setPreviewing(false)} />}

      {confirm === 'publish' && (
        <Dialog
          title="Publish these checks?"
          subtitle={`${detail.name} · version ${detail.flowVersion}`}
          onClose={() => setConfirm(null)}
          width={500}
          footer={
            <>
              <Button onClick={() => setConfirm(null)}>Cancel</Button>
              <Button variant="primary" disabled={busy !== null} onClick={publish}>
                {busy === 'publish' ? 'Publishing…' : 'Publish'}
              </Button>
            </>
          }
        >
          <p className="otp__note">
            Captains who report {describeTarget(detail.target).toLowerCase()} get these checks from now on.
            {current
              ? ` Version ${current.version} is retired; requests already part-way through it finish on it.`
              : ''}
            {local.sampleContent ? ' They are marked as sample checks, and Captains see that.' : ''}
          </p>
        </Dialog>
      )}
      {confirm === 'retire' && (
        <Dialog
          title="Withdraw these checks?"
          subtitle={`${detail.name} · version ${detail.flowVersion}`}
          onClose={() => setConfirm(null)}
          width={500}
          footer={
            <>
              <Button onClick={() => setConfirm(null)}>Cancel</Button>
              <Button variant="danger" disabled={busy !== null} onClick={retire}>
                {busy === 'retire' ? 'Withdrawing…' : 'Withdraw'}
              </Button>
            </>
          }
        >
          <p className="otp__note">
            New requests fall back to the next most general checks.
            {detail.openRequestCount > 0
              ? ` ${detail.openRequestCount} ${detail.openRequestCount === 1 ? 'request is' : 'requests are'} part-way through this version and will finish on it.`
              : ''}{' '}
            You can publish a new version later.
          </p>
        </Dialog>
      )}
      {confirm === 'discard' && (
        <Dialog
          title="Discard this draft?"
          subtitle={`${detail.name} · version ${detail.flowVersion}`}
          onClose={() => setConfirm(null)}
          width={460}
          footer={
            <>
              <Button onClick={() => setConfirm(null)}>Keep editing</Button>
              <Button variant="danger" disabled={busy !== null} onClick={discard}>
                {busy === 'discard' ? 'Discarding…' : 'Discard draft'}
              </Button>
            </>
          }
        >
          <p className="otp__note">The draft is deleted. Published versions are not affected.</p>
        </Dialog>
      )}
    </div>
  );
}

function StatusPill({ detail }: { detail: FlowDetail }) {
  return (
    <span className="flow-badges">
      <Pill size="sm" tone={detail.status === 'PUBLISHED' ? 'accent' : detail.status === 'DRAFT' ? 'approaching' : 'neutral'}>
        {detail.status === 'PUBLISHED' ? 'Published' : detail.status === 'DRAFT' ? 'Draft' : 'Retired'}
      </Pill>
      {detail.sampleContent && <Pill size="sm">Sample</Pill>}
    </span>
  );
}

function StatusNotice({
  detail,
  dirty,
  problems,
  current,
}: {
  detail: FlowDetail;
  dirty: boolean;
  problems: string[];
  current?: { version: number };
}) {
  if (detail.status === 'DRAFT') {
    if (dirty) {
      return (
        <div className="notice notice--wait" role="status">
          <Icon name="history" size={20} />
          <div>
            <p className="notice__title">Unsaved changes</p>
            <p className="notice__body">Save the draft to check it for problems. Captains see nothing until it is published.</p>
          </div>
        </div>
      );
    }
    if (problems.length > 0) {
      return (
        <div className="notice notice--wait" role="status">
          <Icon name="wrench" size={20} />
          <div>
            <p className="notice__title">Fix before publishing</p>
            <ul className="flow-problems">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </div>
        </div>
      );
    }
    return (
      <div className="notice notice--ok" role="status">
        <Icon name="check" size={20} />
        <div>
          <p className="notice__title">Ready to publish</p>
          <p className="notice__body">
            {current
              ? `Publishing replaces version ${current.version} for new requests.`
              : 'Publishing makes these the checks Captains get for this problem.'}
          </p>
        </div>
      </div>
    );
  }
  if (detail.status === 'PUBLISHED') {
    return (
      <div className="notice notice--ok" role="status">
        <Icon name="check" size={20} />
        <div>
          <p className="notice__title">Live</p>
          <p className="notice__body">
            Published {formatDateTime(detail.publishedAt)}
            {detail.publishedBy ? ` by ${detail.publishedBy}` : ''} · used on {detail.requestCount}{' '}
            {detail.requestCount === 1 ? 'request' : 'requests'}
            {detail.openRequestCount > 0 ? `, ${detail.openRequestCount} still in progress` : ''}.
          </p>
        </div>
      </div>
    );
  }
  return (
    <div className="notice notice--wait" role="status">
      <Icon name="history" size={20} />
      <div>
        <p className="notice__title">Retired</p>
        <p className="notice__body">
          New requests no longer get this version. It is kept because {detail.requestCount}{' '}
          {detail.requestCount === 1 ? 'request' : 'requests'} used it.
        </p>
      </div>
    </div>
  );
}

function DetailsForm({
  local,
  setLocal,
  catalogue,
}: {
  local: Local;
  setLocal: React.Dispatch<React.SetStateAction<Local>>;
  catalogue: CategoryProblemTypes[];
}) {
  const problems = catalogue.find((c) => c.id === local.equipmentCategoryId)?.problemTypes ?? [];
  return (
    <Plate title="Details">
      <div className="flow-details">
        <Field label="Name" htmlFor="flow-name">
          <input id="flow-name" className="input" maxLength={160} value={local.name} onChange={(e) => setLocal((l) => ({ ...l, name: e.target.value }))} />
        </Field>
        <Field label="Equipment" htmlFor="flow-category">
          <select
            id="flow-category"
            className="input"
            value={local.equipmentCategoryId ?? ''}
            onChange={(e) => setLocal((l) => ({ ...l, equipmentCategoryId: e.target.value ? Number(e.target.value) : null, problemTypeId: null }))}
          >
            <option value="">Any equipment (general)</option>
            {catalogue.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Problem" htmlFor="flow-problem">
          <select
            id="flow-problem"
            className="input"
            disabled={local.equipmentCategoryId === null}
            value={local.problemTypeId ?? ''}
            onChange={(e) => setLocal((l) => ({ ...l, problemTypeId: e.target.value ? Number(e.target.value) : null }))}
          >
            <option value="">Any problem</option>
            {problems
              .filter((p) => p.active || p.id === local.problemTypeId)
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                  {p.active ? '' : ' (retired)'}
                </option>
              ))}
          </select>
        </Field>
        <label className="flow-sample">
          <input type="checkbox" checked={local.sampleContent} onChange={(e) => setLocal((l) => ({ ...l, sampleContent: e.target.checked }))} />
          <span>
            <b>Sample checks</b>
            <small>Captains see a “Sample checks” label. Clear it once Seastella has approved the content.</small>
          </span>
        </label>
      </div>
    </Plate>
  );
}

// -------------------------------------------------------------------- steps

type Target = string; // "" | "next:<key>" | "out:<OUTCOME>"

function encode(b: Branch): Target {
  if (b.outcome) return `out:${b.outcome}`;
  if (b.nextKey) return `next:${b.nextKey}`;
  return '';
}

function decode(t: Target): Branch {
  if (t.startsWith('out:')) return { nextKey: null, outcome: t.slice(4) as Outcome };
  if (t.startsWith('next:')) return { nextKey: t.slice(5), outcome: null };
  return { nextKey: null, outcome: null };
}

function addStep(l: Local): Local {
  const used = new Set(l.steps.map((s) => s.key));
  let n = l.steps.length + 1;
  while (used.has(`s${n}`)) n++;
  const step: CheckStep = { key: `s${n}`, prompt: '', helpText: '', yes: { outcome: 'RESOLVED' }, no: { outcome: 'UNRESOLVED' } };
  return { ...l, steps: [...l.steps, step], startStepKey: l.steps.length === 0 ? step.key : l.startStepKey };
}

function StepEditor({
  index,
  local,
  setLocal,
}: {
  index: number;
  local: Local;
  setLocal: React.Dispatch<React.SetStateAction<Local>>;
}) {
  const step = local.steps[index];
  const isStart = local.startStepKey === step.key;
  const leadsHere = local.steps.filter((s) => s.yes.nextKey === step.key || s.no.nextKey === step.key).length;

  const update = (change: Partial<CheckStep>) =>
    setLocal((l) => ({ ...l, steps: l.steps.map((s, i) => (i === index ? { ...s, ...change } : s)) }));

  const move = (delta: number) =>
    setLocal((l) => {
      const steps = [...l.steps];
      const [s] = steps.splice(index, 1);
      steps.splice(index + delta, 0, s);
      return { ...l, steps };
    });

  const remove = () =>
    setLocal((l) => {
      const steps = l.steps
        .filter((_, i) => i !== index)
        .map((s) => ({
          ...s,
          // Answers that led to the removed check are left unset, so the author decides where they go now.
          yes: s.yes.nextKey === step.key ? { nextKey: null, outcome: null } : s.yes,
          no: s.no.nextKey === step.key ? { nextKey: null, outcome: null } : s.no,
        }));
      return { ...l, steps, startStepKey: isStart ? (steps[0]?.key ?? '') : l.startStepKey };
    });

  return (
    <li className={`flow-step${isStart ? ' flow-step--start' : ''}`}>
      <div className="flow-step__head">
        <span className="flow-step__n">{index + 1}</span>
        {isStart ? (
          <Pill size="sm" tone="accent">
            First check
          </Pill>
        ) : (
          <button type="button" className="content-link" onClick={() => setLocal((l) => ({ ...l, startStepKey: step.key }))}>
            Make this the first check
          </button>
        )}
        {!isStart && leadsHere === 0 && <span className="flow-step__warn">No answer leads here yet</span>}
        <span className="flow-step__tools">
          <button type="button" className="flow-step__tool" aria-label={`Move check ${index + 1} up`} disabled={index === 0} onClick={() => move(-1)}>
            ↑
          </button>
          <button
            type="button"
            className="flow-step__tool"
            aria-label={`Move check ${index + 1} down`}
            disabled={index === local.steps.length - 1}
            onClick={() => move(1)}
          >
            ↓
          </button>
          <button type="button" className="flow-step__tool flow-step__tool--danger" aria-label={`Remove check ${index + 1}`} onClick={remove}>
            Remove
          </button>
        </span>
      </div>

      <Field label="Question" htmlFor={`q-${step.key}`}>
        <textarea
          id={`q-${step.key}`}
          className="textarea flow-step__prompt"
          rows={2}
          maxLength={500}
          value={step.prompt}
          placeholder="A yes/no question, e.g. “Is the radar in TRANSMIT?”"
          onChange={(e) => update({ prompt: e.target.value })}
        />
      </Field>
      <Field label="Guidance for the Captain" htmlFor={`h-${step.key}`} hint="Optional. Shown under the question.">
        <textarea
          id={`h-${step.key}`}
          className="textarea flow-step__help"
          rows={2}
          maxLength={1000}
          value={step.helpText ?? ''}
          onChange={(e) => update({ helpText: e.target.value })}
        />
      </Field>

      <div className="flow-step__branches">
        <BranchSelect label="If yes" id={`yes-${step.key}`} value={encode(step.yes)} own={step.key} local={local} onChange={(t) => update({ yes: decode(t) })} />
        <BranchSelect label="If no" id={`no-${step.key}`} value={encode(step.no)} own={step.key} local={local} onChange={(t) => update({ no: decode(t) })} />
      </div>
    </li>
  );
}

function BranchSelect({
  label,
  id,
  value,
  own,
  local,
  onChange,
}: {
  label: string;
  id: string;
  value: Target;
  own: string;
  local: Local;
  onChange: (t: Target) => void;
}) {
  return (
    <Field label={label} htmlFor={id}>
      <select id={id} className={`input${value === '' ? ' input--invalid' : ''}`} value={value} onChange={(e) => onChange(e.target.value)}>
        {value === '' && <option value="">Choose where this answer leads</option>}
        <optgroup label="End the checks">
          {(Object.keys(OUTCOME_LABEL) as Outcome[]).map((o) => (
            <option key={o} value={`out:${o}`}>
              End: {OUTCOME_LABEL[o]}
            </option>
          ))}
        </optgroup>
        <optgroup label="Go to another check">
          {local.steps.map((s, i) =>
            s.key === own ? null : (
              <option key={s.key} value={`next:${s.key}`}>
                Check {i + 1}: {excerpt(s.prompt)}
              </option>
            ),
          )}
        </optgroup>
      </select>
    </Field>
  );
}

function StepReadOnly({ index, local }: { index: number; local: Local }) {
  const step = local.steps[index];
  return (
    <li className={`flow-step flow-step--read${local.startStepKey === step.key ? ' flow-step--start' : ''}`}>
      <div className="flow-step__head">
        <span className="flow-step__n">{index + 1}</span>
        {local.startStepKey === step.key && (
          <Pill size="sm" tone="accent">
            First check
          </Pill>
        )}
      </div>
      <p className="flow-step__q">{step.prompt}</p>
      {step.helpText && <p className="flow-step__h">{step.helpText}</p>}
      <div className="flow-step__routes">
        <span>
          <b>Yes</b> → {route(step.yes, local)}
        </span>
        <span>
          <b>No</b> → {route(step.no, local)}
        </span>
      </div>
    </li>
  );
}

function route(b: Branch, local: Local) {
  if (b.outcome) return `ends: ${OUTCOME_LABEL[b.outcome]}`;
  const i = local.steps.findIndex((s) => s.key === b.nextKey);
  return i >= 0 ? `check ${i + 1}` : 'not set';
}

function excerpt(text: string) {
  const t = text.trim() || '(no question yet)';
  return t.length > 60 ? `${t.slice(0, 57)}…` : t;
}

// ------------------------------------------------------------------ preview

/** Walks the checks as the Captain will, from the edits on screen (saved or not). */
function PreviewDialog({ local, name, onClose }: { local: Local; name: string; onClose: () => void }) {
  const [at, setAt] = useState<string | null>(local.startStepKey);
  const [path, setPath] = useState<{ prompt: string; yes: boolean }[]>([]);
  const [ended, setEnded] = useState<Outcome | 'BROKEN' | null>(null);
  const step = local.steps.find((s) => s.key === at);
  const number = local.steps.findIndex((s) => s.key === at) + 1;

  const answer = (yes: boolean) => {
    if (!step) return;
    const b = yes ? step.yes : step.no;
    const nextPath = [...path, { prompt: step.prompt, yes }];
    setPath(nextPath);
    if (b.outcome) {
      setEnded(b.outcome);
    } else if (b.nextKey && local.steps.some((s) => s.key === b.nextKey) && nextPath.length < 50) {
      setAt(b.nextKey);
    } else {
      setEnded('BROKEN');
    }
  };

  const restart = () => {
    setAt(local.startStepKey);
    setPath([]);
    setEnded(null);
  };

  return (
    <Dialog
      title="Preview"
      subtitle={`${name} · as the Captain sees it. Nothing is recorded.`}
      onClose={onClose}
      width={620}
      footer={
        <>
          <Button onClick={restart}>Start again</Button>
          <Button variant="primary" onClick={onClose}>
            Close preview
          </Button>
        </>
      }
    >
      <div className="checks">
        {ended === null && step ? (
          <>
            <p className="flow-preview__n">Check {path.length + 1} (check {number} in the editor)</p>
            <div className="checks__question" aria-live="polite">
              <p className="checks__prompt">{step.prompt || '(no question yet)'}</p>
              {step.helpText && <p className="checks__help">{step.helpText}</p>}
            </div>
            <div className="checks__answers">
              <Button variant="primary" size="xl" onClick={() => answer(true)}>
                Yes
              </Button>
              <Button variant="secondary" size="xl" onClick={() => answer(false)}>
                No
              </Button>
            </div>
          </>
        ) : ended === null || ended === 'BROKEN' ? (
          <div className="checks__outcome">
            <Icon name="wrench" size={22} />
            <div>
              <p className="checks__outcome-title">This answer does not lead anywhere yet</p>
              <p className="checks__outcome-body">Choose where it goes in the editor. A draft like this cannot be published.</p>
            </div>
          </div>
        ) : (
          <div className={`checks__outcome checks__outcome--${ended.toLowerCase()}`}>
            <Icon name={ended === 'UNRESOLVED' ? 'wrench' : 'check'} size={22} />
            <div>
              <p className="checks__outcome-title">Ends: {OUTCOME_LABEL[ended]}</p>
              <p className="checks__outcome-body">
                The Captain now records what they found{ended === 'TEMPORARY_FIX' ? ', including the temporary fix,' : ''} and submits
                the request.
              </p>
            </div>
          </div>
        )}
        {path.length > 0 && (
          <ol className="checks__log" aria-label="Answers so far">
            {path.map((p, i) => (
              <li key={i}>
                <span className="checks__log-n">{i + 1}</span>
                <div>
                  <p className="checks__log-q">{p.prompt}</p>
                </div>
                <span className={`checks__log-a checks__log-a--${p.yes ? 'yes' : 'no'}`}>{p.yes ? 'Yes' : 'No'}</span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </Dialog>
  );
}
