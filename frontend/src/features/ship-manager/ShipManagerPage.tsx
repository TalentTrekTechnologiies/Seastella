import { fetchShipManager } from '@/api/dashboards';
import type { ShipManagerDashboard } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { ActivityList, ConsoleHeader, EmptyNote, Plate, StageBars, StatTile } from '@/design-system/Console';
import { InvoiceCard, RefreshButton, RequestCard } from '@/design-system/ConsoleParts';
import { DueAttention } from '@/design-system/MaintenanceCharts';
import { VesselCard } from '@/design-system/VesselCard';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Icon } from '@/design-system/Icon';
import { RecentAlerts } from '@/features/alerts/RecentAlerts';

/**
 * VESSEL MANAGEMENT — Ship Manager / Superintendent (SoW §8.2).
 *
 * <p>Scope: only the vessels allocated to this Ship Manager. The role holds
 * the two decisions that block everyone else (RBAC matrix: operational
 * approve/reject, and accept/reject/query invoice), so the page opens on
 * "what needs my decision?" and states what each queue is holding up. Invoice
 * values are visible to this role.
 */
export function ShipManagerPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'ship-manager',
    fetchShipManager,
  );

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: 'Vessel management' },
          { label: data?.meta.organizationName ?? '—', strong: true },
          { label: `${data?.meta.vesselsInScope ?? 0} assigned vessels` },
        ]}
        title="Vessel management"
        subtitle="Decisions waiting on you, your vessels and the maintenance they carry."
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading your vessels" />
      ) : (
        <>
          <DecisionStrip data={data} />

          <div className="row-2">
            <Plate
              title="Service requests awaiting your approval"
              count={data.requestsAwaitingApproval.total}
              subtitle={data.requestsAwaitingApproval.actionLabel}
              tone={data.requestsAwaitingApproval.total > 0 ? 'attention' : undefined}
              flush
            >
              {data.requestsAwaitingApproval.items.length === 0 ? (
                <div className="qlist__empty">
                  <EmptyNote>Nothing awaiting your approval. Requests raised on your vessels appear here.</EmptyNote>
                </div>
              ) : (
                <div className="qlist">
                  {data.requestsAwaitingApproval.items.map((r) => (
                    <RequestCard key={r.id} request={r} />
                  ))}
                </div>
              )}
            </Plate>

            <Plate
              title="Invoices awaiting acceptance"
              count={data.invoicesAwaitingAcceptance.total}
              subtitle={data.invoicesAwaitingAcceptance.actionLabel}
              tone={data.invoicesAwaitingAcceptance.total > 0 ? 'attention' : undefined}
              flush
            >
              {data.invoicesAwaitingAcceptance.blocked && data.invoicesAwaitingAcceptance.total > 0 && (
                <p className="gate">
                  <Icon name="invoice" size={16} />
                  {data.invoicesAwaitingAcceptance.blocked}
                </p>
              )}
              {data.invoicesAwaitingAcceptance.items.length === 0 ? (
                <div className="qlist__empty">
                  <EmptyNote>
                    No invoices awaiting acceptance. When the Service Coordinator raises one for your vessels, it
                    appears here.
                  </EmptyNote>
                </div>
              ) : (
                <div className="qlist">
                  {data.invoicesAwaitingAcceptance.items.map((i) => (
                    <InvoiceCard key={i.id} invoice={i} />
                  ))}
                </div>
              )}
            </Plate>
          </div>

          <div className="tiles tiles--4">
            <StatTile
              icon="wrench"
              label="Open service requests"
              value={kpi(data, 'openRequests')}
              caption={`${data.inFlight.length} being worked by the Coordinator`}
            />
            <StatTile
              icon="bell"
              label="Overdue services"
              value={kpi(data, 'overdue')}
              tone={kpi(data, 'overdue') > 0 ? 'alert' : undefined}
              caption="Past their due date"
            />
            <StatTile
              icon="history"
              label="Due within 15 days"
              value={kpi(data, 'dueSoon')}
              tone={kpi(data, 'dueSoon') > 0 ? 'warn' : undefined}
              caption="Plan these next"
            />
            <StatTile
              icon="spare"
              label="Parts below minimum"
              value={kpi(data, 'partShortages')}
              tone={kpi(data, 'partShortages') > 0 ? 'warn' : undefined}
              caption="Replacement stock to reorder"
            />
          </div>

          <div className="row-2">
            <Plate title="My vessels" count={data.vessels.length} subtitle="Allocated to you by your Technical Head" flush>
              {data.vessels.length === 0 ? (
                <div className="qlist__empty">
                  <EmptyNote>No vessels are allocated to you yet. Your Technical Head allocates them.</EmptyNote>
                </div>
              ) : (
                <div className="vcards">
                  {data.vessels.map((v) => (
                    <VesselCard key={v.vesselId} vessel={v} />
                  ))}
                </div>
              )}
            </Plate>

            <Plate title="Request pipeline" subtitle="Every request on your vessels, by workflow stage">
              <StageBars rows={data.requestsByStage.slices} />
            </Plate>
          </div>

          <div className="row-2">
            <Plate
              title="Requests in progress"
              count={data.inFlight.length}
              subtitle="Completion reaches you through the Service Coordinator, not the engineer"
              flush
            >
              {data.inFlight.length === 0 ? (
                <div className="qlist__empty">
                  <EmptyNote>Nothing in progress. Approved requests being worked appear here.</EmptyNote>
                </div>
              ) : (
                <div className="qlist">
                  {data.inFlight.map((r) => (
                    <RequestCard key={r.id} request={r} showStage staleAfterDays={7} />
                  ))}
                </div>
              )}
            </Plate>

            <DueAttention
              overdue={data.overdue}
              dueSoon={data.dueSoon}
              totals={{ overdue: kpi(data, 'overdue'), dueSoon: kpi(data, 'dueSoon') }}
            />
          </div>

          <div className="row-2">
            <RecentAlerts subtitle="Approvals, invoices, completions and maintenance on your vessels" />
            <Plate title="Recent activity" count={data.recentActivity.length} subtitle="On your vessels" flush>
              <ActivityList items={data.recentActivity} />
            </Plate>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * The decisions waiting on this user, with what each is holding up. Calm when
 * nothing is waiting: a permanent alarm trains people to ignore it.
 */
function DecisionStrip({ data }: { data: ShipManagerDashboard }) {
  const approvals = data.requestsAwaitingApproval.total;
  const invoices = data.invoicesAwaitingAcceptance.total;
  const pending = approvals + invoices;

  return (
    <section className={`decision${pending > 0 ? ' decision--on' : ''}`} role="status">
      <div className="decision__lead">
        <span className="decision__count">{pending}</span>
        <div>
          <h2 className="decision__title">
            {pending === 0
              ? 'No decisions waiting on you'
              : pending === 1
                ? '1 decision is waiting on you'
                : `${pending} decisions are waiting on you`}
          </h2>
          <p className="decision__body">
            {pending === 0
              ? 'New service requests and invoices for your vessels will appear here.'
              : invoices > 0
                ? 'No Service Engineer is dispatched until you accept the invoice.'
                : 'The Service Coordinator can act on a request only after you approve it.'}
          </p>
        </div>
      </div>
      <div className="decision__items">
        <div className="decision__item">
          <b>{approvals}</b>
          <span>Service {approvals === 1 ? 'request' : 'requests'} to review</span>
        </div>
        <div className="decision__item">
          <b>{invoices}</b>
          <span>{invoices === 1 ? 'Invoice' : 'Invoices'} to accept</span>
        </div>
      </div>
    </section>
  );
}

function kpi(data: ShipManagerDashboard, key: string) {
  return data.kpis.find((k) => k.key === key)?.value ?? 0;
}
