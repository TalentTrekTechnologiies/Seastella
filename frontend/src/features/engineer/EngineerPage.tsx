import { useState } from 'react';
import { fetchServiceEngineer } from '@/api/dashboards';
import type { EngineerJob } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, DefinitionList, PageHeader, Panel } from '@/design-system/Panel';
import { ErrorState, KpiSkeleton, LoadingState, EmptyState } from '@/design-system/States';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import { Icon } from '@/design-system/Icon';
import '../shared/dashboard.css';
import './engineer.css';

/**
 * Service Engineer — a job workspace (SoW §5, master brief §7.6).
 *
 * <p>Not an analytics dashboard. Today's work leads, the selected job fills the
 * detail pane, and everything present is something needed to perform the job.
 *
 * <p>What is <b>absent</b> is as deliberate as what is present: no other
 * engineer's jobs, no unassigned vessel, no organization-wide figures, no
 * activity feed, no administration, and <b>no invoice amount</b>. The engineer
 * is told the job is authorised — never what it costs (§12).
 */
export function EngineerPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'service-engineer',
    fetchServiceEngineer,
  );
  const [selectedId, setSelectedId] = useState<number | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const allJobs = [
    ...(data?.todaysSchedule ?? []),
    ...(data?.inProgress ?? []),
    ...(data?.upcoming ?? []),
    ...(data?.awaitingMyReport ?? []),
  ];
  const selected = allJobs.find((j) => j.serviceRequestId === selectedId) ?? allJobs[0] ?? null;

  return (
    <div className="page">
      <PageHeader
        eyebrow="Service engineer"
        title="My jobs"
        meta={
          data && (
            <>
              <span>{allJobs.length} active assignments</span>
              <span>Updated {relativeTime(data.meta.generatedAt)}</span>
            </>
          )
        }
        actions={
          <Button onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {isLoading || !data ? <KpiSkeleton count={5} /> : <KpiRow items={data.kpis} />}

      <div className="eng-layout">
        <div className="eng-queues">
          <JobGroup
            title="Today"
            subtitle="Assigned and due"
            jobs={data?.todaysSchedule ?? []}
            loading={isLoading}
            selectedId={selected?.serviceRequestId ?? null}
            onSelect={setSelectedId}
            emptyTitle="Nothing scheduled today"
            emptyBody="Assigned jobs appear here once the Coordinator dispatches them."
            tone="today"
          />

          <JobGroup
            title="In progress"
            jobs={data?.inProgress ?? []}
            loading={isLoading}
            selectedId={selected?.serviceRequestId ?? null}
            onSelect={setSelectedId}
            emptyTitle="No job in progress"
            tone="active"
          />

          <JobGroup
            title="Reported, awaiting Coordinator"
            subtitle="Your report has been submitted"
            jobs={data?.awaitingMyReport ?? []}
            loading={isLoading}
            selectedId={selected?.serviceRequestId ?? null}
            onSelect={setSelectedId}
            emptyTitle="No reports pending review"
          />

          <JobGroup
            title="Upcoming"
            jobs={data?.upcoming ?? []}
            loading={isLoading}
            selectedId={selected?.serviceRequestId ?? null}
            onSelect={setSelectedId}
            emptyTitle="No upcoming jobs"
          />
        </div>

        <div className="eng-detail">
          {isLoading ? (
            <Panel title="Job detail">
              <LoadingState rows={6} />
            </Panel>
          ) : !selected ? (
            <Panel title="Job detail">
              <EmptyState
                title="No job selected"
                body="You have no active assignments. The Service Coordinator assigns jobs once an invoice is accepted."
                icon="inbox"
              />
            </Panel>
          ) : (
            <JobDetail job={selected} />
          )}

          {!isLoading && data && data.recentlyCompleted.length > 0 && (
            <Panel
              title="Recently completed"
              count={data.recentlyCompleted.length}
              padded={false}
            >
              <ul className="eng-history">
                {data.recentlyCompleted.slice(0, 6).map((c) => (
                  <li key={c.serviceRequestId}>
                    <div>
                      <span className="eng-history__spare">{c.spareName}</span>
                      <span className="cell-sub">
                        {c.vesselName} · <span className="mono">{c.requestNumber}</span>
                      </span>
                    </div>
                    <span className="eng-history__date">{relativeTime(c.reportedAt)}</span>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}

function JobGroup({
  title,
  subtitle,
  jobs,
  loading,
  selectedId,
  onSelect,
  emptyTitle,
  emptyBody,
  tone,
}: {
  title: string;
  subtitle?: string;
  jobs: EngineerJob[];
  loading: boolean;
  selectedId: number | null;
  onSelect: (id: number) => void;
  emptyTitle: string;
  emptyBody?: string;
  tone?: 'today' | 'active';
}) {
  return (
    <Panel
      title={title}
      subtitle={subtitle}
      count={jobs.length}
      padded={false}
      tone={tone === 'today' && jobs.length > 0 ? 'attention' : 'default'}
    >
      {loading ? (
        <LoadingState rows={2} />
      ) : jobs.length === 0 ? (
        <EmptyState title={emptyTitle} body={emptyBody} />
      ) : (
        <ul className="eng-jobs">
          {jobs.map((j) => (
            <li key={j.serviceRequestId}>
              <button
                type="button"
                className={`eng-job${selectedId === j.serviceRequestId ? ' eng-job--selected' : ''}`}
                onClick={() => onSelect(j.serviceRequestId)}
              >
                <span className="eng-job__top">
                  <span className="eng-job__title">{j.spareName}</span>
                  <PriorityChip value={j.priority} />
                </span>
                <span className="eng-job__vessel">
                  {j.vesselName}
                  {j.imoNumber && <span className="mono"> · IMO {j.imoNumber}</span>}
                </span>
                <span className="eng-job__meta">
                  <span className="mono">{j.requestNumber}</span>
                  {j.ageDays !== null && <span>{j.ageDays}d</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function JobDetail({ job }: { job: EngineerJob }) {
  return (
    <Panel
      title={job.spareName}
      subtitle={`${job.vesselName} · ${job.requestNumber}`}
      action={<Pill tone="accent" size="sm">{job.statusLabel}</Pill>}
    >
      <div className="eng-detail__body">
        {/* Authorisation status only. The engineer is told the job is cleared
            to proceed, never its cost (SoW §12). */}
        <div className={`eng-auth${job.invoiceAccepted ? ' eng-auth--ok' : ''}`}>
          <Icon name={job.invoiceAccepted ? 'check' : 'invoice'} size={15} />
          <span>
            {job.invoiceAccepted
              ? 'Authorised — the Ship Manager has accepted the invoice for this job'
              : 'Not yet authorised for attendance'}
          </span>
        </div>

        <DefinitionList
          items={[
            { label: 'Vessel', value: job.vesselName },
            { label: 'IMO', value: job.imoNumber ?? '—', mono: true },
            { label: 'Spare', value: job.spareName },
            { label: 'VMP ref', value: job.sparePath, mono: true },
            { label: 'Category', value: job.categoryCode },
            { label: 'Make', value: job.make ?? '—' },
            { label: 'Model', value: job.model ?? '—' },
            { label: 'Serial', value: job.serialNumber ?? '—', mono: true },
            { label: 'Priority', value: <PriorityChip value={job.priority} /> },
            { label: 'Assigned', value: formatDate(job.assignedAt) },
          ]}
        />

        <div className="eng-report">
          <h3 className="eng-report__title">Completion report</h3>
          <p className="eng-report__note">
            Work performed, parts used and outcome are submitted to the Service Coordinator.
            Reporting and attachments arrive in the next stage.
          </p>
          <Button variant="primary" disabled title="Available in the next stage">
            Submit completion report
          </Button>
        </div>
      </div>
    </Panel>
  );
}
