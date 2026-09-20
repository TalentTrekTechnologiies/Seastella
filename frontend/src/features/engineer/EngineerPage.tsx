import { useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchServiceEngineer } from '@/api/dashboards';
import type { EngineerJob, ServiceEngineerDashboard } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, relativeTime } from '@/lib/format';
import { ConsoleHeader, EmptyNote, Plate, Segmented } from '@/design-system/Console';
import { RefreshButton } from '@/design-system/ConsoleParts';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import { Icon } from '@/design-system/Icon';
import { categoryLabel } from '@/design-system/status';

/**
 * ENGINEER WORKBOARD — Service Engineer (SoW §5, master brief §7.6).
 *
 * <p>Scope: only jobs assigned to this engineer (`JOB_SET`). Not an analytics
 * dashboard: the job in hand fills the page, and everything present is
 * something needed to do the work. The only action this role holds is
 * submitting a completion report on its own job (RBAC matrix).
 *
 * <p>What is absent is as deliberate as what is present: no other engineer's
 * jobs, no unassigned vessel, no organization-wide figures, no activity feed,
 * and <b>no invoice amount</b>. The engineer is told a job is authorised —
 * never what it costs (§12). The payload carries no amount to show.
 */

type Group = 'today' | 'inProgress' | 'awaiting' | 'upcoming';

export function EngineerPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'service-engineer',
    fetchServiceEngineer,
  );
  const [group, setGroup] = useState<Group | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const groups: Record<Group, EngineerJob[]> = {
    today: data?.todaysSchedule ?? [],
    inProgress: data?.inProgress ?? [],
    awaiting: data?.awaitingMyReport ?? [],
    upcoming: data?.upcoming ?? [],
  };
  const active = groups.today.length + groups.inProgress.length + groups.awaiting.length + groups.upcoming.length;

  // Open on the first group that has work in it.
  const order: Group[] = ['today', 'inProgress', 'awaiting', 'upcoming'];
  const currentGroup = group ?? order.find((g) => groups[g].length > 0) ?? 'today';
  const jobs = groups[currentGroup];
  const selected = jobs.find((j) => j.serviceRequestId === selectedId) ?? jobs[0] ?? null;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Service engineer' }, { label: `${active} active assignments`, strong: true }]}
        title="Engineer workboard"
        subtitle="Your assigned work, the job in hand and what you have completed."
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading your jobs" />
      ) : (
        <>
          <DayStrip data={data} />

          <div className="workboard">
            <div className="stack-col">
              <Plate title="My jobs" count={jobs.length} subtitle="Choose a job to open its job sheet" flush>
                <div className="attn__tools">
                  <Segmented<Group>
                    label="Job group"
                    value={currentGroup}
                    onChange={(g) => {
                      setGroup(g);
                      setSelectedId(null);
                    }}
                    options={[
                      { value: 'today', label: 'Today', count: groups.today.length },
                      { value: 'inProgress', label: 'In progress', count: groups.inProgress.length },
                      { value: 'awaiting', label: 'Reported', count: groups.awaiting.length },
                      { value: 'upcoming', label: 'Upcoming', count: groups.upcoming.length },
                    ]}
                  />
                </div>

                {jobs.length === 0 ? (
                  <div className="qlist__empty">
                    <EmptyNote>{EMPTY[currentGroup]}</EmptyNote>
                  </div>
                ) : (
                  <div className="jobs">
                    {jobs.map((j) => (
                      <button
                        type="button"
                        key={j.serviceRequestId}
                        className={`job${selected?.serviceRequestId === j.serviceRequestId ? ' job--on' : ''}`}
                        onClick={() => setSelectedId(j.serviceRequestId)}
                        aria-pressed={selected?.serviceRequestId === j.serviceRequestId}
                      >
                        <span className="job__top">
                          <span className="job__title">{j.spareName}</span>
                          <PriorityChip value={j.priority} />
                        </span>
                        <span className="job__line">{j.title}</span>
                        <span className="job__line">
                          {j.vesselName}
                          {j.imoNumber && ` · IMO ${j.imoNumber}`}
                        </span>
                        <span className="job__meta">
                          <span className="mono">{j.requestNumber}</span>
                          {j.scheduledFor && <span>Scheduled {formatDate(j.scheduledFor)}</span>}
                          {j.ageDays !== null && <span>{j.ageDays} days open</span>}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              </Plate>

              <Plate
                title="Recently completed"
                count={data.recentlyCompleted.length}
                subtitle="Your submitted completion reports"
                flush
              >
                {data.recentlyCompleted.length === 0 ? (
                  <div className="qlist__empty">
                    <EmptyNote>No completed jobs yet.</EmptyNote>
                  </div>
                ) : (
                  <div className="done">
                    {data.recentlyCompleted.map((c) => (
                      <article className="done__card" key={c.serviceRequestId}>
                        <div className="done__top">
                          <div>
                            <h3 className="done__title">{c.spareName}</h3>
                            <p className="done__meta">
                              {c.vesselName} · <span className="mono">{c.requestNumber}</span>
                            </p>
                          </div>
                          <span className="done__when">
                            {c.reportedAt ? `Reported ${relativeTime(c.reportedAt)}` : formatDate(c.serviceDate)}
                          </span>
                        </div>
                        {c.workPerformed && (
                          <p className="done__text">
                            <b>Work performed</b>
                            {c.workPerformed}
                          </p>
                        )}
                        {c.outcome && (
                          <p className="done__text">
                            <b>Outcome</b>
                            {c.outcome}
                          </p>
                        )}
                      </article>
                    ))}
                  </div>
                )}
              </Plate>
            </div>

            {selected ? (
              <JobSheet job={selected} />
            ) : (
              <Plate title="Job sheet">
                <EmptyNote>
                  No job selected. The Service Coordinator assigns jobs once the Ship Manager accepts the invoice.
                </EmptyNote>
              </Plate>
            )}
          </div>

        </>
      )}
    </div>
  );
}

const EMPTY: Record<Group, string> = {
  today: 'Nothing scheduled today. Assigned jobs appear here once the Coordinator dispatches them.',
  inProgress: 'No job in progress.',
  awaiting: 'No reports waiting for the Coordinator.',
  upcoming: 'No upcoming jobs.',
};

/** The engineer's day in five counts — their own work only. */
function DayStrip({ data }: { data: ServiceEngineerDashboard }) {
  const k = (key: string) => data.kpis.find((x) => x.key === key)?.value ?? 0;
  const items = [
    { label: 'Today', value: k('today'), icon: 'bell' },
    { label: 'In progress', value: k('inProgress'), icon: 'wrench' },
    { label: 'Awaiting review', value: k('awaitingReview'), icon: 'history' },
    { label: 'Upcoming', value: k('upcoming'), icon: 'report' },
    { label: 'Completed', value: k('completed'), icon: 'check' },
  ];

  return (
    <div className="daystrip">
      {items.map((i) => (
        <div className="daystrip__item" key={i.label}>
          <span className="tile__icon" aria-hidden="true">
            <Icon name={i.icon} size={16} />
          </span>
          <b>{i.value}</b>
          <span>{i.label}</span>
        </div>
      ))}
    </div>
  );
}

/** Everything needed to attend the job, and nothing that is not. */
function JobSheet({ job }: { job: EngineerJob }) {
  return (
    <Plate
      title={job.spareName}
      subtitle={`${job.vesselName} · ${job.requestNumber}`}
      action={<Pill tone="accent">{job.statusLabel}</Pill>}
    >
      <div className="sheet">
        {/* Authorisation status only. The engineer is told the job is cleared
            to proceed, never its cost (SoW §12). */}
        <div className={`notice ${job.invoiceAccepted ? 'notice--ok' : 'notice--wait'}`}>
          <Icon name={job.invoiceAccepted ? 'check' : 'invoice'} size={20} />
          <div>
            <p className="notice__title">
              {job.invoiceAccepted ? 'Authorised to attend' : 'Not yet authorised for attendance'}
            </p>
            <p className="notice__body">
              {job.invoiceAccepted
                ? 'The Ship Manager has accepted the invoice for this job.'
                : 'Attendance is authorised once the Ship Manager accepts the invoice.'}
            </p>
          </div>
        </div>

        <section className="sheet__section">
          <h3 className="sheet__h">Reported problem</h3>
          <p className="sheet__problem">{job.title}</p>
          {job.problemDescription && <p className="sheet__desc">{job.problemDescription}</p>}
        </section>

        <section className="sheet__section">
          <h3 className="sheet__h">Equipment</h3>
          <dl className="facts">
            <FactRow label="Spare" value={job.spareName} />
            <FactRow label="VMP reference" value={job.sparePath} mono />
            <FactRow label="Category" value={categoryLabel(job.categoryCode)} />
            <FactRow label="Make" value={job.make ?? '—'} />
            <FactRow label="Model" value={job.model ?? '—'} mono />
            <FactRow label="Serial number" value={job.serialNumber ?? '—'} mono />
          </dl>
        </section>

        <section className="sheet__section">
          <h3 className="sheet__h">Vessel and assignment</h3>
          <dl className="facts">
            <FactRow label="Vessel" value={job.vesselName} />
            <FactRow label="IMO" value={job.imoNumber ?? '—'} mono />
            <FactRow label="Priority" value={<PriorityChip value={job.priority} />} />
            <FactRow label="Assigned" value={formatDate(job.assignedAt)} />
            {job.scheduledFor && <FactRow label="Scheduled for" value={formatDate(job.scheduledFor)} />}
            {job.ageDays !== null && <FactRow label="Open for" value={`${job.ageDays} days`} />}
          </dl>
        </section>

        <section className="sheet__report">
          <div>
            <h3 className="sheet__h">Completion report</h3>
            <p className="sheet__desc">
              Start the work, then file what you did, the parts used and the outcome. Your report goes to the Service
              Coordinator.
            </p>
          </div>
          <Link to={`/requests/${job.serviceRequestId}`} className="cbtn cbtn--primary cbtn--lg">
            Open job to update it
          </Link>
        </section>
      </div>
    </Plate>
  );
}

function FactRow({ label, value, mono = false }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="facts__row">
      <dt>{label}</dt>
      <dd className={mono ? 'mono' : undefined}>{value}</dd>
    </div>
  );
}
