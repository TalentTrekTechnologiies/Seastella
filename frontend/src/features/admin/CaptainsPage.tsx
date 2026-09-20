import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchAccounts, fetchAdminVessels, type AdminVessel, type LinkSent } from '@/api/admin';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { AccountLine, LinkSentDialog, useAccountActions } from './AdminParts';
import { AssignCaptainDialog } from './SetupDialogs';

/**
 * CAPTAINS — Ship Manager (SoW s4.1 step 5).
 *
 * <p>"The Ship Manager, in turn, assigns the Captain to each of their vessels —
 * the last step before that vessel is operational on the platform." Only the
 * vessels allocated to this manager appear, and only Captains who are free or
 * already serving on them.
 */
export function CaptainsPage() {
  const client = useQueryClient();
  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: fetchAccounts });
  const [assigning, setAssigning] = useState<AdminVessel | null>(null);
  const [invited, setInvited] = useState<LinkSent | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['admin-vessels'] });
    client.invalidateQueries({ queryKey: ['accounts'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const actions = useAccountActions(refresh);

  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  const mine = vessels.data ?? [];
  const captains = (accounts.data ?? []).filter((a) => a.role === 'CAPTAIN');
  const vesselName = new Map(mine.map((v) => [v.id, v.name]));
  const withoutCaptain = mine.filter((v) => !v.captain).length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Vessel management' }, { label: 'Captains', strong: true }]}
        title="Captains"
        subtitle="Assign the Captain of each vessel you manage. A vessel is operational on the platform once it has one."
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
        title="My vessels"
        count={mine.length}
        subtitle={withoutCaptain > 0 ? `${withoutCaptain} without a Captain` : 'Every vessel has a Captain'}
        flush
      >
        {vessels.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={3} />
          </div>
        ) : mine.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No vessels allocated to you yet. Your Technical Head allocates them.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {mine.map((v) => (
              <li key={v.id} className="setup-row">
                <div className="setup-row__main">
                  <div className="setup-row__title">{v.name}</div>
                  <div className="setup-row__meta">
                    <span className="mono">IMO {v.imoNumber}</span>
                    {[v.vesselType, v.flag].filter(Boolean).map((x) => ` · ${x}`)}
                  </div>
                  <div className="setup-row__people">
                    {v.captain ? (
                      <span>
                        Captain <b>{v.captain.fullName}</b>
                      </span>
                    ) : (
                      <span className="setup-row__missing">No Captain yet — not operational</span>
                    )}
                  </div>
                </div>
                <div className="setup-row__actions">
                  <Button variant={v.captain ? 'secondary' : 'primary'} onClick={() => setAssigning(v)}>
                    {v.captain ? 'Change Captain' : 'Assign Captain'}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Plate>

      <Plate title="Captain accounts" count={captains.length} subtitle="Captains serving on your vessels, and those not yet assigned" flush>
        {accounts.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={2} />
          </div>
        ) : captains.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No Captain accounts yet. Assign a Captain to a vessel to create one.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {captains.map((c) => (
              <AccountLine
                key={c.id}
                account={c}
                detail={c.vesselIds.length ? `Captain of ${c.vesselIds.map((id) => vesselName.get(id) ?? 'a vessel').join(', ')}` : 'Not assigned to a vessel'}
                canManage={c.vesselIds.length > 0}
                onStatus={actions.onStatus}
                onSendLink={actions.onSendLink}
                onEdit={actions.onEdit}
              />
            ))}
          </ul>
        )}
        <div style={{ padding: '0 20px' }}>
          <FormError message={actions.error} />
        </div>
      </Plate>

      {assigning && (
        <AssignCaptainDialog
          vessel={assigning}
          captains={captains}
          onClose={() => setAssigning(null)}
          onAssigned={() => {
            setNotice(`Captain assigned to ${assigning.name}.`);
            setAssigning(null);
            refresh();
          }}
          onCreated={(c) => {
            setNotice(`${c.user.fullName} is now Captain of ${assigning.name}.`);
            setAssigning(null);
            refresh();
            setInvited(c);
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
