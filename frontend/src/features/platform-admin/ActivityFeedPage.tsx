import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchActivity, streamActivity, type ActivityCategory, type ActivityEntry } from '@/api/activity';
import { Button, ConsoleHeader, EmptyNote, Plate, Segmented } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Icon } from '@/design-system/Icon';
import { formatDate } from '@/lib/format';
import './activity.css';

/**
 * The fallback pace, used only when the push channel is down (FEE-04). While
 * the stream is connected the feed is not polled at all: the server says when
 * something has happened.
 */
const FALLBACK_REFRESH_MS = 15_000;

type Filter = 'ALL' | ActivityCategory;

/**
 * ACTIVITY FEED — Platform Admin (SoW §8.5, §11, §12).
 *
 * <p>Every request, troubleshooting outcome, escalation, approval, invoice,
 * completion, maintenance change and setup action across all organizations and
 * vessels, newest first. It reads the audit trail, so what it shows is what was
 * recorded — the Platform Admin sees every update without being paged for each.
 *
 * <p>Pushed, not polled (FEE-04): the server announces each new audit entry
 * over SSE and the page refetches on the announcement. If the stream cannot be
 * held open — an old proxy, a dropped network — it falls back to asking every
 * fifteen seconds, and says which of the two it is doing rather than showing a
 * "Live" badge that might mean nothing.
 */
export function ActivityFeedPage() {
  const [category, setCategory] = useState<Filter>('ALL');
  const [organizationId, setOrganizationId] = useState<number | undefined>();
  const [older, setOlder] = useState<ActivityEntry[]>([]);
  const [olderCursor, setOlderCursor] = useState<number | undefined>();
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const client = useQueryClient();
  const streamingRef = useRef(false);

  const filter = { organizationId, category: category === 'ALL' ? undefined : category };
  const latest = useQuery({
    queryKey: ['activity', filter],
    queryFn: () => fetchActivity(filter),
    // Polling is the fallback, not the mechanism: off while the stream is up.
    refetchInterval: streaming ? false : FALLBACK_REFRESH_MS,
  });

  useEffect(() => {
    const close = streamActivity(
      () => client.invalidateQueries({ queryKey: ['activity'] }),
      (live) => {
        streamingRef.current = live;
        setStreaming(live);
      },
    );
    return close;
  }, [client]);

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
      const page = await fetchActivity({ ...filter, before: cursor });
      setOlder((prev) => [...prev, ...page.items]);
      setOlderCursor(page.nextBefore ?? 0);
    } finally {
      setLoadingOlder(false);
    }
  };

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Activity feed', strong: true }]}
        title="Activity feed"
        subtitle="Every update across all organizations and vessels, as it happens: requests, checks, chats, approvals, invoices, completions, maintenance and setup."
        actions={
          <span
            className={`feed__live${streaming ? '' : ' feed__live--polling'}`}
            title={
              streaming
                ? 'The server pushes each update as it is recorded'
                : `The push channel is not available; checking every ${FALLBACK_REFRESH_MS / 1000} seconds`
            }
          >
            {streaming ? 'Live' : 'Checking'}
          </span>
        }
      />

      <Plate
        title="Events"
        subtitle="Newest first"
        action={
          <select
            className="input feed__org"
            aria-label="Organization"
            value={organizationId ?? ''}
            onChange={(e) => {
              setOrganizationId(e.target.value ? Number(e.target.value) : undefined);
              resetPaging();
            }}
          >
            <option value="">All organizations</option>
            {(latest.data?.organizations ?? []).map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        }
        flush
      >
        <div className="attn__tools">
          <Segmented<Filter>
            label="Event type"
            value={category}
            onChange={(c) => {
              setCategory(c);
              resetPaging();
            }}
            options={[
              { value: 'ALL', label: 'All' },
              { value: 'REQUESTS', label: 'Requests' },
              { value: 'INVOICES', label: 'Invoices' },
              { value: 'MAINTENANCE', label: 'Maintenance' },
              { value: 'SETUP', label: 'Setup' },
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

function FeedList({ items }: { items: ActivityEntry[] }) {
  let lastDay = '';
  return (
    <ol className="feed">
      {items.map((e) => {
        const day = new Date(e.occurredAt).toDateString();
        const header = day !== lastDay ? <li className="feed__day">{dayLabel(e.occurredAt)}</li> : null;
        lastDay = day;
        return (
          <FeedDay key={e.id} header={header}>
            <li className="feed__item">
              <span className={`feed__icon feed__icon--${e.category.toLowerCase()}`} aria-hidden="true">
                <Icon name={ICON[e.category]} size={15} />
              </span>
              <div className="feed__main">
                <p className="feed__line">
                  <b>{e.actorName}</b>
                  {e.actorRole && <span className="feed__role">{e.actorRole}</span>} {e.summary}
                </p>
                <p className="feed__meta">
                  {[e.organizationName, e.vesselName].filter(Boolean).join(' · ')}
                  {e.serviceRequestId && (
                    <>
                      {' · '}
                      <Link to={`/requests/${e.serviceRequestId}`}>Open request</Link>
                    </>
                  )}
                </p>
              </div>
              <time className="feed__time" dateTime={e.occurredAt}>
                {new Date(e.occurredAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}
              </time>
            </li>
          </FeedDay>
        );
      })}
    </ol>
  );
}

function FeedDay({ header, children }: { header: React.ReactNode; children: React.ReactNode }) {
  return (
    <>
      {header}
      {children}
    </>
  );
}

const ICON: Record<ActivityCategory, string> = {
  REQUESTS: 'wrench',
  INVOICES: 'invoice',
  MAINTENANCE: 'gauge',
  SETUP: 'users',
};

function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86_400_000);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return formatDate(iso);
}
