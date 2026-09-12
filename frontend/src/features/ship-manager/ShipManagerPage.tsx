import { fetchShipManager } from '@/api/dashboards';
import type { DueItem, InvoiceSummary, RequestSummary, ShipManagerVesselRow } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, formatMoney, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, PageHeader, Panel } from '@/design-system/Panel';
import { DataTable, type Column } from '@/design-system/DataTable';
import { StackedBar } from '@/design-system/Charts';
import { ErrorState, KpiSkeleton, LoadingState, EmptyState } from '@/design-system/States';
import { DueStatusBadge, Pill, PriorityChip, VesselStatusChip } from '@/design-system/StatusBadge';
import '../shared/dashboard.css';
import './shipmanager.css';

/**
 * Ship Manager — assigned vessels (SoW §8.2).
 *
 * <p>Action-first. This role holds two queues that block other people: an
 * unapproved request stalls the Coordinator, and an unaccepted invoice stalls
 * engineer dispatch entirely. Both lead the page, and the invoice queue states
 * the consequence rather than leaving the user to infer it.
 *
 * <p>The question the layout answers is "what needs my decision?" — everything
 * below the fold is context for that.
 */
export function ShipManagerPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'ship-manager',
    fetchShipManager,
  );

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const pendingTotal =
    (data?.requestsAwaitingApproval.total ?? 0) + (data?.invoicesAwaitingAcceptance.total ?? 0);

  return (
    <div className="page">
      <PageHeader
        eyebrow="Vessel management"
        title="My vessels"
        meta={
          data && (
            <>
              <span>
                {data.meta.vesselsInScope} assigned vessels
                {data.meta.organizationName ? ` · ${data.meta.organizationName}` : ''}
              </span>
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

      {/* The decision banner. Present only when something is actually waiting —
          a permanent "0 items need you" strip trains people to ignore it. */}
      {!isLoading && data && pendingTotal > 0 && (
        <div className="sm-alert" role="status">
          <span className="sm-alert__count mono">{pendingTotal}</span>
          <div>
            <p className="sm-alert__title">
              {pendingTotal === 1 ? 'One item needs' : `${pendingTotal} items need`} your decision
            </p>
            <p className="sm-alert__body">
              {data.requestsAwaitingApproval.total > 0 && (
                <>
                  {data.requestsAwaitingApproval.total} service{' '}
                  {data.requestsAwaitingApproval.total === 1 ? 'request' : 'requests'} to review
                </>
              )}
              {data.requestsAwaitingApproval.total > 0 &&
                data.invoicesAwaitingAcceptance.total > 0 && ' · '}
              {data.invoicesAwaitingAcceptance.total > 0 && (
                <>
                  {data.invoicesAwaitingAcceptance.total}{' '}
                  {data.invoicesAwaitingAcceptance.total === 1 ? 'invoice' : 'invoices'} to accept —
                  no engineer is dispatched until you do
                </>
              )}
            </p>
          </div>
        </div>
      )}

      {isLoading || !data ? <KpiSkeleton count={7} /> : <KpiRow items={data.kpis} />}

      <div className="grid-halves">
        <Panel
          title={data?.requestsAwaitingApproval.label ?? 'Awaiting operational review'}
          count={data?.requestsAwaitingApproval.total}
          padded={false}
          tone={data && data.requestsAwaitingApproval.total > 0 ? 'attention' : 'default'}
          action={
            data && data.requestsAwaitingApproval.total > 0 ? (
              <span className="queue-action-hint">
                {data.requestsAwaitingApproval.actionLabel}
              </span>
            ) : undefined
          }
        >
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : data.requestsAwaitingApproval.items.length === 0 ? (
            <EmptyState
              title="Nothing awaiting your approval"
              body="Service requests raised on your vessels will appear here for operational review."
            />
          ) : (
            <ul className="queue">
              {data.requestsAwaitingApproval.items.map((r) => (
                <RequestQueueRow key={r.id} request={r} />
              ))}
            </ul>
          )}
        </Panel>

        <Panel
          title={data?.invoicesAwaitingAcceptance.label ?? 'Invoices awaiting acceptance'}
          count={data?.invoicesAwaitingAcceptance.total}
          padded={false}
          tone={data && data.invoicesAwaitingAcceptance.total > 0 ? 'attention' : 'default'}
          action={
            data && data.invoicesAwaitingAcceptance.total > 0 ? (
              <span className="queue-action-hint">
                {data.invoicesAwaitingAcceptance.actionLabel}
              </span>
            ) : undefined
          }
        >
          {isLoading || !data ? (
            <LoadingState rows={3} />
          ) : (
            <>
              {/* The gate, stated where it is being held up. */}
              {data.invoicesAwaitingAcceptance.blocked &&
                data.invoicesAwaitingAcceptance.total > 0 && (
                  <p className="queue-blocked">
                    <strong>Gate:</strong> {data.invoicesAwaitingAcceptance.blocked}
                  </p>
                )}
              {data.invoicesAwaitingAcceptance.items.length === 0 ? (
                <EmptyState
                  title="No invoices awaiting acceptance"
                  body="When the Service Coordinator raises an invoice for one of your vessels, it appears here."
                />
              ) : (
                <ul className="queue">
                  {data.invoicesAwaitingAcceptance.items.map((i) => (
                    <InvoiceQueueRow key={i.id} invoice={i} />
                  ))}
                </ul>
              )}
            </>
          )}
        </Panel>
      </div>

      <div className="grid-main-rail">
        <Panel
          title="Vessels"
          count={data?.vessels.length}
          padded={false}
        >
          {isLoading || !data ? (
            <LoadingState rows={4} />
          ) : (
            <DataTable<ShipManagerVesselRow>
              columns={vesselColumns}
              rows={data.vessels}
              rowKey={(v) => v.vesselId}
              emptyTitle="No vessels allocated"
              emptyBody="Your Technical Head allocates vessels to you."
            />
          )}
        </Panel>

        <div className="stack">
          <Panel title="Request pipeline">
            {isLoading || !data ? (
              <LoadingState rows={4} />
            ) : (
              <StackedBar data={data.requestsByStage} />
            )}
          </Panel>

          <Panel title="Recent activity" padded={false}>
            {isLoading || !data ? (
              <LoadingState rows={4} />
            ) : data.recentActivity.length === 0 ? (
              <EmptyState title="No recent activity" icon="inbox" />
            ) : (
              <ol className="feed feed--compact">
                {data.recentActivity.slice(0, 10).map((a, i) => (
                  <li key={`${a.serviceRequestId}-${i}`} className="feed__item">
                    <span className="feed__rail feed__rail--accent" aria-hidden="true" />
                    <div className="feed__body">
                      <p className="feed__line">
                        <span className="feed__action">{a.actionLabel}</span>
                      </p>
                      <p className="feed__meta">
                        {a.vesselName}
                        <span className="feed__sep">·</span>
                        <span className="mono">{a.requestNumber}</span>
                      </p>
                    </div>
                    <time className="feed__time">{relativeTime(a.occurredAt)}</time>
                  </li>
                ))}
              </ol>
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
              pageSize={6}
              emptyTitle="Nothing overdue"
              emptyBody="Every tracked spare on your vessels is within its interval."
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
              pageSize={6}
              emptyTitle="Nothing due soon"
            />
          )}
        </Panel>
      </div>

      <Panel
        title="Requests in progress"
        subtitle="Completion reaches you through the Service Coordinator, not the engineer"
        count={data?.inFlight.length}
        padded={false}
      >
        {isLoading || !data ? (
          <LoadingState rows={4} />
        ) : (
          <DataTable<RequestSummary>
            columns={inFlightColumns}
            rows={data.inFlight}
            rowKey={(r) => r.id}
            pageSize={10}
            emptyTitle="Nothing in progress"
            emptyBody="Approved requests being worked by the Service Coordinator appear here."
          />
        )}
      </Panel>
    </div>
  );
}

function RequestQueueRow({ request }: { request: RequestSummary }) {
  return (
    <li className="queue__item">
      <div className="queue__head">
        <span className="queue__title">{request.title}</span>
        <span className="queue__ref mono">{request.requestNumber}</span>
      </div>
      <p className="queue__spare">
        {request.spareName} <span className="mono">({request.sparePath})</span> ·{' '}
        {request.vesselName}
      </p>
      <div className="queue__meta">
        <PriorityChip value={request.priority} />
        <span>Raised by {request.raisedByName}</span>
        {request.ageDays !== null && (
          <span className={request.ageDays > 3 ? 'queue__age queue__age--stale' : 'queue__age'}>
            {request.ageDays}d waiting
          </span>
        )}
      </div>
    </li>
  );
}

function InvoiceQueueRow({ invoice }: { invoice: InvoiceSummary }) {
  return (
    <li className="queue__item">
      <div className="queue__head">
        <span className="queue__title">{formatMoney(invoice.amount, invoice.currency)}</span>
        <span className="queue__ref mono">{invoice.invoiceNumber}</span>
      </div>
      <p className="queue__spare">{invoice.description}</p>
      <div className="queue__meta">
        <Pill tone="approaching" size="sm">
          {invoice.statusLabel}
        </Pill>
        <span>{invoice.vesselName}</span>
        <span className="mono">{invoice.requestNumber}</span>
      </div>
    </li>
  );
}

const dueColumns: Column<DueItem>[] = [
  {
    key: 'spare',
    header: 'Spare',
    sortValue: (d) => d.spareName,
    render: (d) => (
      <>
        <span className="cell-strong">{d.spareName}</span>
        <span className="cell-sub mono">
          {d.sparePath} · {d.vesselName}
        </span>
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

const inFlightColumns: Column<RequestSummary>[] = [
  {
    key: 'request',
    header: 'Request',
    sortValue: (r) => r.requestNumber,
    render: (r) => (
      <>
        <span className="cell-strong">{r.title}</span>
        <span className="cell-sub mono">
          {r.requestNumber} · {r.vesselName}
        </span>
      </>
    ),
  },
  {
    key: 'priority',
    header: 'Priority',
    width: '110px',
    sortValue: (r) => r.priority,
    render: (r) => <PriorityChip value={r.priority} />,
  },
  {
    key: 'status',
    header: 'Stage',
    width: '180px',
    sortValue: (r) => r.status,
    render: (r) => <Pill tone="accent" size="sm">{r.statusLabel}</Pill>,
  },
  {
    key: 'engineer',
    header: 'Engineer',
    width: '160px',
    render: (r) => r.assignedEngineerName ?? <span className="cell-muted">Not yet assigned</span>,
  },
  {
    key: 'age',
    header: 'Age',
    align: 'right',
    width: '80px',
    sortValue: (r) => r.ageDays ?? 0,
    render: (r) => <span className="mono">{r.ageDays !== null ? `${r.ageDays}d` : '—'}</span>,
  },
];

const vesselColumns: Column<ShipManagerVesselRow>[] = [
  {
    key: 'vessel',
    header: 'Vessel',
    sortValue: (v) => v.vesselName,
    render: (v) => (
      <>
        <span className="cell-strong">{v.vesselName}</span>
        <span className="cell-sub mono">IMO {v.imoNumber}</span>
      </>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    width: '140px',
    render: (v) => <VesselStatusChip value={v.status} />,
  },
  {
    key: 'spares',
    header: 'Spares',
    align: 'right',
    width: '90px',
    sortValue: (v) => v.spareCount,
    render: (v) => <span className="mono">{v.spareCount}</span>,
  },
  {
    key: 'overdue',
    header: 'Overdue',
    align: 'right',
    width: '100px',
    sortValue: (v) => v.overdue,
    render: (v) => (
      <span className={v.overdue > 0 ? 'count count--overdue mono' : 'count count--zero mono'}>
        {v.overdue}
      </span>
    ),
  },
  {
    key: 'requests',
    header: 'Open',
    align: 'right',
    width: '90px',
    sortValue: (v) => v.openRequests,
    render: (v) => (
      <span className={v.openRequests > 0 ? 'count count--accent mono' : 'count count--zero mono'}>
        {v.openRequests}
      </span>
    ),
  },
];
