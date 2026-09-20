import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { ActivityItem, DueItem, DueStatus, RadarPoint, VesselHealthRow } from '@/api/types';
import { formatDate, relativeTime } from '@/lib/format';
import { StatusMark } from '@/design-system/Console';
import { VesselMark } from '@/design-system/VesselMark';
import { DUE_LABEL, DUE_SEVERITY, formatDays, worstStatus } from '@/design-system/status';
import './drawer.css';

/**
 * Fleet → vessel drill-down.
 *
 * <p>Composed from data the dashboard response already carried, so opening it
 * costs no request: it narrows what is on screen rather than fetching more,
 * which is the right shape while the per-vessel endpoints are still to come.
 *
 * <p>The equipment fit is the reason this drawer exists. At fleet level the
 * console reads categories across all hulls; here it reads one hull's own
 * fit — every category it carries, the worst service state in each, and how
 * many units sit behind that state.
 *
 * <p>It is also the middle of the drill-down the SoW asks for (DSH-13): fleet →
 * vessel → category → spare → request → conversation. Choosing a category
 * narrows what is below it; a spare opens on the vessel's equipment page at
 * that item; a request opens the request, where its conversation is.
 */
export function VesselDrawer({
  vessel,
  overdue,
  dueSoon,
  points,
  activity,
  onClose,
}: {
  vessel: VesselHealthRow;
  overdue: DueItem[];
  dueSoon: DueItem[];
  points: RadarPoint[];
  activity: ActivityItem[];
  onClose: () => void;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const [category, setCategory] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    closeRef.current?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const fit = useMemo(() => buildFit(points), [points]);

  return (
    <>
      <button type="button" className="drawer-scrim" aria-label="Close" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label={`${vessel.vesselName} detail`}>
        <header className="drawer__head">
          <div className="drawer__ident">
            <VesselMark vesselType={vessel.vesselType} />
            <div>
              <h2 className="drawer__title">{vessel.vesselName}</h2>
              <p className="drawer__sub mono">
                IMO {vessel.imoNumber} · {vessel.vesselType ?? 'Vessel'}
              </p>
            </div>
          </div>
          <button
            type="button"
            className="drawer__close"
            onClick={onClose}
            aria-label="Close"
            ref={closeRef}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </header>

        <div className="drawer__readings">
          <Reading value={vessel.spareCount} label="Spares" />
          <Reading value={vessel.overdue} label="Overdue" tone={vessel.overdue > 0 ? 'alert' : undefined} />
          <Reading value={vessel.dueSoon} label="Due ≤ 15d" tone={vessel.dueSoon > 0 ? 'warn' : undefined} />
          <Reading value={vessel.openRequests} label="Requests" />
          <Reading
            value={vessel.partShortages}
            label="Parts low"
            tone={vessel.partShortages > 0 ? 'warn' : undefined}
          />
        </div>

        <div className="drawer__body">
          <section className="drawer__section">
            <h3 className="drawer__h3">
              Equipment fit <span className="mono">{fit.length}</span>
            </h3>
            {fit.length === 0 ? (
              <p className="readout__idle">No tracked equipment on this vessel.</p>
            ) : (
              <ul className="fitlist">
                {fit.map((f) => (
                  <li key={f.code}>
                    <button
                      type="button"
                      className={`fitlist__pick${category === f.code ? ' fitlist__pick--on' : ''}`}
                      aria-pressed={category === f.code}
                      onClick={() => setCategory(category === f.code ? null : f.code)}
                    >
                      <span className="fitlist__code mono">{f.code.replace(/_/g, ' ')}</span>
                      <span className="fitlist__state">
                        {f.worst && <StatusMark status={f.worst} />}
                        {f.worst ? DUE_LABEL[f.worst] : '—'}
                      </span>
                      <span className="fitlist__count mono">
                        {f.exposed > 0 ? `${f.exposed}/${f.total}` : f.total}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {category && (
            <p className="drawer__filter">
              Showing <b>{category.replace(/_/g, ' ').toLowerCase()}</b> only.{' '}
              <button type="button" className="drawer__clear" onClick={() => setCategory(null)}>
                Show everything
              </button>
            </p>
          )}

          <DueSection
            title="Overdue"
            items={overdue.filter((d) => !category || d.categoryCode === category)}
            vesselId={vessel.vesselId}
            empty={category ? 'Nothing overdue in this category.' : 'Nothing overdue on this vessel.'}
          />
          <DueSection
            title="Due within 15 days"
            items={dueSoon.filter((d) => !category || d.categoryCode === category)}
            vesselId={vessel.vesselId}
            empty={category ? 'Nothing due in this category.' : 'Nothing due in the next fifteen days.'}
          />

          <section className="drawer__section">
            <h3 className="drawer__h3">Recent activity</h3>
            {activity.length === 0 ? (
              <p className="readout__idle">No recorded activity for this vessel.</p>
            ) : (
              <ol className="drawer__log">
                {activity.slice(0, 12).map((a, i) => (
                  <li key={`${a.serviceRequestId}-${i}`}>
                    {/* …and on to the request itself, where its conversation is. */}
                    <Link to={`/requests/${a.serviceRequestId}`} onClick={onClose}>
                      <span className="drawer__log-what">{a.actionLabel}</span>
                      <span className="drawer__log-ref mono">{a.requestNumber}</span>
                    </Link>
                    <time dateTime={a.occurredAt}>{relativeTime(a.occurredAt)}</time>
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

function Reading({ value, label, tone }: { value: number; label: string; tone?: 'alert' | 'warn' }) {
  return (
    <div className={`drawer__reading${tone ? ` drawer__reading--${tone}` : ''}`}>
      <b className="tnum">{value}</b>
      <span>{label}</span>
    </div>
  );
}

function DueSection({
  title,
  items,
  vesselId,
  empty,
}: {
  title: string;
  items: DueItem[];
  vesselId: number;
  empty: string;
}) {
  return (
    <section className="drawer__section">
      <h3 className="drawer__h3">
        {title} <span className="mono">{items.length}</span>
      </h3>
      {items.length === 0 ? (
        <p className="readout__idle">{empty}</p>
      ) : (
        <ul className="drawer__list">
          {items.map((d) => (
            <li key={d.spareId}>
              {/* The spare, opened where it lives in the tree (DSH-13). */}
              <Link to={`/fleet/vessels/${vesselId}?spare=${d.spareId}`}>
                <span className="drawer__spare">{d.spareName}</span>
                <span className="drawer__spare-meta mono">
                  {d.sparePath} · {d.categoryCode.replace(/_/g, ' ')} · due {formatDate(d.nextDueDate)}
                </span>
              </Link>
              <span className="drawer__due">
                <StatusMark status={d.status} />
                <b className="mono">{formatDays(d.daysRemaining)}</b>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface FitRow {
  code: string;
  total: number;
  exposed: number;
  worst: DueStatus | null;
}

function buildFit(points: RadarPoint[]): FitRow[] {
  const exposedStates: DueStatus[] = ['OVERDUE', 'DUE', 'URGENT'];
  const byCat = new Map<string, { statuses: DueStatus[]; exposed: number }>();

  for (const p of points) {
    const entry = byCat.get(p.categoryCode) ?? { statuses: [], exposed: 0 };
    entry.statuses.push(p.status);
    if (exposedStates.includes(p.status)) entry.exposed += 1;
    byCat.set(p.categoryCode, entry);
  }

  return [...byCat.entries()]
    .map(([code, e]) => ({
      code,
      total: e.statuses.length,
      exposed: e.exposed,
      worst: worstStatus(e.statuses),
    }))
    .sort(
      (a, b) =>
        (b.worst ? DUE_SEVERITY[b.worst] : 0) - (a.worst ? DUE_SEVERITY[a.worst] : 0) ||
        b.exposed - a.exposed ||
        a.code.localeCompare(b.code),
    );
}
