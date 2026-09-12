import { useState } from 'react';
import { fetchTechnicalHead } from '@/api/dashboards';
import type { DueItem, VesselHealthRow } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatDate, formatMoney, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, PageHeader, Panel } from '@/design-system/Panel';
import { DataTable, type Column } from '@/design-system/DataTable';
import { StackedBar, SplitMeter } from '@/design-system/Charts';
import { MaintenanceRadar } from '@/design-system/MaintenanceRadar';
import { ErrorState, KpiSkeleton, LoadingState } from '@/design-system/States';
import { DueStatusBadge, VesselStatusChip } from '@/design-system/StatusBadge';
import { VesselDrawer } from './VesselDrawer';
import '../shared/dashboard.css';
import './fleet.css';

/**
 * Technical Head — fleet health (SoW §8.1).
 *
 * <p>Answers one question: what across my fleet needs attention? So exceptions
 * lead — overdue before due-soon, both before the full vessel table — and the
 * vessel table is the drill-down path (fleet → vessel → spare) rather than the
 * headline.
 *
 * <p>This role has no operational actions (§5 gives it monitoring and
 * drill-down only), so there are deliberately no action queues here.
 */
export function TechnicalHeadPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'technical-head',
    fetchTechnicalHead,
  );
  const [drillVessel, setDrillVessel] = useState<VesselHealthRow | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  return (
    <div className="page">
      <PageHeader
        eyebrow="Fleet technical oversight"
        title={data?.meta.organizationName ?? 'Fleet health'}
        meta={
          data && (
            <>
              <span>
                {data.meta.vesselsInScope} vessels &middot;{' '}
                {data.kpis.find((k) => k.key === 'spares')?.value ?? 0} spares
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

      {isLoading || !data ? <KpiSkeleton count={8} /> : <KpiRow items={data.kpis} />}

      {/* The radar leads: it answers "how much is imminent, and is it
          concentrated anywhere" in one look. The exception lists beside it
          name the specific work. */}
      <div className="th-scope">
        <Panel
          title="Maintenance radar"
          subtitle="Range is time to service · bearing is equipment category"
          variant="instrument"
        >
          {isLoading || !data ? (
            <LoadingState rows={6} />
          ) : (
            <MaintenanceRadar points={data.maintenanceRadar} />
          )}
        </Panel>

        <div className="stack">
          <Panel title="Spare maintenance health" subtitle="Across the whole fleet">
            {isLoading || !data ? (
              <LoadingState rows={5} />
            ) : (
              <StackedBar data={data.spareHealth} />
            )}
          </Panel>

          <Panel title="Service requests by stage">
            {isLoading || !data ? (
              <LoadingState rows={5} />
            ) : (
              <StackedBar data={data.requestsByStage} />
            )}
          </Panel>
        </div>

        <div className="stack">
          <Panel
            title="Troubleshooting effectiveness"
            subtitle="Resolved without a visit vs requiring an engineer"
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

          <Panel title="Invoices" subtitle="Fleet-wide, acceptance workflow only">
            {isLoading || !data ? (
              <LoadingState rows={3} />
            ) : (
              <div className="th-invoices">
                <div>
                  <span className="th-invoices__label">Awaiting Ship Manager</span>
                  <span className="th-invoices__value mono">
                    {formatMoney(data.invoices.pendingValue, data.invoices.currency)}
                  </span>
                  <span className="th-invoices__count">{data.invoices.pendingCount} invoices</span>
                </div>
                <div>
                  <span className="th-invoices__label">Accepted</span>
                  <span className="th-invoices__value mono">
                    {formatMoney(data.invoices.acceptedValue, data.invoices.currency)}
                  </span>
                  <span className="th-invoices__count">{data.invoices.acceptedCount} invoices</span>
                </div>
              </div>
            )}
          </Panel>

          <Panel title="Spares by criticality">
            {isLoading || !data ? (
              <LoadingState rows={4} />
            ) : (
              <StackedBar data={data.spareCriticality} />
            )}
          </Panel>
        </div>
      </div>

      <div className="grid-halves">
        <Panel
          title="Overdue services"
          subtitle="Past their due date"
          count={data?.overdue.length}
          padded={false}
          tone={data && data.overdue.length > 0 ? 'attention' : 'default'}
        >
          {isLoading || !data ? (
            <LoadingState rows={4} />
          ) : (
            <DataTable<DueItem>
              columns={dueColumns}
              rows={data.overdue}
              rowKey={(d) => d.spareId}
              dense
              pageSize={8}
              emptyTitle="Nothing overdue"
              emptyBody="Every tracked spare in the fleet is within its service interval."
            />
          )}
        </Panel>

        <Panel
          title="Due within 15 days"
          count={data?.dueSoon.length}
          padded={false}
        >
          {isLoading || !data ? (
            <LoadingState rows={4} />
          ) : (
            <DataTable<DueItem>
              columns={dueColumns}
              rows={data.dueSoon}
              rowKey={(d) => d.spareId}
              dense
              pageSize={8}
              emptyTitle="Nothing due soon"
              emptyBody="No spare falls due in the next fifteen days."
            />
          )}
        </Panel>
      </div>

      <Panel
        title="Vessels"
        subtitle="Select a vessel to drill into its spares and open work"
        count={data?.vessels.length}
        padded={false}
      >
        {isLoading || !data ? (
          <LoadingState rows={6} />
        ) : (
          <DataTable<VesselHealthRow>
            columns={vesselColumns}
            rows={data.vessels}
            rowKey={(v) => v.vesselId}
            onRowClick={setDrillVessel}
            emptyTitle="No vessels in scope"
            emptyBody="A Platform Administrator assigns vessels to your organization."
          />
        )}
      </Panel>

      {drillVessel && (
        <VesselDrawer
          vessel={drillVessel}
          overdue={data?.overdue.filter((d) => d.vesselId === drillVessel.vesselId) ?? []}
          dueSoon={data?.dueSoon.filter((d) => d.vesselId === drillVessel.vesselId) ?? []}
          activity={
            data?.recentActivity.filter((a) => a.vesselId === drillVessel.vesselId) ?? []
          }
          onClose={() => setDrillVessel(null)}
        />
      )}
    </div>
  );
}

/**
 * Shared columns for the two due lists.
 *
 * <p>The badge receives `status` from the API — the colour band was decided by
 * the maintenance engine, and nothing here recomputes it from `daysRemaining`.
 */
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
    width: '120px',
    sortValue: (d) => d.nextDueDate ?? '',
    render: (d) => <span className="mono cell-date">{formatDate(d.nextDueDate)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    width: '150px',
    sortValue: (d) => d.daysRemaining ?? 9999,
    render: (d) => <DueStatusBadge status={d.status} daysRemaining={d.daysRemaining} size="sm" />,
  },
];

const vesselColumns: Column<VesselHealthRow>[] = [
  {
    key: 'vessel',
    header: 'Vessel',
    sortValue: (v) => v.vesselName,
    render: (v) => (
      <>
        <span className="cell-strong">{v.vesselName}</span>
        <span className="cell-sub mono">
          IMO {v.imoNumber}
          {v.vesselType ? ` · ${v.vesselType}` : ''}
        </span>
      </>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    width: '140px',
    sortValue: (v) => v.status,
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
    key: 'dueSoon',
    header: 'Due soon',
    align: 'right',
    width: '100px',
    sortValue: (v) => v.dueSoon,
    render: (v) => <Count value={v.dueSoon} tone={v.dueSoon > 0 ? 'approaching' : undefined} />,
  },
  {
    key: 'overdue',
    header: 'Overdue',
    align: 'right',
    width: '100px',
    sortValue: (v) => v.overdue,
    render: (v) => <Count value={v.overdue} tone={v.overdue > 0 ? 'overdue' : undefined} />,
  },
  {
    key: 'requests',
    header: 'Open requests',
    align: 'right',
    width: '130px',
    sortValue: (v) => v.openRequests,
    render: (v) => <Count value={v.openRequests} tone={v.openRequests > 0 ? 'accent' : undefined} />,
  },
  {
    key: 'shortages',
    header: 'Low stock',
    align: 'right',
    width: '110px',
    sortValue: (v) => v.partShortages,
    render: (v) => (
      <Count value={v.partShortages} tone={v.partShortages > 0 ? 'urgent' : undefined} />
    ),
  },
];

/** A zero should recede; a non-zero should not. */
function Count({ value, tone }: { value: number; tone?: string }) {
  if (value === 0) return <span className="count count--zero mono">0</span>;
  return <span className={`count count--${tone} mono`}>{value}</span>;
}
