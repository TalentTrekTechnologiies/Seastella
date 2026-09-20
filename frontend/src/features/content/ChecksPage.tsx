import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  createFlow,
  describeTarget,
  fetchFlows,
  fetchProblemTypes,
  type CategoryProblemTypes,
  type FlowSummary,
} from '@/api/content';
import { Button, Chip, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDate } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import './content.css';

/**
 * GUIDED CHECKS — Platform Admin (SoW §13, TSA-03).
 *
 * <p>Every set of checks a Captain can be taken through, what it covers, and
 * whether it is live. Which checks a Captain gets is decided most specific
 * first: the equipment and problem they chose, then any problem on that
 * equipment, then the general checks.
 */
export function ChecksPage() {
  const navigate = useNavigate();
  const flows = useQuery({ queryKey: ['content-flows'], queryFn: fetchFlows });
  const catalogue = useQuery({ queryKey: ['problem-types'], queryFn: fetchProblemTypes });
  const [filter, setFilter] = useState<'all' | 'live' | 'draft' | 'sample'>('all');
  const [adding, setAdding] = useState(false);

  const all = flows.data ?? [];
  const counts = {
    all: all.length,
    live: all.filter((f) => f.published).length,
    draft: all.filter((f) => f.draft).length,
    sample: all.filter((f) => f.sampleContent).length,
  };
  const shown = all.filter((f) =>
    filter === 'live' ? f.published : filter === 'draft' ? f.draft : filter === 'sample' ? f.sampleContent : true,
  );
  const coverage = useMemo(() => uncovered(catalogue.data ?? [], all), [catalogue.data, all]);

  if (flows.error) return <ErrorState error={flows.error} onRetry={() => flows.refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Guided checks', strong: true }]}
        title="Guided checks"
        subtitle="The yes/no checks a Captain works through before a request goes for approval. Edit a draft, preview it as the Captain sees it, then publish."
        actions={
          <Button variant="primary" onClick={() => setAdding(true)}>
            <Icon name="check" size={16} />
            New checks
          </Button>
        }
      />

      <Plate
        title="Checks"
        count={all.length}
        subtitle="Most specific first: a problem on an equipment, then any problem on it, then the general checks"
        action={
          <div className="content-filters">
            <Chip on={filter === 'all'} onClick={() => setFilter('all')} count={counts.all}>All</Chip>
            <Chip on={filter === 'live'} onClick={() => setFilter('live')} count={counts.live}>Published</Chip>
            <Chip on={filter === 'draft'} onClick={() => setFilter('draft')} count={counts.draft}>Drafts</Chip>
            <Chip on={filter === 'sample'} onClick={() => setFilter('sample')} count={counts.sample}>Sample</Chip>
          </div>
        }
        flush
      >
        {flows.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={4} />
          </div>
        ) : shown.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>
              {all.length === 0
                ? 'No guided checks yet. Until some are published, Captains go straight to their Ship Manager without checks.'
                : 'No checks match this filter.'}
            </EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {shown.map((f) => (
              <FlowRow key={f.code} flow={f} />
            ))}
          </ul>
        )}
      </Plate>

      {coverage.length > 0 && (
        <Plate
          title="Problems without their own checks"
          count={coverage.length}
          subtitle="Captains choosing these get the equipment's checks or the general checks"
          flush
        >
          <ul className="content-gaps">
            {coverage.slice(0, 40).map((g) => (
              <li key={g.id}>
                <span>
                  <b>{g.category}</b> {g.label}
                </span>
              </li>
            ))}
          </ul>
          {coverage.length > 40 && <p className="content-more">and {coverage.length - 40} more</p>}
        </Plate>
      )}

      {adding && catalogue.data && (
        <NewChecksDialog
          catalogue={catalogue.data}
          onClose={() => setAdding(false)}
          onCreated={(id) => navigate(`/platform/checks/${id}`)}
        />
      )}
    </div>
  );
}

function FlowRow({ flow: f }: { flow: FlowSummary }) {
  const open = f.draft ?? f.published;
  const lastVersion = open ?? null;
  return (
    <li className="setup-row">
      <div className="setup-row__main">
        <div className="setup-row__title">
          {open ? <Link to={`/platform/checks/${open.id}`}>{f.name}</Link> : f.name}
          {f.published && <Pill size="sm" tone="accent">Published v{f.published.version}</Pill>}
          {f.draft && <Pill size="sm" tone="approaching">Draft v{f.draft.version}</Pill>}
          {!f.published && !f.draft && <Pill size="sm">Withdrawn</Pill>}
          {f.sampleContent && <Pill size="sm">Sample</Pill>}
        </div>
        <div className="setup-row__meta">
          {describeTarget(f.target)}
          {!f.target.problemTypeActive && ' (problem type retired)'}
        </div>
        <div className="setup-row__people">
          {f.published ? (
            <span>
              Live since <b>{formatDate(f.published.publishedAt)}</b> · used on <b>{f.published.requestCount}</b>{' '}
              {f.published.requestCount === 1 ? 'request' : 'requests'}
            </span>
          ) : (
            <span className="setup-row__missing">Not live: Captains do not get these checks</span>
          )}
        </div>
      </div>
      <div className="setup-row__actions">
        {lastVersion && (
          <Link className="cbtn cbtn--secondary cbtn--md" to={`/platform/checks/${lastVersion.id}`}>
            {f.draft ? 'Continue draft' : 'Open'}
          </Link>
        )}
      </div>
    </li>
  );
}

/** Name, what it covers, and the first question: enough for a valid draft to edit. */
function NewChecksDialog({
  catalogue,
  onClose,
  onCreated,
}: {
  catalogue: CategoryProblemTypes[];
  onClose: () => void;
  onCreated: (id: number) => void;
}) {
  const [name, setName] = useState('');
  const [categoryId, setCategoryId] = useState<number | ''>('');
  const [problemTypeId, setProblemTypeId] = useState<number | ''>('');
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const problems = catalogue.find((c) => c.id === categoryId)?.problemTypes.filter((p) => p.active) ?? [];
  const valid = name.trim() !== '' && question.trim() !== '';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const created = await createFlow({
        name,
        equipmentCategoryId: categoryId === '' ? null : categoryId,
        problemTypeId: problemTypeId === '' ? null : problemTypeId,
        firstQuestion: question,
      });
      onCreated(created.id);
    } catch (e) {
      setError(errorText(e, 'The checks could not be created.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="New guided checks"
      subtitle="Starts as a draft. Captains get nothing until you publish."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Creating…' : 'Create draft'}
          </Button>
        </>
      }
    >
      <Field label="Name" htmlFor="flow-name" hint="What an administrator sees, e.g. “Radar: no echoes”.">
        <input id="flow-name" className="input" value={name} maxLength={160} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Equipment" htmlFor="flow-category" hint="Leave as “Any equipment” for the general checks used when nothing more specific exists.">
        <select
          id="flow-category"
          className="input"
          value={categoryId}
          onChange={(e) => {
            setCategoryId(e.target.value ? Number(e.target.value) : '');
            setProblemTypeId('');
          }}
        >
          <option value="">Any equipment</option>
          {catalogue.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </Field>
      {categoryId !== '' && (
        <Field
          label="Problem"
          htmlFor="flow-problem"
          hint={problems.length ? 'Or any problem on this equipment.' : 'This equipment has no problem types yet; add them under Problem types.'}
        >
          <select id="flow-problem" className="input" value={problemTypeId} onChange={(e) => setProblemTypeId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Any problem</option>
            {problems.map((p) => (
              <option key={p.id} value={p.id}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Field label="First question" htmlFor="flow-question" hint="A yes/no question. You add the checks that follow in the editor.">
        <textarea id="flow-question" className="textarea" rows={3} maxLength={500} value={question} onChange={(e) => setQuestion(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

function uncovered(catalogue: CategoryProblemTypes[], flows: FlowSummary[]) {
  const covered = new Set(flows.filter((f) => f.published && f.target.problemTypeId).map((f) => f.target.problemTypeId));
  return catalogue.flatMap((c) =>
    c.problemTypes.filter((p) => p.active && !covered.has(p.id)).map((p) => ({ id: p.id, category: c.name, label: p.label })),
  );
}
