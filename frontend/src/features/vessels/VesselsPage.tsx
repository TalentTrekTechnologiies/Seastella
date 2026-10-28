import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchAdminVessels } from '@/api/admin';
import { useAuth } from '@/app/AuthContext';
import { ConsoleHeader, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { VesselList } from './VesselList';
import './vessels.css';

/**
 * Every vessel the caller may open: the whole platform for the Platform
 * Administrator, the organizations they serve for a Service Coordinator.
 * Searchable, and filtered by organization from the URL so the overview's
 * organization cards and the organization pages can link straight here.
 */
export function VesselsPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });
  const search = params.get('q') ?? '';
  const organization = Number(params.get('organization')) || undefined;

  const organizations = useMemo(() => {
    const byId = new Map<number, string>();
    for (const v of vessels.data ?? []) byId.set(v.organizationId, v.organizationName ?? `Organization ${v.organizationId}`);
    return [...byId.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [vessels.data]);

  const shown = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (vessels.data ?? []).filter(
      (v) =>
        (!organization || v.organizationId === organization) &&
        (!term ||
          v.name.toLowerCase().includes(term) ||
          v.imoNumber.includes(term) ||
          (v.organizationName ?? '').toLowerCase().includes(term)),
    );
  }, [vessels.data, search, organization]);

  const set = (key: string, value?: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  const coordinator = user?.role === 'SERVICE_COORDINATOR';

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: coordinator ? 'Service operations' : 'Platform administration' }, { label: 'Vessels', strong: true }]}
        title="Vessels"
        subtitle={
          coordinator
            ? 'Every vessel of the organizations you serve. Open one for its particulars, equipment and pre-survey form.'
            : 'Every vessel on the platform. Open one for its particulars, equipment, certificates and documents.'
        }
      />
      <Plate
        title="Fleet"
        count={shown.length}
        subtitle={organization ? organizations.find(([id]) => id === organization)?.[1] : 'All organizations'}
        action={
          <div className="vfilter">
            <input
              className="input"
              type="search"
              placeholder="Search name, IMO or organization"
              aria-label="Search vessels"
              value={search}
              onChange={(e) => set('q', e.target.value)}
            />
            {organizations.length > 1 && (
              <select
                className="input"
                aria-label="Organization"
                value={organization ?? ''}
                onChange={(e) => set('organization', e.target.value || undefined)}
              >
                <option value="">All organizations</option>
                {organizations.map(([id, name]) => (
                  <option key={id} value={id}>
                    {name}
                  </option>
                ))}
              </select>
            )}
          </div>
        }
        flush
      >
        {vessels.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={5} />
          </div>
        ) : (
          <VesselList
            vessels={shown}
            showOrganization={!organization}
            empty={search || organization ? 'No vessel matches.' : 'No vessels yet.'}
          />
        )}
      </Plate>
    </div>
  );
}
