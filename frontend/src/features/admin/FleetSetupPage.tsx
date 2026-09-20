import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchAccounts, fetchAdminVessels, type AccountSummary, type LinkSent } from '@/api/admin';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { Pill } from '@/design-system/StatusBadge';
import { AccountLine, LinkSentDialog, useAccountActions } from './AdminParts';
import { AddPersonDialog, AddVesselDialog, AllocateVesselsDialog } from './SetupDialogs';

/**
 * VESSELS & SHIP MANAGERS — Technical Head (SoW s4.1 steps 3 and 4).
 *
 * <p>The Technical Head adds vessels to their fleet, creates Ship Managers and
 * decides which vessels each is responsible for. Captains are not assigned
 * here: that is the Ship Manager's step, and this page shows only whether it
 * has happened.
 */
export function FleetSetupPage() {
  const client = useQueryClient();
  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: fetchAccounts });
  const [addingVessel, setAddingVessel] = useState(false);
  const [addingManager, setAddingManager] = useState(false);
  const [allocating, setAllocating] = useState<AccountSummary | null>(null);
  const [invited, setInvited] = useState<LinkSent | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['admin-vessels'] });
    client.invalidateQueries({ queryKey: ['accounts'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const actions = useAccountActions(refresh);

  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  const fleet = vessels.data ?? [];
  const managers = (accounts.data ?? []).filter((a) => a.role === 'SHIP_MANAGER');
  const vesselName = new Map(fleet.map((v) => [v.id, v.name]));
  const unallocated = fleet.filter((v) => !v.shipManager).length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Fleet setup' }, { label: `${fleet.length} ${fleet.length === 1 ? 'vessel' : 'vessels'}`, strong: true }]}
        title="Vessels & ship managers"
        subtitle="Add vessels to the fleet, create Ship Managers and decide which vessels each one is responsible for."
        actions={
          <>
            <Button onClick={() => setAddingManager(true)}>
              <Icon name="users" size={16} />
              Add Ship Manager
            </Button>
            <Button variant="primary" onClick={() => setAddingVessel(true)}>
              <Icon name="ship" size={16} />
              Add vessel
            </Button>
          </>
        }
      />

      {notice && (
        <div className="notice notice--ok" role="status">
          <Icon name="check" size={20} />
          <div>
            <p className="notice__title">{notice}</p>
          </div>
        </div>
      )}

      <Plate
        title="Vessels"
        count={fleet.length}
        subtitle={unallocated > 0 ? `${unallocated} not yet allocated to a Ship Manager` : 'Every vessel has a responsible Ship Manager'}
        flush
      >
        {vessels.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={4} />
          </div>
        ) : fleet.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No vessels yet. Add the first vessel of the fleet.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {fleet.map((v) => (
              <li key={v.id} className="setup-row">
                <div className="setup-row__main">
                  <div className="setup-row__title">
                    {v.name}
                    {v.status !== 'ACTIVE' && <Pill size="sm">{title(v.status)}</Pill>}
                  </div>
                  <div className="setup-row__meta">
                    <span className="mono">IMO {v.imoNumber}</span>
                    {[v.vesselType, v.flag].filter(Boolean).map((x) => ` · ${x}`)}
                    {` · ${v.spareCount} spares`}
                  </div>
                  <div className="setup-row__people">
                    {v.shipManager ? (
                      <span>
                        Ship Manager <b>{v.shipManager.fullName}</b>
                      </span>
                    ) : (
                      <span className="setup-row__missing">Not allocated to a Ship Manager</span>
                    )}
                    {v.captain ? (
                      <span>
                        Captain <b>{v.captain.fullName}</b>
                      </span>
                    ) : (
                      <span>No Captain assigned yet</span>
                    )}
                  </div>
                </div>
                <div className="setup-row__actions">
                  <Link to={`/fleet/vessels/${v.id}`} className="cbtn cbtn--secondary cbtn--md">
                    Equipment
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Plate>

      <Plate title="Ship managers" count={managers.length} subtitle="Each is responsible for the vessels allocated to them" flush>
        {accounts.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={3} />
          </div>
        ) : managers.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No Ship Managers yet. Create one, then allocate vessels to them.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {managers.map((m) => (
              <AccountLine
                key={m.id}
                account={m}
                detail={
                  m.vesselIds.length
                    ? `Responsible for ${m.vesselIds.map((id) => vesselName.get(id) ?? `vessel ${id}`).join(', ')}`
                    : 'No vessels allocated yet'
                }
                canManage
                onStatus={actions.onStatus}
                onSendLink={actions.onSendLink}
                onEdit={actions.onEdit}
                extra={
                  <Button variant="secondary" onClick={() => setAllocating(m)}>
                    Allocate vessels
                  </Button>
                }
              />
            ))}
          </ul>
        )}
        <div style={{ padding: '0 20px' }}>
          <FormError message={actions.error} />
        </div>
      </Plate>

      {addingVessel && (
        <AddVesselDialog
          onClose={() => setAddingVessel(false)}
          onCreated={(v) => {
            setAddingVessel(false);
            setNotice(`${v.name} added with ${v.spareCount} spares from the standard bridge fit. Allocate it to a Ship Manager next.`);
            refresh();
          }}
        />
      )}
      {addingManager && (
        <AddPersonDialog
          title="Add Ship Manager"
          subtitle="Responsible for the vessels you allocate"
          role="SHIP_MANAGER"
          vessels={fleet}
          vesselHint="Optional now; you can allocate vessels later. A vessel with someone else moves to this manager."
          onClose={() => setAddingManager(false)}
          onCreated={(c) => {
            setAddingManager(false);
            refresh();
            setInvited(c);
          }}
        />
      )}
      {allocating && (
        <AllocateVesselsDialog
          manager={allocating}
          vessels={fleet}
          onClose={() => setAllocating(null)}
          onSaved={() => {
            setNotice(`Vessel allocation saved for ${allocating.fullName}.`);
            setAllocating(null);
            refresh();
          }}
        />
      )}
      {invited && (
        <LinkSentDialog result={invited} kind="invitation" onClose={() => setInvited(null)} />
      )}
      {actions.dialogs}
    </div>
  );
}

function title(s: string) {
  return (s.charAt(0) + s.slice(1).toLowerCase()).replace('_', ' ');
}
