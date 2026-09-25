import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchMyActivity, type ActivityCategory, type ActivityEntry } from '@/api/activity';
import { fetchAdminVessels } from '@/api/admin';
import type { Role } from '@/api/types';
import { Button, ConsoleHeader, EmptyNote, Plate, Segmented } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { FeedList } from '@/features/platform-admin/ActivityFeedPage';
import '@/features/platform-admin/activity.css';

const REFRESH_MS = 30_000;

type Filter = 'ALL' | ActivityCategory;

/** Invoices are visible to these roles only (SoW §8). */
const SEES_INVOICES: Role[] = ['PLATFORM_ADMIN', 'TECHNICAL_HEAD', 'SHIP_MANAGER', 'SERVICE_COORDINATOR'];
/** Roles that look after more than one vessel, and so can narrow to one. */
const PICKS_VESSEL: Role[] = ['PLATFORM_ADMIN', 'TECHNICAL_HEAD', 'SHIP_MANAGER'];

/**
 * ACTIVITY HISTORY — every role's own record of work (SoW §8, §12).
 *
 * <p>What the signed-in person did, and what happened on the vessels they are
 * responsible for: requests raised and approved, checks and chats, invoices
 * where the role may see them, completions, stock counts, work recorded,
 * documents, imports and setup. It reads the same audit trail the Platform
 * Admin's feed does, narrowed to the caller's scope.
 */
export function ActivityHistoryPage({ role }: { role: Role }) {
  const [category, setCategory] = useState<Filter>('ALL');
  const [onlyMine, setOnlyMine] = useState(false);
  const [vesselId, setVesselId] = useState<number | undefined>();
  const [older, setOlder] = useState<ActivityEntry[]>([]);
  const [olderCursor, setOlderCursor] = useState<number | undefined>();
  const [loadingOlder, setLoadingOlder] = useState(false);

  const vessels = useQuery({
    queryKey: ['admin-vessels'],
    queryFn: fetchAdminVessels,
    enabled: PICKS_VESSEL.includes(role),
  });
  const filter = { category: category === 'ALL' ? undefined : category, onlyMine, vesselId };
  const latest = useQuery({
    queryKey: ['my-activity', filter],
    queryFn: () => fetchMyActivity(filter),
    refetchInterval: REFRESH_MS,
  });

  const resetPaging = () => {
    setOlder([]);
    setOlderCursor(undefined);
  };

  if (latest.error) return <ErrorState error={latest.error} onRetry={() => latest.refetch()} />;

  const items = [...(latest.data?.items ?? []), ...older.filter((o) => !(latest.data?.items ?? []).some((i) => i.id === o.id))];
  const cursor = olderCursor ?? latest.data?.nextBefore;

  const loadOlder = async () => {
    if (!cursor) return;
    setLoadingOlder(true);
    try {
      const page = await fetchMyActivity({ ...filter, before: cursor });
      setOlder((prev) => [...prev, ...page.items]);
      setOlderCursor(page.nextBefore ?? 0);
    } finally {
      setLoadingOlder(false);
    }
  };

  const categories: { value: Filter; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'REQUESTS', label: 'Requests' },
    ...(SEES_INVOICES.includes(role) ? [{ value: 'INVOICES' as Filter, label: 'Invoices' }] : []),
    { value: 'MAINTENANCE', label: 'Equipment & spares' },
    { value: 'SETUP', label: 'Setup & imports' },
  ];

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'History' }, { label: 'Activity history', strong: true }]}
        title="Activity history"
        subtitle={
          role === 'SERVICE_ENGINEER'
            ? 'Everything you have done, and everything that happened on your jobs.'
            : 'Everything you have done, and everything that happened on the vessels you are responsible for — who did it and when.'
        }
      />

      <Plate
        title="Events"
        subtitle="Newest first"
        action={
          PICKS_VESSEL.includes(role) && (vessels.data?.length ?? 0) > 1 ? (
            <select
              className="input feed__org"
              aria-label="Vessel"
              value={vesselId ?? ''}
              onChange={(e) => {
                setVesselId(e.target.value ? Number(e.target.value) : undefined);
                resetPaging();
              }}
            >
              <option value="">All my vessels</option>
              {(vessels.data ?? []).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          ) : undefined
        }
        flush
      >
        <div className="attn__tools" style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <Segmented<Filter>
            label="Event type"
            value={category}
            onChange={(c) => {
              setCategory(c);
              resetPaging();
            }}
            options={categories}
          />
          <Segmented<'all' | 'mine'>
            label="Whose"
            value={onlyMine ? 'mine' : 'all'}
            onChange={(v) => {
              setOnlyMine(v === 'mine');
              resetPaging();
            }}
            options={[
              { value: 'all', label: 'Everyone' },
              { value: 'mine', label: 'Only mine' },
            ]}
          />
        </div>

        {latest.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={8} />
          </div>
        ) : items.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>Nothing recorded for this selection yet.</EmptyNote>
          </div>
        ) : (
          <FeedList items={items} />
        )}

        {cursor ? (
          <div className="feed__more">
            <Button onClick={loadOlder} disabled={loadingOlder}>
              {loadingOlder ? 'Loading…' : 'Show older events'}
            </Button>
          </div>
        ) : null}
      </Plate>
    </div>
  );
}

/** The last few entries of the caller's history, for the foot of a dashboard. */
export function RecentHistoryPlate() {
  const recent = useQuery({
    queryKey: ['my-activity', 'recent'],
    queryFn: () => fetchMyActivity({ limit: 8 }),
    refetchInterval: REFRESH_MS,
  });
  const items = recent.data?.items ?? [];
  return (
    <Plate
      title="Your history"
      subtitle="Recent work by you and on your vessels"
      action={<Link to="/history">See all</Link>}
      flush
    >
      {recent.isLoading ? (
        <div style={{ padding: '0 20px 20px' }}>
          <LoadingState rows={4} />
        </div>
      ) : items.length === 0 ? (
        <div className="qlist__empty">
          <EmptyNote>Nothing recorded yet.</EmptyNote>
        </div>
      ) : (
        <FeedList items={items} />
      )}
    </Plate>
  );
}
