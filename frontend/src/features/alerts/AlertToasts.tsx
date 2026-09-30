import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { alertLink, fetchInbox, markAlertRead, type AlertItem } from '@/api/notifications';
import { Icon } from '@/design-system/Icon';
import { DUE_SHAPE, DUE_TONE } from '@/design-system/status';
import './alerts.css';

/** Matches the bell, so the two share one poll rather than making two. */
const POLL_MS = 20_000;

/** How long a toast stays before it withdraws itself. */
const DWELL_MS = 12_000;

/**
 * At most this many on screen at once. A vessel's nightly scan can raise
 * thirty-nine alerts in one second; thirty-nine toasts would cover the screen
 * and be dismissed as a fault rather than read as news.
 */
const MAX_VISIBLE = 3;

/**
 * Alerts that announce themselves, instead of waiting in the bell (NOT-12).
 *
 * <p>The bell is a good inbox and a poor announcement: it only speaks when it
 * is clicked, so a superintendent working in another part of the console
 * learns that a unit went overdue whenever they next happen to look. These
 * toasts carry the same alerts to the same person, unprompted.
 *
 * <h2>What is deliberately not shown</h2>
 * <b>The backlog.</b> On first load every unread alert is marked as already
 * seen and nothing is raised. Toasts are for what arrives <em>while you are
 * here</em>; replaying a week of alerts at sign-in is how people learn to
 * close them without reading. The bell still carries the full count.
 *
 * <p><b>The flood.</b> Beyond {@link MAX_VISIBLE} the rest are summarised as a
 * line pointing at the bell, so one nightly scan cannot bury the screen.
 *
 * <p>Maintenance alerts are drawn in the band they announce, so a red alert
 * looks red here and on the equipment list. Colour is not carrying it alone:
 * each keeps the band's shape, as everywhere else in this console.
 */
export function AlertToasts() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [toasts, setToasts] = useState<AlertItem[]>([]);
  const [overflow, setOverflow] = useState(0);

  // null until the first inbox lands: the difference between "nothing has
  // arrived yet" and "nothing was here when I arrived".
  const seen = useRef<Set<number> | null>(null);

  const inbox = useQuery({
    queryKey: ['notifications'],
    queryFn: () => fetchInbox(20),
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
  });

  const items = inbox.data?.items;

  useEffect(() => {
    if (!items) return;

    if (seen.current === null) {
      seen.current = new Set(items.map((i) => i.id));
      return;
    }

    const fresh = items.filter((i) => !seen.current!.has(i.id) && !i.read);
    // Everything in this response is now old news, read or not, so a later
    // poll cannot raise it a second time.
    items.forEach((i) => seen.current!.add(i.id));
    if (fresh.length === 0) return;

    setToasts((prev) => [...fresh, ...prev].slice(0, MAX_VISIBLE));
    if (fresh.length > MAX_VISIBLE) setOverflow((n) => n + fresh.length - MAX_VISIBLE);
  }, [items]);

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const open = useCallback(
    async (item: AlertItem) => {
      dismiss(item.id);
      if (!item.read) {
        await markAlertRead(item.id).catch(() => undefined);
        client.invalidateQueries({ queryKey: ['notifications'] });
      }
      navigate(alertLink(item));
    },
    [client, dismiss, navigate],
  );

  if (toasts.length === 0) return null;

  return (
    <div className="toasts" role="region" aria-label="New alerts">
      {toasts.map((item) => (
        <Toast key={item.id} item={item} onDismiss={dismiss} onOpen={open} />
      ))}
      {overflow > 0 && (
        <p className="toasts__more">
          and {overflow} more — open the bell to read them
        </p>
      )}
    </div>
  );
}

/**
 * One alert, announcing itself.
 *
 * <p>It withdraws on a timer, and the timer stops while the pointer is on it:
 * a notice that vanishes mid-sentence has to be chased into the bell, which is
 * the problem this is here to solve. It is {@code role="status"} rather than
 * {@code alert} so a screen reader finishes what it is saying first.
 */
function Toast({
  item,
  onDismiss,
  onOpen,
}: {
  item: AlertItem;
  onDismiss: (id: number) => void;
  onOpen: (item: AlertItem) => void;
}) {
  const [held, setHeld] = useState(false);

  useEffect(() => {
    if (held) return;
    const timer = setTimeout(() => onDismiss(item.id), DWELL_MS);
    return () => clearTimeout(timer);
  }, [held, item.id, onDismiss]);

  const tone = item.dueStatus ? DUE_TONE[item.dueStatus] : 'signal';
  const shape = item.dueStatus ? DUE_SHAPE[item.dueStatus] : null;

  return (
    <div
      className={`toast toast--${tone}`}
      role="status"
      onMouseEnter={() => setHeld(true)}
      onMouseLeave={() => setHeld(false)}
    >
      <span className="toast__mark" aria-hidden="true">
        {shape ? <span className={`glyph glyph--${shape}`} /> : <Icon name="bell" size={15} />}
      </span>

      <button type="button" className="toast__body" onClick={() => onOpen(item)}>
        <span className="toast__title">{item.title}</span>
        {item.body && <span className="toast__text">{item.body}</span>}
      </button>

      <button
        type="button"
        className="toast__close"
        onClick={() => onDismiss(item.id)}
        aria-label="Dismiss this alert"
      >
        <Icon name="close" size={14} />
      </button>
    </div>
  );
}
