import { useState } from 'react';
import { fetchServiceCoordinator } from '@/api/dashboards';
import type { ActionQueue, InvoiceSummary, RequestSummary, ServiceCoordinatorDashboard } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDuration } from '@/lib/format';
import { ActivityList, ConsoleHeader, EmptyNote, Plate, RadialGauge } from '@/design-system/Console';
import { InvoiceCard, RefreshButton, RequestCard } from '@/design-system/ConsoleParts';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Icon } from '@/design-system/Icon';

/**
 * SERVICE CONTROL ROOM — Service Coordinator (SoW §8.4).
 *
 * <p>Scope: the organizations this Coordinator services (OI-16, resolved
 * server-side). The Coordinator runs the flow rather than reading metrics —
 * closing without cost, raising invoices, assigning engineers behind the
 * invoice gate, and relaying completion (RBAC matrix) — so the page is the
 * workflow itself: REQUEST → TROUBLESHOOT → APPROVE → INVOICE → ASSIGN →
 * SERVICE → REPORT. Each stage is a live queue; choosing one opens it.
 *
 * <p>Two stages carry the rules that matter. <b>Assign</b> sits behind the
 * invoice gate: it is the only stage from which an engineer may be dispatched.
 * <b>Report</b> exists because the Coordinator is the Ship Manager's only
 * channel for completion (§6.3).
 */

type StageKey =
  | 'incoming'
  | 'liveAgentActive'
  | 'awaitingTriage'
  | 'invoicesPendingAcceptance'
  | 'acceptedReadyToAssign'
  | 'assignedInProgress'
  | 'completionReportsPendingRelay';

interface Stage {
  key: StageKey;
  step: string;
  kind: 'request' | 'invoice';
  /** The invoice gate stands in front of this stage. */
  gated?: boolean;
}

const STAGES: Stage[] = [
  { key: 'incoming', step: 'Request', kind: 'request' },
  { key: 'liveAgentActive', step: 'Troubleshoot', kind: 'request' },
  { key: 'awaitingTriage', step: 'Approve', kind: 'request' },
  { key: 'invoicesPendingAcceptance', step: 'Invoice', kind: 'invoice' },
  { key: 'acceptedReadyToAssign', step: 'Assign', kind: 'invoice', gated: true },
  { key: 'assignedInProgress', step: 'Service', kind: 'request' },
  { key: 'completionReportsPendingRelay', step: 'Report', kind: 'request' },
];

export function CoordinatorPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'service-coordinator',
    fetchServiceCoordinator,
  );
  const [chosen, setChosen] = useState<StageKey | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  // Open on the first stage that has work the Coordinator can act on.
  const firstActionable =
    data && STAGES.find((s) => data[s.key].total > 0 && !data[s.key].blocked)?.key;
  const selectedKey: StageKey = chosen ?? firstActionable ?? 'incoming';
  const selected = STAGES.find((s) => s.key === selectedKey) as Stage;

  const orgs = data?.meta.organizationsInScope ?? 0;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: 'SeaStella service operations' },
          {
            label: orgs === 1 ? (data?.meta.organizationNames[0] ?? '—') : `${orgs} organizations`,
            strong: true,
          },
          { label: `${data?.meta.vesselsInScope ?? 0} vessels in scope` },
        ]}
        title="Service control room"
        subtitle={
          orgs > 1
            ? `Every service request moving through the workflow for ${data?.meta.organizationNames.join(' and ')}.`
            : 'Every service request moving through the workflow, stage by stage.'
        }
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading operations" />
      ) : (
        <>
          <nav className="flow" aria-label="Service request workflow">
            {STAGES.map((s, i) => {
              const q = data[s.key];
              const state = q.blocked ? 'blocked' : q.total > 0 ? 'work' : 'clear';
              return (
                <div className="flow__cell" key={s.key}>
                  {i > 0 && (
                    <span className={`flow__link${s.gated ? ' flow__link--gate' : ''}`} aria-hidden="true">
                      {s.gated ? (
                        <>
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <rect x="4" y="10" width="16" height="10" rx="2" />
                            <path d="M8 10V7a4 4 0 018 0v3" />
                          </svg>
                          <span>Gate</span>
                        </>
                      ) : (
                        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                          <path d="M6 3l5 5-5 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                        </svg>
                      )}
                    </span>
                  )}
                  <button
                    type="button"
                    className={`flow__stage flow__stage--${state}${selectedKey === s.key ? ' flow__stage--on' : ''}`}
                    onClick={() => setChosen(s.key)}
                    aria-pressed={selectedKey === s.key}
                  >
                    <span className="flow__step">
                      {i + 1} · {s.step}
                    </span>
                    <span className="flow__count">{q.total}</span>
                    <span className="flow__label">{q.label}</span>
                    <span className="flow__state">
                      {state === 'blocked' ? 'Waiting on Ship Manager' : state === 'work' ? 'Needs action' : 'Clear'}
                    </span>
                  </button>
                </div>
              );
            })}
          </nav>

          <div className="row-main-side">
            <StageQueue stage={selected} queue={data[selected.key]} />

            <div className="stack-col">
              <Plate title="Troubleshooting effectiveness" subtitle="Resolved requests, by how they were closed">
                <div className="effect">
                  <RadialGauge
                    tone="signal"
                    value={data.resolutionSplit.resolvedWithoutCost}
                    of={data.resolutionSplit.resolvedWithoutCost + data.resolutionSplit.resolvedByEngineerVisit}
                    caption="Closed without a service visit"
                  />
                  <div className="effect__facts">
                    <Fact value={data.resolutionSplit.resolvedWithoutCost} label="Closed without cost" />
                    <Fact value={data.resolutionSplit.resolvedByEngineerVisit} label="Needed an engineer" />
                    <Fact value={data.resolutionSplit.stillOpen} label="Still open" />
                  </div>
                </div>
              </Plate>

              <Plate title="Turnaround" subtitle="Average time from raise to close">
                <div className="turn">
                  <Fact value={formatDuration(data.turnaround.averageHoursLast30Days)} label="Last 30 days" />
                  <Fact value={formatDuration(data.turnaround.averageHoursLast90Days)} label="Last 90 days" />
                  <Fact value={data.turnaround.closedLast30Days} label="Closed in 30 days" />
                </div>
              </Plate>
            </div>
          </div>

          <Plate
            title="Recent activity"
            count={data.recentActivity.length}
            subtitle="Latest events across every organization you service"
            flush
          >
            <ActivityList items={data.recentActivity} wide />
          </Plate>
        </>
      )}
    </div>
  );
}

function StageQueue({
  stage,
  queue,
}: {
  stage: Stage;
  queue: ServiceCoordinatorDashboard[StageKey];
}) {
  const blocked = Boolean(queue.blocked);

  return (
    <Plate
      title={queue.label}
      count={queue.total}
      subtitle={`Step ${STAGES.indexOf(stage) + 1} · ${stage.step}`}
      tone={!blocked && queue.total > 0 ? 'attention' : undefined}
      flush
    >
      <p className={`gate${blocked ? '' : ' gate--action'}`}>
        <Icon name={blocked ? 'history' : 'check'} size={16} />
        {blocked ? queue.blocked : queue.actionLabel}
      </p>

      {queue.items.length === 0 ? (
        <div className="qlist__empty">
          <EmptyNote>Nothing at this stage right now.</EmptyNote>
        </div>
      ) : (
        <div className="qlist">
          {stage.kind === 'request'
            ? (queue as ActionQueue<RequestSummary>).items.map((r) => (
                <RequestCard key={r.id} request={r} showStage />
              ))
            : (queue as ActionQueue<InvoiceSummary>).items.map((inv) => (
                <InvoiceCard key={inv.id} invoice={inv} tone={stage.gated ? 'normal' : 'approaching'} />
              ))}
        </div>
      )}
    </Plate>
  );
}

function Fact({ value, label }: { value: number | string; label: string }) {
  return (
    <div className="fact">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}
