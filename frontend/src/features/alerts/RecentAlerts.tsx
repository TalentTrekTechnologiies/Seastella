import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchInbox, markAlertRead, type AlertItem } from '@/api/notifications';
import { EmptyNote, Plate } from '@/design-system/Console';
import { AlertList } from './AlertList';

/**
 * "Recent alerts" on a dashboard (SoW s8.1, s8.2, s8.3). The same inbox the
 * bell reads, so the two never disagree.
 */
export function RecentAlerts({ subtitle, limit = 6 }: { subtitle: string; limit?: number }) {
  const client = useQueryClient();
  const inbox = useQuery({ queryKey: ['notifications'], queryFn: () => fetchInbox(20), refetchInterval: 20_000 });
  const items = (inbox.data?.items ?? []).slice(0, limit);

  const open = async (item: AlertItem) => {
    if (item.read) return;
    await markAlertRead(item.id).catch(() => undefined);
    client.invalidateQueries({ queryKey: ['notifications'] });
  };

  return (
    <Plate title="Recent alerts" count={inbox.data?.unreadCount ? `${inbox.data.unreadCount} unread` : undefined} subtitle={subtitle} flush>
      {inbox.isLoading ? (
        <div className="qlist__empty">
          <EmptyNote>Loading alerts…</EmptyNote>
        </div>
      ) : items.length === 0 ? (
        <div className="qlist__empty">
          <EmptyNote>{inbox.isError ? 'Alerts could not be loaded.' : 'No alerts yet.'}</EmptyNote>
        </div>
      ) : (
        <AlertList items={items} onOpen={open} />
      )}
    </Plate>
  );
}
