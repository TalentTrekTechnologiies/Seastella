import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchEngineerLoads, type EngineerLoad } from '@/api/registers';
import { ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import { ErrorState, LoadingState } from '@/design-system/States';
import { formatDate, relativeTime } from '@/lib/format';
import './registers.css';

/**
 * ENGINEERS — the Coordinator's service engineers and what each one holds
 * (SoW §6.2 step 5, §8.4). Who is free and who is busy decides who is
 * assigned next; the jobs are only those on vessels this Coordinator serves.
 */
export function EngineersPage() {
  const loads = useQuery({ queryKey: ['engineer-loads'], queryFn: fetchEngineerLoads, refetchInterval: 30_000 });

  if (loads.error) return <ErrorState error={loads.error} onRetry={() => loads.refetch()} />;

  const list = loads.data ?? [];
  const free = list.filter((e) => e.activeJobs.length === 0).length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Service operations' }, { label: 'Engineers', strong: true }]}
        title="Engineers"
        subtitle="Every service engineer, the jobs they are on now and what they have finished. Engineers are assigned on the request, once its invoice is accepted."
      />

      {loads.isLoading ? (
        <LoadingState rows={5} label="Loading engineers" />
      ) : list.length === 0 ? (
        <Plate title="Engineers">
          <EmptyNote>No service engineers yet. The Platform Admin adds them under Users &amp; roles.</EmptyNote>
        </Plate>
      ) : (
        <Plate
          title="Engineers"
          count={list.length}
          subtitle={`${free} free now · ${list.length - free} on a job`}
          flush
        >
          <ul className="reg__list">
            {list.map((e) => (
              <EngineerItem key={e.id} engineer={e} />
            ))}
          </ul>
        </Plate>
      )}
    </div>
  );
}

function EngineerItem({ engineer: e }: { engineer: EngineerLoad }) {
  const busy = e.activeJobs.length > 0;
  return (
    <li className="reg__item reg__item--stack">
      <div className="reg__top">
        <span className="reg__avatar" aria-hidden="true">
          {e.fullName
            .split(/\s+/)
            .map((p) => p[0])
            .slice(0, 2)
            .join('')
            .toUpperCase()}
        </span>
        <div className="reg__who">
          <b>{e.fullName}</b>
          <span>{e.email}</span>
        </div>
        <Pill tone={busy ? 'approaching' : 'normal'} size="sm">
          {busy ? `${e.activeJobs.length} active ${e.activeJobs.length === 1 ? 'job' : 'jobs'}` : 'Free'}
        </Pill>
      </div>
      <p className="reg__meta">
        {e.completedJobs} {e.completedJobs === 1 ? 'job' : 'jobs'} finished
        {e.lastCompletedAt && <> · last {relativeTime(e.lastCompletedAt)}</>}
      </p>
      {busy && (
        <ul className="reg__jobs">
          {e.activeJobs.map((j) => (
            <li key={j.requestId}>
              <Link to={`/requests/${j.requestId}`} className="mono">
                {j.requestNumber}
              </Link>
              <span className="reg__jobname">
                {j.spareName} · {j.vesselName}
              </span>
              {j.priority && <PriorityChip value={j.priority} />}
              <Pill size="sm">{j.statusLabel}</Pill>
              <span className="reg__since">since {formatDate(j.lastUpdatedAt ?? j.raisedAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}
