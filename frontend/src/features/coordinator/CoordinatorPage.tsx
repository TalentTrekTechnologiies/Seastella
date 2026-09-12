import { fetchServiceCoordinator } from '@/api/dashboards';
import type { ActionQueue, InvoiceSummary, RequestSummary } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDuration, formatMoney, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, PageHeader, Panel } from '@/design-system/Panel';
import { SplitMeter } from '@/design-system/Charts';
import { ErrorState, KpiSkeleton, LoadingState, EmptyState } from '@/design-system/States';
import { Pill, PriorityChip } from '@/design-system/StatusBadge';
import '../shared/dashboard.css';
import './coordinator.css';

/**
 * Service Coordinator — the operations control room (SoW §8.4).
 *
 * <p>Laid out as the §6 workflow itself, left to right, because this role
 * manages flow rather than reads metrics. Each stage is a queue with the action
 * it expects, and stages waiting on somebody else say so instead of looking
 * broken.
 *
 * <p>Two stages carry the rules that matter:
 * <b>Ready to assign</b> is the only column from which an engineer may be
 * assigned — it is the invoice gate expressed as work. <b>Reports to relay</b>
 * exists because the Coordinator is the Ship Manager's only channel for
 * completion (§6.3): an unrelayed report is a Ship Manager who has not been
 * told the job is done.
 */
export function CoordinatorPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'service-coordinator',
    fetchServiceCoordinator,
  );

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  return (
    <div className="page">
      <PageHeader
        eyebrow="Seastella service operations"
        title="Operations board"
        meta={
          data && (
            <>
              {/* OI-16: a Coordinator may service several client organizations.
                  Stated plainly, and resolved entirely server-side. */}
              <span>
                {data.meta.organizationsInScope === 1
                  ? data.meta.organizationNames[0]
                  : `${data.meta.organizationsInScope} organizations`}
                {data.meta.organizationsInScope > 1 && (
                  <span className="co-orgs"> — {data.meta.organizationNames.join(', ')}</span>
                )}
              </span>
              <span>{data.meta.vesselsInScope} vessels in scope</span>
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

      {isLoading || !data ? <KpiSkeleton count={8} /> : <KpiRow items={data.kpis} />}

      {/* The pipeline, in workflow order. Blocked stages are visibly blocked. */}
      <div className="pipeline" role="region" aria-label="Service request pipeline">
        {isLoading || !data ? (
          <div className="pipeline__loading">
            <LoadingState rows={3} />
          </div>
        ) : (
          <>
            <StageColumn stage="A" queue={data.incoming} kind="request" />
            <StageArrow />
            <StageColumn stage="A" queue={data.liveAgentActive} kind="request" tone="urgent" />
            <StageArrow />
            <StageColumn stage="B" queue={data.awaitingTriage} kind="request" />
            <StageArrow />
            <StageColumn stage="B" queue={data.invoicesPendingAcceptance} kind="invoice" />
            <StageArrow gate />
            <StageColumn stage="B" queue={data.acceptedReadyToAssign} kind="invoice" tone="ready" />
            <StageArrow />
            <StageColumn stage="C" queue={data.assignedInProgress} kind="request" />
            <StageArrow />
            <StageColumn
              stage="C"
              queue={data.completionReportsPendingRelay}
              kind="request"
              tone="relay"
            />
          </>
        )}
      </div>

      <div className="grid-halves">
        <Panel
          title="Troubleshooting effectiveness"
          subtitle="Requests closed without a service visit"
        >
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : (
            <SplitMeter
              leftLabel="closed without cost"
              leftValue={data.resolutionSplit.resolvedWithoutCost}
              rightLabel="engineer visit"
              rightValue={data.resolutionSplit.resolvedByEngineerVisit}
            />
          )}
        </Panel>

        <Panel title="Turnaround" subtitle="Raise to close, over recently closed requests">
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : (
            <div className="co-turnaround">
              <div>
                <span className="co-turnaround__label">Last 30 days</span>
                <span className="co-turnaround__value mono">
                  {formatDuration(data.turnaround.averageHoursLast30Days)}
                </span>
              </div>
              <div>
                <span className="co-turnaround__label">Last 90 days</span>
                <span className="co-turnaround__value mono">
                  {formatDuration(data.turnaround.averageHoursLast90Days)}
                </span>
              </div>
              <div>
                <span className="co-turnaround__label">Closed</span>
                <span className="co-turnaround__value mono">
                  {data.turnaround.closedLast30Days}
                </span>
              </div>
            </div>
          )}
        </Panel>
      </div>

      <Panel title="Recent activity" count={data?.recentActivity.length} padded={false}>
        {isLoading || !data ? (
          <LoadingState rows={6} />
        ) : data.recentActivity.length === 0 ? (
          <EmptyState title="No recent activity" icon="inbox" />
        ) : (
          <ol className="feed">
            {data.recentActivity.slice(0, 20).map((a, i) => (
              <li key={`${a.serviceRequestId}-${i}`} className="feed__item">
                <span className="feed__rail feed__rail--accent" aria-hidden="true" />
                <div className="feed__body">
                  <p className="feed__line">
                    <span className="feed__action">{a.actionLabel}</span>
                    <span className="feed__sep">·</span>
                    <span className="mono feed__ref">{a.requestNumber}</span>
                  </p>
                  <p className="feed__meta">
                    {a.vesselName}
                    {a.actorName && (
                      <>
                        <span className="feed__sep">·</span>
                        {a.actorName}
                      </>
                    )}
                  </p>
                </div>
                <time className="feed__time">{relativeTime(a.occurredAt)}</time>
              </li>
            ))}
          </ol>
        )}
      </Panel>
    </div>
  );
}

/** One stage of the pipeline. */
function StageColumn({
  stage,
  queue,
  kind,
  tone = 'default',
}: {
  stage: 'A' | 'B' | 'C';
  queue: ActionQueue<RequestSummary> | ActionQueue<InvoiceSummary>;
  kind: 'request' | 'invoice';
  tone?: 'default' | 'urgent' | 'ready' | 'relay';
}) {
  const blocked = Boolean(queue.blocked);

  return (
    <section className={`stage stage--${tone}${blocked ? ' stage--blocked' : ''}`}>
      <header className="stage__head">
        <span className="stage__phase">Phase {stage}</span>
        <h3 className="stage__title">{queue.label}</h3>
        <span className="stage__count mono">{queue.total}</span>
      </header>

      {blocked ? (
        <p className="stage__blocked">{queue.blocked}</p>
      ) : (
        <p className="stage__action">{queue.actionLabel}</p>
      )}

      <div className="stage__body">
        {queue.items.length === 0 ? (
          <p className="stage__empty">Nothing here</p>
        ) : (
          <ul className="stage__list">
            {queue.items.slice(0, 6).map((item) =>
              kind === 'request' ? (
                <li key={(item as RequestSummary).id} className="stage__card">
                  <span className="stage__card-title">{(item as RequestSummary).title}</span>
                  <span className="stage__card-meta">
                    <PriorityChip value={(item as RequestSummary).priority} />
                    <span className="mono">{(item as RequestSummary).vesselName}</span>
                  </span>
                  {(item as RequestSummary).ageDays !== null && (
                    <span className="stage__card-age">
                      {(item as RequestSummary).ageDays}d
                    </span>
                  )}
                </li>
              ) : (
                <li key={(item as InvoiceSummary).id} className="stage__card">
                  <span className="stage__card-title mono">
                    {formatMoney(
                      (item as InvoiceSummary).amount,
                      (item as InvoiceSummary).currency,
                    )}
                  </span>
                  <span className="stage__card-meta">
                    <Pill tone={tone === 'ready' ? 'normal' : 'approaching'} size="sm">
                      {(item as InvoiceSummary).statusLabel}
                    </Pill>
                    <span className="mono">{(item as InvoiceSummary).vesselName}</span>
                  </span>
                </li>
              ),
            )}
            {queue.items.length > 6 && (
              <li className="stage__more">+{queue.items.length - 6} more</li>
            )}
          </ul>
        )}
      </div>
    </section>
  );
}

/** The gate arrow is marked, because that transition is the one with a rule. */
function StageArrow({ gate = false }: { gate?: boolean }) {
  return (
    <div className={`stage-arrow${gate ? ' stage-arrow--gate' : ''}`} aria-hidden="true">
      {gate ? (
        <>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="4" y="10" width="16" height="10" rx="2" />
            <path d="M8 10V7a4 4 0 018 0v3" />
          </svg>
          <span className="stage-arrow__label">Gate</span>
        </>
      ) : (
        <span className="stage-arrow__chev">›</span>
      )}
    </div>
  );
}
