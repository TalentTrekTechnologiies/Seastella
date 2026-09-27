import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchJobHistory, type FinishedJob } from '@/api/registers';
import { ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { Pill } from '@/design-system/StatusBadge';
import { ErrorState, LoadingState } from '@/design-system/States';
import { formatDate } from '@/lib/format';
import './registers.css';

/**
 * JOB HISTORY — the engineer's finished jobs and what they reported on each
 * (SoW §6.3). The work, the parts and the outcome; never the cost, which is
 * not the engineer's to see.
 */
export function JobHistoryPage() {
  const [search, setSearch] = useState('');
  const history = useQuery({ queryKey: ['job-history'], queryFn: fetchJobHistory });

  if (history.error) return <ErrorState error={history.error} onRetry={() => history.refetch()} />;

  const q = search.trim().toLowerCase();
  const jobs = (history.data ?? []).filter(
    (f) =>
      !q ||
      [f.job.requestNumber, f.job.vesselName, f.job.spareName, f.job.title, f.workPerformed]
        .some((v) => v?.toLowerCase().includes(q)),
  );

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'My work' }, { label: 'Job history', strong: true }]}
        title="Job history"
        subtitle="Every job you have finished, newest first, with the work you reported."
      />

      <Plate
        title="Finished jobs"
        count={history.data?.length}
        action={
          <input
            className="input input--search"
            type="search"
            placeholder="Find job, vessel, equipment…"
            aria-label="Find a job"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        }
        flush
      >
        {history.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={5} />
          </div>
        ) : jobs.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>
              {q ? 'No finished job matches that.' : 'No finished jobs yet. A job appears here once you submit its completion report.'}
            </EmptyNote>
          </div>
        ) : (
          <ul className="reg__list">
            {jobs.map((f) => (
              <JobItem key={f.job.requestId} finished={f} />
            ))}
          </ul>
        )}
      </Plate>
    </div>
  );
}

function JobItem({ finished: f }: { finished: FinishedJob }) {
  const done = f.job.status === 'COMPLETED';
  return (
    <li className="reg__item reg__item--stack">
      <div className="reg__top">
        <Link to={`/requests/${f.job.requestId}`} className="mono">
          <b>{f.job.requestNumber}</b>
        </Link>
        <span className="reg__jobname">
          {f.job.spareName} · {f.job.vesselName}
        </span>
        <Pill tone={done ? 'normal' : 'approaching'} size="sm">
          {done ? 'Completed' : 'Report sent to the Coordinator'}
        </Pill>
      </div>
      <p className="reg__desc">{f.job.title}</p>
      <dl className="reg__report">
        {f.workPerformed && (
          <>
            <dt>Work done</dt>
            <dd>{f.workPerformed}</dd>
          </>
        )}
        {f.partsUsed && (
          <>
            <dt>Parts used</dt>
            <dd>{f.partsUsed}</dd>
          </>
        )}
        {f.outcome && (
          <>
            <dt>Outcome</dt>
            <dd>{f.outcome}</dd>
          </>
        )}
      </dl>
      <p className="reg__meta">
        {f.reportedAt ? `Reported ${formatDate(f.reportedAt)}` : 'Reported'}
        {f.completedAt && ` · closed ${formatDate(f.completedAt)}`}
      </p>
    </li>
  );
}
