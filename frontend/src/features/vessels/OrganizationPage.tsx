import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchAdminVessels, fetchOrganizations } from '@/api/admin';
import { ConsoleHeader, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { VesselList } from './VesselList';
import './vessels.css';

/** One client organization and its fleet, opened from the Organizations list. */
export function OrganizationPage() {
  const organizationId = Number(useParams().organizationId);
  const organizations = useQuery({ queryKey: ['organizations'], queryFn: fetchOrganizations });
  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });

  if (organizations.error) return <ErrorState error={organizations.error} onRetry={() => organizations.refetch()} />;
  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  const org = organizations.data?.find((o) => o.id === organizationId);
  const fleet = (vessels.data ?? []).filter((v) => v.organizationId === organizationId);

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: <Link to="/organizations">Organizations</Link> }, { label: org?.name ?? 'Organization', strong: true }]}
        title={org?.name ?? 'Organization'}
        subtitle={
          org
            ? [org.address, org.contactEmail, org.contactPhone].filter(Boolean).join(' · ') || 'No contact details'
            : undefined
        }
        badge={org ? <Pill size="sm">{org.code}</Pill> : undefined}
      />
      {org && (
        <div className="vfacts">
          <span>
            Technical Head{' '}
            <b>{org.technicalHeads.length ? org.technicalHeads.map((h) => h.fullName).join(', ') : 'not yet added'}</b>
          </span>
          <span>
            <b>{org.vesselCount}</b> vessels · <b>{org.shipManagerCount}</b> ship managers
          </span>
        </div>
      )}
      <Plate title="Vessels" count={fleet.length} subtitle="Open a vessel for its particulars, equipment, certificates and documents" flush>
        {vessels.isLoading || organizations.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={4} />
          </div>
        ) : (
          <VesselList vessels={fleet} showOrganization={false} empty="This organization has no vessels yet." />
        )}
      </Plate>
    </div>
  );
}
