import { useState } from 'react';
import { fetchTechnicalHead } from '@/api/dashboards';
import type { TechnicalHeadDashboard, VesselHealthRow } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatMoney } from '@/lib/format';
import { ActivityList, ConsoleHeader, Plate, RadialGauge, StageBars, StatTile } from '@/design-system/Console';
import { CategoryHealth, DueAttention, DueTimeline, HealthBar } from '@/design-system/MaintenanceCharts';
import { Money, RefreshButton } from '@/design-system/ConsoleParts';
import { VesselCard } from '@/design-system/VesselCard';
import { ErrorState, LoadingState } from '@/design-system/States';
import { RecentAlerts } from '@/features/alerts/RecentAlerts';
import { VesselDrawer } from './VesselDrawer';

/**
 * FLEET TECHNICAL COMMAND — Technical Head (SoW §8.1).
 *
 * <p>Scope: every vessel in the Technical Head's own organization. The role
 * sees invoice values (RBAC matrix) but raises, approves and assigns nothing,
 * so the screen is read-and-drill: how healthy is the fleet's equipment, when
 * does the work land, which vessels carry it, which equipment is behind it,
 * and what is already moving.
 */
export function TechnicalHeadPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'technical-head',
    fetchTechnicalHead,
  );

  const [openVessel, setOpenVessel] = useState<VesselHealthRow | null>(null);
  // Choosing a category narrows the attention list beside it, so the chart is
  // a way into the work rather than a picture of it.
  const [category, setCategory] = useState<string | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: 'Fleet operations' },
          { label: data?.meta.organizationName ?? '—', strong: true },
          { label: `${data?.meta.vesselsInScope ?? 0} vessels` },
        ]}
        title="Fleet technical command"
        subtitle="Equipment health, upcoming maintenance and open technical work across your fleet."
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading fleet" />
      ) : (
        <>
          <Summary data={data} />

          <Plate title="Fleet" count={data.vessels.length} subtitle="Select a vessel to see its equipment" flush>
            <div className="vcards">
              {data.vessels.map((v) => (
                <VesselCard key={v.vesselId} vessel={v} onOpen={() => setOpenVessel(v)} />
              ))}
            </div>
          </Plate>

          <div className="row-2">
            <Plate title="When maintenance falls due" subtitle="Tracked spares grouped by days until their next service">
              <DueTimeline points={data.maintenanceRadar} />
            </Plate>

            <Plate title="Service requests" count={kpi(data, 'openRequests')} subtitle="Open requests by workflow stage">
              <StageBars rows={data.requestsByStage.slices} />
              {/* SoW §8.1 pairs these two: what is waiting on the office, and
                  what the office has cleared this month. One without the other
                  says nothing about whether approvals are keeping up. */}
              <div className="approvals">
                <span>
                  <b>{kpi(data, 'awaitingApproval')}</b> awaiting Ship Manager approval
                </span>
                <span>
                  <b>{kpi(data, 'approvedThisMonth')}</b> approved this month
                </span>
              </div>
              {data.meta.financialsVisible && (
                <div className="money">
                  <Money
                    label="Invoices awaiting acceptance"
                    value={formatMoney(data.invoices.pendingValue, data.invoices.currency)}
                    count={data.invoices.pendingCount}
                  />
                  <Money
                    label="Invoices accepted"
                    value={formatMoney(data.invoices.acceptedValue, data.invoices.currency)}
                    count={data.invoices.acceptedCount}
                  />
                </div>
              )}
            </Plate>
          </div>

          <div className="row-2">
            <Plate
              title="Equipment health by category"
              subtitle="Worst first · select a category to filter the list beside it"
            >
              <CategoryHealth points={data.maintenanceRadar} selected={category} onSelect={setCategory} />
            </Plate>

            <DueAttention
              overdue={data.overdue}
              dueSoon={data.dueSoon}
              totals={{ overdue: kpi(data, 'overdue'), dueSoon: kpi(data, 'dueSoon') }}
              category={category}
              onClearCategory={() => setCategory(null)}
              spareHref={(d) => `/fleet/vessels/${d.vesselId}?spare=${d.spareId}`}
            />
          </div>

          <div className="row-2">
            <RecentAlerts subtitle="Maintenance status changes across the fleet" />
            <Plate
              title="Recent activity"
              count={data.recentActivity.length}
              subtitle="Latest service request events across the fleet"
              flush
            >
              <ActivityList items={data.recentActivity} />
            </Plate>
          </div>
        </>
      )}

      {openVessel && data && (
        <VesselDrawer
          vessel={openVessel}
          overdue={data.overdue.filter((d) => d.vesselId === openVessel.vesselId)}
          dueSoon={data.dueSoon.filter((d) => d.vesselId === openVessel.vesselId)}
          points={data.maintenanceRadar.filter((p) => p.vesselId === openVessel.vesselId)}
          activity={data.recentActivity.filter((a) => a.vesselId === openVessel.vesselId)}
          onClose={() => setOpenVessel(null)}
        />
      )}
    </div>
  );
}

function Summary({ data }: { data: TechnicalHeadDashboard }) {
  const normal = data.spareHealth.slices.find((s) => s.key === 'NORMAL')?.value ?? 0;
  const overdue = kpi(data, 'overdue');
  const dueSoon = kpi(data, 'dueSoon');
  const shortages = kpi(data, 'partShortages');

  return (
    <div className="summary">
      <Plate
        title="Maintenance health"
        subtitle={`All ${data.spareHealth.total} tracked spares across ${data.vessels.length} vessels, by service status`}
      >
        <div className="health">
          <HealthBar distribution={data.spareHealth} />
          <RadialGauge value={normal} of={data.spareHealth.total} caption="Within service interval" />
        </div>
      </Plate>

      <div className="tiles">
        <StatTile
          icon="bell"
          label="Overdue services"
          value={overdue}
          tone={overdue > 0 ? 'alert' : undefined}
          caption="Past their due date"
        />
        <StatTile
          icon="history"
          label="Due within 15 days"
          value={dueSoon}
          tone={dueSoon > 0 ? 'warn' : undefined}
          caption="Plan these next"
        />
        <StatTile
          icon="wrench"
          label="Open service requests"
          value={kpi(data, 'openRequests')}
          caption={`${kpi(data, 'awaitingApproval')} awaiting Ship Manager approval`}
        />
        <StatTile
          icon="spare"
          label="Parts below minimum"
          value={shortages}
          tone={shortages > 0 ? 'warn' : undefined}
          caption="Replacement stock to reorder"
        />
      </div>
    </div>
  );
}

function kpi(data: TechnicalHeadDashboard, key: string) {
  return data.kpis.find((k) => k.key === key)?.value ?? 0;
}
