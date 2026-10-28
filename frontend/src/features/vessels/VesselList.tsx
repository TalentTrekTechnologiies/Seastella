import { Link } from 'react-router-dom';
import type { AdminVessel } from '@/api/admin';
import { EmptyNote } from '@/design-system/Console';
import { Pill } from '@/design-system/StatusBadge';
import '@/features/admin/admin.css';

/**
 * Vessels as rows, each opening its details page - the same facts as the
 * Technical Head's "Vessels & managers" list: name, IMO, type, flag, spares,
 * Ship Manager and Captain.
 */
export function VesselList({
  vessels,
  showOrganization,
  empty,
}: {
  vessels: AdminVessel[];
  showOrganization: boolean;
  empty: string;
}) {
  if (vessels.length === 0) {
    return (
      <div className="qlist__empty">
        <EmptyNote>{empty}</EmptyNote>
      </div>
    );
  }
  return (
    <ul className="setup">
      {vessels.map((v) => (
        <li key={v.id} className="setup-row">
          <div className="setup-row__main">
            <div className="setup-row__title">
              <Link to={`/fleet/vessels/${v.id}`}>{v.name}</Link>
              {v.status !== 'ACTIVE' && <Pill size="sm">{title(v.status)}</Pill>}
            </div>
            <div className="setup-row__meta">
              <span className="mono">IMO {v.imoNumber}</span>
              {[v.vesselType, v.flag].filter(Boolean).map((x) => ` · ${x}`)}
              {` · ${v.spareCount} spares`}
              {showOrganization && v.organizationName && ` · ${v.organizationName}`}
            </div>
            <div className="setup-row__people">
              {v.shipManager ? (
                <span>
                  Ship Manager <b>{v.shipManager.fullName}</b>
                </span>
              ) : (
                <span>No Ship Manager</span>
              )}
              {v.captain ? (
                <span>
                  Captain <b>{v.captain.fullName}</b>
                </span>
              ) : (
                <span>No Captain assigned</span>
              )}
            </div>
          </div>
          <div className="setup-row__actions">
            <Link to={`/fleet/vessels/${v.id}`} className="cbtn cbtn--secondary cbtn--md">
              Open
            </Link>
          </div>
        </li>
      ))}
    </ul>
  );
}

function title(s: string) {
  const words = s.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
