import { useEffect } from 'react';
import type { ActivityItem, DueItem, VesselHealthRow } from '@/api/types';
import { formatDate, relativeTime } from '@/lib/format';
import { DueStatusBadge, VesselStatusChip } from '@/design-system/StatusBadge';
import { DefinitionList } from '@/design-system/Panel';
import { EmptyState } from '@/design-system/States';
import './drawer.css';

/**
 * Fleet → vessel drill-down.
 *
 * <p>Composed from data the dashboard response already carried, so opening it
 * costs no request. It narrows what is on screen rather than fetching more,
 * which is the right shape while the per-vessel endpoints are still to come.
 */
export function VesselDrawer({
  vessel,
  overdue,
  dueSoon,
  activity,
  onClose,
}: {
  vessel: VesselHealthRow;
  overdue: DueItem[];
  dueSoon: DueItem[];
  activity: ActivityItem[];
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <>
      <button type="button" className="drawer-scrim" aria-label="Close" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label={`${vessel.vesselName} detail`}>
        <header className="drawer__head">
          <div>
            <h2 className="drawer__title">{vessel.vesselName}</h2>
            <p className="drawer__sub mono">IMO {vessel.imoNumber}</p>
          </div>
          <button type="button" className="drawer__close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>

        <div className="drawer__body">
          <DefinitionList
            items={[
              { label: 'Status', value: <VesselStatusChip value={vessel.status} /> },
              { label: 'Type', value: vessel.vesselType ?? '—' },
              { label: 'Spares', value: vessel.spareCount, mono: true },
              { label: 'Open requests', value: vessel.openRequests, mono: true },
              { label: 'Low stock parts', value: vessel.partShortages, mono: true },
            ]}
          />

          <section className="drawer__section">
            <h3>Overdue ({overdue.length})</h3>
            {overdue.length === 0 ? (
              <EmptyState title="Nothing overdue on this vessel" />
            ) : (
              <ul className="drawer__list">
                {overdue.map((d) => (
                  <li key={d.spareId}>
                    <div>
                      <span className="drawer__spare">{d.spareName}</span>
                      <span className="cell-sub mono">
                        {d.sparePath} · due {formatDate(d.nextDueDate)}
                      </span>
                    </div>
                    <DueStatusBadge status={d.status} daysRemaining={d.daysRemaining} size="sm" />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="drawer__section">
            <h3>Due soon ({dueSoon.length})</h3>
            {dueSoon.length === 0 ? (
              <EmptyState title="Nothing due in the next fifteen days" />
            ) : (
              <ul className="drawer__list">
                {dueSoon.map((d) => (
                  <li key={d.spareId}>
                    <div>
                      <span className="drawer__spare">{d.spareName}</span>
                      <span className="cell-sub mono">
                        {d.sparePath} · due {formatDate(d.nextDueDate)}
                      </span>
                    </div>
                    <DueStatusBadge status={d.status} daysRemaining={d.daysRemaining} size="sm" />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="drawer__section">
            <h3>Recent activity</h3>
            {activity.length === 0 ? (
              <EmptyState title="No recent activity" icon="inbox" />
            ) : (
              <ol className="drawer__activity">
                {activity.slice(0, 12).map((a, i) => (
                  <li key={`${a.serviceRequestId}-${i}`}>
                    <span>{a.actionLabel}</span>
                    <span className="cell-sub mono">{a.requestNumber}</span>
                    <time>{relativeTime(a.occurredAt)}</time>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      </aside>
    </>
  );
}
