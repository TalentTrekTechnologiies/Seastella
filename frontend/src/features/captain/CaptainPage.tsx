import { fetchCaptain } from '@/api/dashboards';
import type { CaptainDashboard, DueItem } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, formatHours, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, DefinitionList, PageHeader, Panel } from '@/design-system/Panel';
import { DataTable, type Column } from '@/design-system/DataTable';
import { ErrorState, KpiSkeleton, LoadingState, EmptyState } from '@/design-system/States';
import { DueStatusBadge, Pill, PriorityChip, VesselStatusChip } from '@/design-system/StatusBadge';
import { Icon } from '@/design-system/Icon';
import '../shared/dashboard.css';
import './captain.css';

type MyRequest = CaptainDashboard['myRequests'][number];

/**
 * Captain — one vessel (SoW §8.3).
 *
 * <p>The simplest screen in the product, by instruction: §12 requires the
 * vessel-side workflow be "kept deliberately simple to minimize onboarding
 * effort for crew". Single column, larger targets, one obvious primary action.
 *
 * <p><b>No monetary value appears anywhere.</b> Not filtered out — the API
 * never sends one (§12), so there is nothing here to hide. The Captain sees
 * that an invoice was accepted, never what it cost.
 */
export function CaptainPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'captain',
    fetchCaptain,
  );

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const needsResponse = data?.myRequests.filter((r) => r.awaitingMyResponse) ?? [];

  return (
    <div className="page page--captain">
      <PageHeader
        eyebrow="Vessel operations"
        title={data?.vessel?.name ?? 'My vessel'}
        meta={
          data?.vessel && (
            <>
              <span className="mono">IMO {data.vessel.imoNumber}</span>
              {data.vessel.flag && <span>{data.vessel.flag}</span>}
              <VesselStatusChip value={data.vessel.status} />
              <span>Updated {relativeTime(data.meta.generatedAt)}</span>
            </>
          )
        }
        actions={
          <>
            <Button onClick={() => refetch()} disabled={isFetching}>
              {isFetching ? 'Refreshing…' : 'Refresh'}
            </Button>
            {/* The primary operational action, always reachable. Disabled with
                a reason until the request flow ships — never a dead button. */}
            <Button variant="primary" size="lg" disabled title="Available in the next stage">
              <Icon name="wrench" size={15} />
              Raise service request
            </Button>
          </>
        }
      />

      {/* Only shown when the Ship Manager has actually asked for something. */}
      {!isLoading && needsResponse.length > 0 && (
        <div className="cap-alert" role="status">
          <Icon name="chat" size={18} />
          <div>
            <p className="cap-alert__title">
              {needsResponse.length === 1
                ? 'One request needs more information'
                : `${needsResponse.length} requests need more information`}
            </p>
            <p className="cap-alert__body">
              Your Ship Manager has asked for clarification before approving.
            </p>
          </div>
        </div>
      )}

      {isLoading || !data ? <KpiSkeleton count={5} /> : <KpiRow items={data.kpis} />}

      <div className="grid-main-rail">
        <Panel
          title="My service requests"
          subtitle="Raised from this vessel"
          count={data?.myRequests.length}
          padded={false}
        >
          {isLoading || !data ? (
            <LoadingState rows={5} />
          ) : data.myRequests.length === 0 ? (
            <EmptyState
              title="No service requests"
              body="Raise one from a spare when equipment needs attention."
            />
          ) : (
            <ul className="cap-requests">
              {data.myRequests.map((r) => (
                <li key={r.id} className={`cap-request${r.awaitingMyResponse ? ' cap-request--attention' : ''}`}>
                  <div className="cap-request__top">
                    <span className="cap-request__title">{r.title}</span>
                    <Pill tone={toneForStatus(r.status)} size="sm">
                      {r.statusLabel}
                    </Pill>
                  </div>
                  <p className="cap-request__spare">
                    {r.spareName} <span className="mono">({r.sparePath})</span>
                  </p>

                  {/* Progress through the workflow, without restating its rules.
                      The server decides the stage; this only draws it. */}
                  <ol className="cap-steps" aria-label="Request progress">
                    {STEPS.map((s) => {
                      const state = stepState(s.key, r);
                      return (
                        <li key={s.key} className={`cap-step cap-step--${state}`}>
                          <span className="cap-step__dot" aria-hidden="true" />
                          <span className="cap-step__label">{s.label}</span>
                        </li>
                      );
                    })}
                  </ol>

                  <div className="cap-request__meta">
                    <PriorityChip value={r.priority} />
                    <span className="mono">{r.requestNumber}</span>
                    {r.engineerAssigned && r.engineerName && (
                      <span>Engineer: {r.engineerName}</span>
                    )}
                    {r.ageDays !== null && <span>{r.ageDays}d old</span>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <div className="stack">
          <Panel title="This vessel">
            {isLoading || !data?.vessel ? (
              <LoadingState rows={4} />
            ) : (
              <DefinitionList
                items={[
                  { label: 'IMO', value: data.vessel.imoNumber, mono: true },
                  { label: 'Call sign', value: data.vessel.callSign ?? '—', mono: true },
                  { label: 'Flag', value: data.vessel.flag ?? '—' },
                  { label: 'Type', value: data.vessel.vesselType ?? '—' },
                  { label: 'Spares', value: data.vessel.spareCount, mono: true },
                  { label: 'Critical spares', value: data.vessel.criticalSpareCount, mono: true },
                ]}
              />
            )}
          </Panel>

          <Panel title="Running hours" subtitle="Spares that accrue hours" padded={false}>
            {isLoading || !data ? (
              <LoadingState rows={3} />
            ) : data.runningHours.length === 0 ? (
              <EmptyState title="No hour-tracked spares" icon="inbox" />
            ) : (
              <ul className="cap-hours">
                {data.runningHours.map((h) => (
                  <li key={h.spareId}>
                    <span className="cap-hours__name">{h.spareName}</span>
                    <span className="cap-hours__value mono">{formatHours(h.currentHours)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Low stock" subtitle="Replacement parts below minimum" padded={false}>
            {isLoading || !data ? (
              <LoadingState rows={3} />
            ) : data.partShortages.length === 0 ? (
              <EmptyState title="Stock levels are fine" body="No part is below its minimum." />
            ) : (
              <ul className="cap-parts">
                {data.partShortages.map((p) => (
                  <li key={p.id}>
                    <div>
                      <span className="cap-parts__name">{p.name}</span>
                      {p.partNumber && <span className="cell-sub mono">{p.partNumber}</span>}
                    </div>
                    <span className="cap-parts__qty mono">
                      {p.quantityOnHand} / {p.minimumQuantity}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>

      <div className="grid-halves">
        <Panel title="Overdue services" count={data?.overdue.length} padded={false}>
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : (
            <DataTable<DueItem>
              columns={dueColumns}
              rows={data.overdue}
              rowKey={(d) => d.spareId}
              dense
              pageSize={8}
              emptyTitle="Nothing overdue"
              emptyBody="Every tracked spare aboard is within its service interval."
            />
          )}
        </Panel>

        <Panel title="Due within 15 days" count={data?.dueSoon.length} padded={false}>
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : (
            <DataTable<DueItem>
              columns={dueColumns}
              rows={data.dueSoon}
              rowKey={(d) => d.spareId}
              dense
              pageSize={8}
              emptyTitle="Nothing due soon"
            />
          )}
        </Panel>
      </div>
    </div>
  );
}

/** The Captain-visible stages. Deliberately fewer than the full machine. */
const STEPS = [
  { key: 'raised', label: 'Raised' },
  { key: 'approved', label: 'Approved' },
  { key: 'authorised', label: 'Authorised' },
  { key: 'engineer', label: 'Engineer' },
  { key: 'done', label: 'Done' },
] as const;

/**
 * Maps a server-decided status onto the Captain's simplified progress bar.
 *
 * <p>This reads the workflow position the backend already set; it does not
 * decide transitions or re-implement the state machine.
 */
function stepState(step: string, r: MyRequest): 'done' | 'current' | 'todo' | 'stopped' {
  const stopped = r.status === 'REJECTED';
  const approved =
    r.invoiceAccepted ||
    r.engineerAssigned ||
    ['OPERATIONALLY_APPROVED', 'CLOSED_NO_COST', 'INVOICE_RAISED', 'INVOICE_QUERIED',
      'INVOICE_REJECTED', 'COMPLETED'].includes(r.status);
  const done = r.status === 'COMPLETED' || r.status === 'CLOSED_NO_COST';

  switch (step) {
    case 'raised':
      return 'done';
    case 'approved':
      if (stopped) return 'stopped';
      return approved ? 'done' : 'current';
    case 'authorised':
      if (!approved) return 'todo';
      return r.invoiceAccepted ? 'done' : 'current';
    case 'engineer':
      if (!r.invoiceAccepted) return 'todo';
      return r.engineerAssigned ? 'done' : 'current';
    case 'done':
      return done ? 'done' : 'todo';
    default:
      return 'todo';
  }
}

function toneForStatus(status: string) {
  if (status === 'REJECTED') return 'overdue' as const;
  if (status === 'COMPLETED' || status === 'CLOSED_NO_COST') return 'normal' as const;
  if (status === 'CLARIFICATION_REQUESTED') return 'approaching' as const;
  return 'accent' as const;
}

const dueColumns: Column<DueItem>[] = [
  {
    key: 'spare',
    header: 'Spare',
    sortValue: (d) => d.spareName,
    render: (d) => (
      <>
        <span className="cell-strong">{d.spareName}</span>
        <span className="cell-sub mono">{d.sparePath}</span>
      </>
    ),
  },
  {
    key: 'due',
    header: 'Next due',
    width: '115px',
    sortValue: (d) => d.nextDueDate ?? '',
    render: (d) => <span className="mono">{formatDate(d.nextDueDate)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    width: '150px',
    sortValue: (d) => d.daysRemaining ?? 9999,
    render: (d) => <DueStatusBadge status={d.status} daysRemaining={d.daysRemaining} size="sm" />,
  },
];
