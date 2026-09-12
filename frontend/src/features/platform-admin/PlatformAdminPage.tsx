import { useState } from 'react';
import { fetchPlatformAdmin } from '@/api/dashboards';
import type { ActivityItem, OrganizationSummary } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatMoney, relativeTime } from '@/lib/format';
import { KpiRow } from '@/design-system/Kpi';
import { Button, PageHeader, Panel } from '@/design-system/Panel';
import { DataTable, type Column } from '@/design-system/DataTable';
import { StackedBar, BarList } from '@/design-system/Charts';
import { ErrorState, KpiSkeleton, LoadingState, EmptyState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import '../shared/dashboard.css';
import './platform.css';

/**
 * Platform Administrator (SoW §8.5).
 *
 * <p>The activity feed is the primary column rather than a footnote: §8.5 makes
 * consolidated real-time visibility this role's instrument, on the reasoning
 * that a Platform Admin must know every update without being paged on each one.
 * Metrics sit in a supporting rail.
 */
export function PlatformAdminPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'platform-admin',
    fetchPlatformAdmin,
  );
  const [orgFilter, setOrgFilter] = useState<number | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const feed = data?.activityFeed ?? [];
  // Filters the already-scoped feed the server returned. It never requests a
  // wider set: a Platform Admin sees everything, and narrowing is a view
  // preference rather than an access decision.
  const visibleFeed = orgFilter ? feed.filter((a) => a.organizationId === orgFilter) : feed;

  return (
    <div className="page">
      <PageHeader
        eyebrow="Platform administration"
        title="Platform overview"
        meta={
          data && (
            <>
              <span>
                {data.systemStatus.organizations} organizations &middot;{' '}
                {data.systemStatus.vessels} vessels &middot; {data.systemStatus.users} users
              </span>
              <span>Updated {relativeTime(data.meta.generatedAt)}</span>
              {data.systemStatus.seedDataPresent && (
                <Pill tone="approaching" size="sm">
                  Demo data present
                </Pill>
              )}
            </>
          )
        }
        actions={
          <Button onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {isLoading || !data ? <KpiSkeleton count={6} /> : <KpiRow items={data.kpis} />}

      <div className="pa-grid">
        {/* The feed leads. It is the widest column on the page for a reason. */}
        <Panel
          title="Platform activity"
          subtitle="Every request, approval, invoice and completion across all organizations"
          count={visibleFeed.length}
          padded={false}
          action={
            data && data.organizations.length > 1 ? (
              <select
                id="pa-org-filter"
                className="select"
                value={orgFilter ?? ''}
                onChange={(e) => setOrgFilter(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">All organizations</option>
                {data.organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            ) : undefined
          }
        >
          {isLoading ? (
            <LoadingState rows={8} />
          ) : visibleFeed.length === 0 ? (
            <EmptyState
              title="No activity recorded"
              body="Service request events across the platform will appear here as they happen."
              icon="inbox"
            />
          ) : (
            <ol className="feed">
              {visibleFeed.map((a, i) => (
                <li key={`${a.serviceRequestId}-${a.occurredAt}-${i}`} className="feed__item">
                  <span className={`feed__rail feed__rail--${toneFor(a)}`} aria-hidden="true" />
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
                          {a.actorRole && (
                            <span className="feed__role">{roleLabel(a.actorRole)}</span>
                          )}
                        </>
                      )}
                    </p>
                    {a.reason && <p className="feed__reason">“{a.reason}”</p>}
                  </div>
                  <time className="feed__time" dateTime={a.occurredAt}>
                    {relativeTime(a.occurredAt)}
                  </time>
                </li>
              ))}
            </ol>
          )}
        </Panel>

        <div className="pa-rail">
          <Panel title="Vessels by status">
            {isLoading || !data ? <LoadingState rows={3} /> : <StackedBar data={data.vesselStatus} />}
          </Panel>

          <Panel title="Service requests by stage">
            {isLoading || !data ? (
              <LoadingState rows={4} />
            ) : (
              <StackedBar data={data.requestStatus} />
            )}
          </Panel>

          <Panel title="Users by role">
            {isLoading || !data ? (
              <LoadingState rows={5} />
            ) : (
              <BarList
                data={data.usersByRole.map((u) => ({
                  key: u.role,
                  label: roleLabel(u.role),
                  value: u.count,
                  colour: 'accent',
                }))}
              />
            )}
          </Panel>

          <Panel title="Invoice totals" subtitle="Acceptance workflow only — no settlement">
            {isLoading || !data ? (
              <LoadingState rows={2} />
            ) : (
              <div className="pa-money">
                <div>
                  <span className="pa-money__label">Awaiting acceptance</span>
                  <span className="pa-money__value mono">
                    {formatMoney(data.invoiceTotals.raisedValue, data.invoiceTotals.currency)}
                  </span>
                  <span className="pa-money__count">{data.invoiceTotals.raised} invoices</span>
                </div>
                <div>
                  <span className="pa-money__label">Accepted</span>
                  <span className="pa-money__value mono">
                    {formatMoney(data.invoiceTotals.acceptedValue, data.invoiceTotals.currency)}
                  </span>
                  <span className="pa-money__count">{data.invoiceTotals.accepted} invoices</span>
                </div>
              </div>
            )}
          </Panel>
        </div>
      </div>

      <Panel
        title="Organizations"
        subtitle="Client companies on the platform"
        count={data?.organizations.length}
        padded={false}
      >
        {isLoading || !data ? (
          <LoadingState rows={4} />
        ) : (
          <DataTable<OrganizationSummary>
            columns={ORG_COLUMNS}
            rows={data.organizations}
            rowKey={(o) => o.id}
            emptyTitle="No organizations"
            emptyBody="A Platform Administrator creates each client organization and its Technical Head."
          />
        )}
      </Panel>
    </div>
  );
}

const ORG_COLUMNS: Column<OrganizationSummary>[] = [
  {
    key: 'name',
    header: 'Organization',
    sortValue: (o) => o.name,
    render: (o) => (
      <>
        <span className="cell-strong">{o.name}</span>
        <span className="cell-sub mono">{o.code}</span>
      </>
    ),
  },
  {
    key: 'vessels',
    header: 'Vessels',
    align: 'right',
    width: '110px',
    sortValue: (o) => o.vesselCount,
    render: (o) => <span className="mono">{o.vesselCount}</span>,
  },
  {
    key: 'users',
    header: 'Users',
    align: 'right',
    width: '110px',
    sortValue: (o) => o.userCount,
    render: (o) => <span className="mono">{o.userCount}</span>,
  },
];

/** Colour the feed rail by what kind of event it was. */
function toneFor(a: ActivityItem): string {
  switch (a.toStatus) {
    case 'REJECTED':
    case 'INVOICE_REJECTED':
      return 'overdue';
    case 'PENDING_OPERATIONAL_APPROVAL':
    case 'INVOICE_RAISED':
    case 'INVOICE_QUERIED':
    case 'CLARIFICATION_REQUESTED':
      return 'approaching';
    case 'COMPLETED':
    case 'CLOSED_NO_COST':
      return 'normal';
    default:
      return 'accent';
  }
}

function roleLabel(role: string) {
  return role
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}
