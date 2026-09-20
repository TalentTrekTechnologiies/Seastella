import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchAccounts, fetchOrganizations, type LinkSent, type OrganizationRow } from '@/api/admin';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { ErrorState, LoadingState } from '@/design-system/States';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { Pill } from '@/design-system/StatusBadge';
import { AccountLine, LinkSentDialog, useAccountActions } from './AdminParts';
import { AddOrganizationDialog, AddPersonDialog } from './SetupDialogs';

/**
 * ORGANIZATIONS — Platform Admin (SoW s4.1 steps 1 and 2).
 *
 * <p>Setting up a client starts here: the organization, then its Technical
 * Head. Everything after that — Ship Managers, vessel allocation, Captains —
 * is delegated to the client's own people, so this page stops at the Technical
 * Head on purpose.
 */
export function OrganizationsPage() {
  const client = useQueryClient();
  const orgs = useQuery({ queryKey: ['organizations'], queryFn: fetchOrganizations });
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: fetchAccounts });
  const [adding, setAdding] = useState(false);
  const [headFor, setHeadFor] = useState<OrganizationRow | null>(null);
  const [invited, setInvited] = useState<LinkSent | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['organizations'] });
    client.invalidateQueries({ queryKey: ['accounts'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const actions = useAccountActions(refresh);

  if (orgs.error) return <ErrorState error={orgs.error} onRetry={() => orgs.refetch()} />;

  const list = orgs.data ?? [];
  const heads = (accounts.data ?? []).filter((a) => a.role === 'TECHNICAL_HEAD');
  const orgName = new Map(list.map((o) => [o.id, o.name]));

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Organizations', strong: true }]}
        title="Organizations"
        subtitle="Client companies on the platform. Add the organization, then its Technical Head, who sets up the rest of their fleet."
        actions={
          <Button variant="primary" onClick={() => setAdding(true)}>
            <Icon name="org" size={16} />
            Add organization
          </Button>
        }
      />

      <Plate title="Client organizations" count={list.length} subtitle="With their Technical Head and fleet size" flush>
        {orgs.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={3} />
          </div>
        ) : list.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No organizations yet. Add the first client to begin.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {list.map((o) => (
              <li key={o.id} className="setup-row">
                <div className="setup-row__main">
                  <div className="setup-row__title">
                    {o.name}
                    <Pill size="sm">{o.code}</Pill>
                  </div>
                  <div className="setup-row__meta">{[o.address, o.contactEmail, o.contactPhone].filter(Boolean).join(' · ') || 'No contact details'}</div>
                  <div className="setup-row__people">
                    {o.technicalHeads.length > 0 ? (
                      <span>
                        Technical Head <b>{o.technicalHeads.map((h) => h.fullName).join(', ')}</b>
                      </span>
                    ) : (
                      <span className="setup-row__missing">No Technical Head yet</span>
                    )}
                  </div>
                </div>
                <div className="setup-row__stats">
                  <div className="setup-row__stat">
                    <b>{o.vesselCount}</b>
                    <span>vessels</span>
                  </div>
                  <div className="setup-row__stat">
                    <b>{o.shipManagerCount}</b>
                    <span>ship managers</span>
                  </div>
                </div>
                <div className="setup-row__actions">
                  <Button variant={o.technicalHeads.length ? 'secondary' : 'primary'} onClick={() => setHeadFor(o)}>
                    Add Technical Head
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Plate>

      <Plate title="Technical Heads" count={heads.length} subtitle="Accounts you created; resend invitations, send reset links or suspend here" flush>
        {heads.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No Technical Head accounts yet.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {heads.map((h) => (
              <AccountLine
                key={h.id}
                account={h}
                detail={h.organizationId ? orgName.get(h.organizationId) : undefined}
                canManage
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

      {adding && (
        <AddOrganizationDialog
          onClose={() => setAdding(false)}
          onCreated={(org) => {
            setAdding(false);
            refresh();
            setHeadFor(org);
          }}
        />
      )}
      {headFor && (
        <AddPersonDialog
          title="Add Technical Head"
          subtitle={`${headFor.name} · full-fleet access within this organization`}
          role="TECHNICAL_HEAD"
          organizationId={headFor.id}
          onClose={() => setHeadFor(null)}
          onCreated={(c) => {
            setHeadFor(null);
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
