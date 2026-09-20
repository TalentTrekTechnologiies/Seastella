import type { VesselStatus } from '@/api/types';
import { VesselMark } from './VesselMark';
import './sbs.css';

export interface VesselCardData {
  vesselId: number;
  vesselName: string;
  imoNumber: string;
  vesselType?: string | null;
  status: VesselStatus;
  spareCount: number;
  overdue: number;
  dueSoon: number;
  openRequests: number;
  partShortages: number;
}

/**
 * A vessel as an asset: its class, its silhouette, and what it is carrying in
 * overdue work, requests and short stock. Every figure is from the dashboard
 * row; the silhouette comes from the vessel type when the role's payload
 * includes it and falls back to a generic hull when it does not.
 */
export function VesselCard({ vessel, onOpen }: { vessel: VesselCardData; onOpen?: () => void }) {
  const later = Math.max(vessel.spareCount - vessel.overdue - vessel.dueSoon, 0);

  const body = (
    <>
      <div className="vcard__top">
        <div>
          <div className="vcard__name">{vessel.vesselName}</div>
          {vessel.vesselType && <div className="vcard__type">{vessel.vesselType}</div>}
          <div className="vcard__id">IMO {vessel.imoNumber}</div>
        </div>
        <VesselStatusPill status={vessel.status} />
      </div>

      <div className="vcard__ship">
        <VesselMark vesselType={vessel.vesselType ?? null} size="lg" />
      </div>

      <div className="vcard__stats">
        <div className={`vcard__stat${vessel.overdue > 0 ? ' vcard__stat--alert' : ''}`}>
          <b>{vessel.overdue}</b>
          <span>Overdue</span>
        </div>
        <div className={`vcard__stat${vessel.dueSoon > 0 ? ' vcard__stat--warn' : ''}`}>
          <b>{vessel.dueSoon}</b>
          <span>Due soon</span>
        </div>
        <div className="vcard__stat">
          <b>{vessel.openRequests}</b>
          <span>Requests</span>
        </div>
        <div className={`vcard__stat${vessel.partShortages > 0 ? ' vcard__stat--warn' : ''}`}>
          <b>{vessel.partShortages}</b>
          <span>Parts low</span>
        </div>
      </div>

      <div className="vcard__bar" aria-hidden="true">
        <i style={{ flexGrow: vessel.overdue, background: 'var(--c-overdue)' }} />
        <i style={{ flexGrow: vessel.dueSoon, background: 'var(--c-urgent)' }} />
        <i style={{ flexGrow: later, background: 'var(--sbs-line-strong)' }} />
      </div>
      <div className="vcard__barnote">{vessel.spareCount} spares tracked</div>
    </>
  );

  return onOpen ? (
    <button type="button" className="vcard vcard--action" onClick={onOpen}>
      {body}
    </button>
  ) : (
    <div className="vcard">{body}</div>
  );
}

/** Vessel operational status, written out beside its dot. */
export function VesselStatusPill({ status }: { status: VesselStatus }) {
  return (
    <span className="pill">
      <span className={`vessel-dot vessel-dot--${status.toLowerCase()}`} aria-hidden="true" />
      {status === 'DRY_DOCK' ? 'Dry dock' : status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  );
}
