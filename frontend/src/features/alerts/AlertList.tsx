import { Link } from 'react-router-dom';
import { alertLink, type AlertItem } from '@/api/notifications';
import { StatusMark } from '@/design-system/Console';
import { Icon } from '@/design-system/Icon';
import { DUE_LABEL } from '@/design-system/status';
import { relativeTime } from '@/lib/format';
import './alerts.css';

/**
 * Alerts as a list: what happened, where, and whether it is the reader's move.
 *
 * <p>Maintenance alerts carry the reserved due-status mark (colour and shape),
 * because they announce exactly that status. Workflow alerts do not use those
 * colours — "your move" is marked in the signal colour and in words.
 */
export function AlertList({
  items,
  onOpen,
  compact = false,
}: {
  items: AlertItem[];
  onOpen?: (item: AlertItem) => void;
  compact?: boolean;
}) {
  return (
    <ul className={`alerts${compact ? ' alerts--compact' : ''}`}>
      {items.map((item) => (
        <li key={item.id}>
          <Link
            to={alertLink(item)}
            className={`alert${item.read ? '' : ' alert--unread'}`}
            onClick={() => onOpen?.(item)}
          >
            <span className={`alert__icon alert__icon--${item.category.toLowerCase()}`} aria-hidden="true">
              {item.category === 'MAINTENANCE' && item.dueStatus ? (
                <StatusMark status={item.dueStatus} size={12} />
              ) : (
                <Icon name={item.category === 'ACTION' ? 'bell' : 'check'} size={15} />
              )}
            </span>
            <span className="alert__main">
              <span className="alert__top">
                <span className="alert__title">{item.title}</span>
                {!item.read && <span className="sr-only">Unread.</span>}
              </span>
              {item.body && <span className="alert__body">{item.body}</span>}
              <span className="alert__meta">
                {item.category === 'ACTION' && <span className="alert__tag">Your move</span>}
                {item.category === 'MAINTENANCE' && item.dueStatus && (
                  <span className="alert__tag alert__tag--plain">{DUE_LABEL[item.dueStatus]}</span>
                )}
                <span>{relativeTime(item.createdAt)}</span>
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
