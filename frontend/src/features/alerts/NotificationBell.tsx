import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchInbox, markAlertRead, markAllAlertsRead, type AlertItem } from '@/api/notifications';
import { EmptyNote } from '@/design-system/Console';
import { Icon } from '@/design-system/Icon';
import { AlertList } from './AlertList';

/** How often the bell checks for new alerts. */
const POLL_MS = 20_000;

/**
 * The station bar's alert bell (NOT-12): unread count at a glance, the latest
 * alerts one click away, each leading to the record it is about.
 */
export function NotificationBell() {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);

  const inbox = useQuery({
    queryKey: ['notifications'],
    queryFn: () => fetchInbox(20),
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
  });

  // Close on outside click and on Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const unread = inbox.data?.unreadCount ?? 0;
  const items = inbox.data?.items ?? [];

  const openItem = async (item: AlertItem) => {
    setOpen(false);
    if (!item.read) {
      await markAlertRead(item.id).catch(() => undefined);
      client.invalidateQueries({ queryKey: ['notifications'] });
    }
  };

  const readAll = async () => {
    await markAllAlertsRead().catch(() => undefined);
    client.invalidateQueries({ queryKey: ['notifications'] });
  };

  return (
    <div className="bell" ref={wrap}>
      <button
        type="button"
        className={`bell__btn${open ? ' bell__btn--on' : ''}`}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="true"
        aria-label={unread > 0 ? `Alerts, ${unread} unread` : 'Alerts'}
      >
        <Icon name="bell" size={18} />
        {unread > 0 && <span className="bell__count">{unread > 99 ? '99+' : unread}</span>}
      </button>

      {open && (
        <div className="bell__panel" role="region" aria-label="Alerts">
          <header className="bell__head">
            <div>
              <h2 className="bell__title">Alerts</h2>
              <p className="bell__sub">{unread > 0 ? `${unread} unread` : 'All caught up'}</p>
            </div>
            {unread > 0 && (
              <button type="button" className="cbtn cbtn--ghost cbtn--md" onClick={readAll}>
                Mark all read
              </button>
            )}
          </header>
          <div className="bell__body">
            {inbox.isLoading ? (
              <p className="bell__empty">Loading alerts…</p>
            ) : inbox.isError ? (
              <p className="bell__empty">Alerts could not be loaded.</p>
            ) : items.length === 0 ? (
              <div className="bell__empty">
                <EmptyNote>No alerts yet. You will be told here when something needs you.</EmptyNote>
              </div>
            ) : (
              <AlertList items={items} onOpen={openItem} compact />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
