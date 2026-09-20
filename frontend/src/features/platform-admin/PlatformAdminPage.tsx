import { useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchPlatformAdmin } from '@/api/dashboards';
import type { OrganizationSummary, PlatformAdminDashboard } from '@/api/types';
import { useDashboard } from '@/lib/useDashboard';
import { formatMoney } from '@/lib/format';
import { ActivityList, Chip, ConsoleHeader, Plate, StageBars, StatTile, roleName } from '@/design-system/Console';
import { Money, RefreshButton } from '@/design-system/ConsoleParts';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';

/**
 * GLOBAL MARITIME CONTROL — Platform Administrator (SoW §8.5).
 *
 * <p>Scope: the entire platform. This is the only role granted the
 * platform-wide activity feed and the full audit count (RBAC matrix), so the
 * feed is the widest column on the page. Organizations double as the feed's
 * filter — picking one narrows what is already on screen and never requests a
 * wider set, because narrowing is a view preference, not an access decision.
 */
export function PlatformAdminPage() {
  const { data, isLoading, isError, error, refetch, isFetching } = useDashboard(
    'platform-admin',
    fetchPlatformAdmin,
  );
  const [orgFilter, setOrgFilter] = useState<number | null>(null);

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  const feed = data?.activityFeed ?? [];
  const visibleFeed = orgFilter ? feed.filter((a) => a.organizationId === orgFilter) : feed;
  const selectedOrg = data?.organizations.find((o) => o.id === orgFilter) ?? null;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: 'Platform administration' },
          { label: 'All organizations', strong: true },
          { label: `${data?.systemStatus.organizations ?? 0} organizations` },
        ]}
        badge={
          data?.systemStatus.seedDataPresent ? (
            <Pill tone="approaching" size="sm">
              Demo data present
            </Pill>
          ) : undefined
        }
        title="Global maritime control"
        subtitle="Client organizations, fleet scale, users and every service event across the platform."
        generatedAt={data?.meta.generatedAt}
        actions={<RefreshButton onClick={() => refetch()} busy={isFetching} />}
      />

      {isLoading || !data ? (
        <LoadingState rows={6} label="Loading platform" />
      ) : (
        <>
          <Summary data={data} />

          <Plate
            title="Organizations"
            count={data.organizations.length}
            subtitle="Select an organization to filter the platform activity"
          >
            <div className="orgs">
              {data.organizations.map((o) => (
                <OrgCard
                  key={o.id}
                  org={o}
                  totalVessels={data.systemStatus.vessels}
                  totalUsers={data.systemStatus.users}
                  selected={orgFilter === o.id}
                  onSelect={() => setOrgFilter((cur) => (cur === o.id ? null : o.id))}
                />
              ))}
            </div>
          </Plate>

          <div className="row-main-side">
            <Plate
              title="Platform activity"
              count={visibleFeed.length}
              subtitle="Every request, approval, invoice and completion across the platform"
              action={
                selectedOrg ? (
                  <Chip on onClick={() => setOrgFilter(null)}>
                    {selectedOrg.name} <span aria-hidden="true">✕</span>
                    <span className="sr-only">Show all organizations</span>
                  </Chip>
                ) : (
                  <Link to="/platform/activity" className="cbtn cbtn--secondary cbtn--md">
                    Full activity feed
                  </Link>
                )
              }
              flush
              fill
            >
              <ActivityList items={visibleFeed} showReason />
            </Plate>

            <div className="stack-col">
              <Plate title="Vessels by status" subtitle={`All ${data.systemStatus.vessels} registered vessels`}>
                <StageBars rows={data.vesselStatus.slices} hideZero={false} />
              </Plate>

              <Plate title="Service requests by stage" subtitle="Every request on the platform">
                <StageBars rows={data.requestStatus.slices} />
              </Plate>

              <Plate title="Users by role" subtitle={`${data.systemStatus.users} users`}>
                <StageBars
                  rows={data.usersByRole.map((u) => ({ key: u.role, label: roleName(u.role), value: u.count }))}
                />
              </Plate>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Summary({ data }: { data: PlatformAdminDashboard }) {
  const s = data.systemStatus;
  const inv = data.invoiceTotals;

  return (
    <div className="summary">
      <Plate title="Platform at a glance" subtitle="Everything registered on SeaStella today">
        <div className="scale4">
          <Figure value={s.organizations} label="Organizations" />
          <Figure value={s.vessels} label="Vessels" />
          <Figure value={s.users} label="Users" />
          <Figure value={s.spares} label="Spares tracked" />
        </div>
        <div className="money">
          <Money label="Invoices awaiting acceptance" value={formatMoney(inv.raisedValue, inv.currency)} count={inv.raised} />
          <Money label="Invoices accepted" value={formatMoney(inv.acceptedValue, inv.currency)} count={inv.accepted} />
        </div>
      </Plate>

      <div className="tiles tiles--stack">
        <StatTile
          icon="wrench"
          label="Open service requests"
          value={s.openRequests}
          caption="Across every organization, not yet closed"
        />
        <StatTile icon="audit" label="Audit entries" value={s.auditEntries} caption="Recorded platform events" />
      </div>
    </div>
  );
}

function Figure({ value, label }: { value: number; label: string }) {
  return (
    <div className="figure">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

function OrgCard({
  org,
  totalVessels,
  totalUsers,
  selected,
  onSelect,
}: {
  org: OrganizationSummary;
  totalVessels: number;
  totalUsers: number;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      className={`orgcard${selected ? ' orgcard--on' : ''}`}
      onClick={onSelect}
      aria-pressed={selected}
    >
      <div className="orgcard__top">
        <span className="orgcard__mark" aria-hidden="true">
          {org.code.slice(0, 2)}
        </span>
        <div>
          <div className="orgcard__name">{org.name}</div>
          <div className="orgcard__code">{org.code}</div>
        </div>
      </div>
      <Share label="Vessels" value={org.vesselCount} of={totalVessels} />
      <Share label="Users" value={org.userCount} of={totalUsers} />
    </button>
  );
}

/** A count with its share of the platform total drawn beside it. */
function Share({ label, value, of }: { label: string; value: number; of: number }) {
  return (
    <div className="share">
      <span className="share__label">{label}</span>
      <span className="share__track" aria-hidden="true">
        <i style={{ width: `${of === 0 ? 0 : (value / of) * 100}%` }} />
      </span>
      <span className="share__value">
        {value} <small>of {of}</small>
      </span>
    </div>
  );
}
