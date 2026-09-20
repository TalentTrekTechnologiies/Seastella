import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAccount,
  fetchAccounts,
  fetchOrganizations,
  type AccountSummary,
  type LinkSent,
} from '@/api/admin';
import type { Role } from '@/api/types';
import { Button, ConsoleHeader, EmptyNote, Plate, Segmented, roleName } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { AccountLine, LinkSentDialog, errorText, useAccountActions } from '@/features/admin/AdminParts';

type Filter = 'ALL' | 'SEASTELLA' | 'CLIENT';

/**
 * Everyone on the platform, and the only place Seastella's own staff are
 * created (SoW §8.5 "organizations, users and vessels overview").
 *
 * <p>Client-side accounts are made where they belong — a Technical Head is
 * created with its organization, a Captain with its vessel — because §4.1 makes
 * each of those a step in standing a client up. Seastella's own Service
 * Coordinators and Service Engineers belong to no client at all, so they have
 * nowhere else to be created, and without this screen they could only ever
 * arrive through seed data.
 *
 * <p>A Coordinator is scoped by the client organizations they serve (OI-16); an
 * Engineer by the jobs they are assigned, and nothing else.
 */
export function UsersPage() {
  const client = useQueryClient();
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: fetchAccounts });
  const organizations = useQuery({ queryKey: ['organizations'], queryFn: fetchOrganizations });
  const [filter, setFilter] = useState<Filter>('ALL');
  const [adding, setAdding] = useState<'SERVICE_COORDINATOR' | 'SERVICE_ENGINEER' | null>(null);
  const [sent, setSent] = useState<LinkSent | null>(null);

  const refresh = () => client.invalidateQueries({ queryKey: ['accounts'] });
  const actions = useAccountActions(refresh);

  const orgName = useMemo(
    () => new Map((organizations.data ?? []).map((o) => [o.id, o.name])),
    [organizations.data],
  );

  if (accounts.error) return <ErrorState error={accounts.error} onRetry={() => accounts.refetch()} />;

  const all = accounts.data ?? [];
  const isSeastella = (a: AccountSummary) =>
    a.role === 'PLATFORM_ADMIN' || a.role === 'SERVICE_COORDINATOR' || a.role === 'SERVICE_ENGINEER';
  const shown = all.filter((a) =>
    filter === 'ALL' ? true : filter === 'SEASTELLA' ? isSeastella(a) : !isSeastella(a),
  );

  const byRole = new Map<Role, AccountSummary[]>();
  for (const account of shown) {
    const list = byRole.get(account.role) ?? [];
    list.push(account);
    byRole.set(account.role, list);
  }

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Users & roles', strong: true }]}
        title="Users and roles"
        subtitle="Everyone on the platform. Client accounts are created where they belong — with their organization or vessel; Seastella's own staff are created here."
        actions={
          <>
            <Button onClick={() => setAdding('SERVICE_COORDINATOR')}>Add Coordinator</Button>
            <Button variant="primary" onClick={() => setAdding('SERVICE_ENGINEER')}>
              Add Engineer
            </Button>
          </>
        }
      />

      <Plate
        title="Accounts"
        count={shown.length}
        subtitle="Suspending an account signs that person out at once; their records stay as they are"
        action={
          <Segmented<Filter>
            label="Show"
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'ALL', label: 'Everyone', count: all.length },
              { value: 'SEASTELLA', label: 'Seastella', count: all.filter(isSeastella).length },
              { value: 'CLIENT', label: 'Client fleets', count: all.filter((a) => !isSeastella(a)).length },
            ]}
          />
        }
        flush
      >
        {accounts.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={8} />
          </div>
        ) : shown.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No accounts match that filter.</EmptyNote>
          </div>
        ) : (
          [...byRole.entries()].map(([role, list]) => (
            <div key={role} className="userrole">
              <div className="userrole__head">
                {roleName(role)} <span className="mono">{list.length}</span>
              </div>
              <ul className="setup">
                {list.map((account) => (
                  <AccountLine
                    key={account.id}
                    account={account}
                    canManage
                    detail={detailFor(account, orgName)}
                    onStatus={actions.onStatus}
                    onSendLink={actions.onSendLink}
                    onEdit={actions.onEdit}
                  />
                ))}
              </ul>
            </div>
          ))
        )}
      </Plate>

      {actions.dialogs}
      <FormError message={actions.error} />

      {adding && (
        <AddStaffDialog
          role={adding}
          organizations={(organizations.data ?? []).map((o) => ({ id: o.id, name: o.name }))}
          onClose={() => setAdding(null)}
          onCreated={(result) => {
            setAdding(null);
            setSent(result);
            refresh();
          }}
        />
      )}
      {sent && <LinkSentDialog result={sent} kind="invitation" onClose={() => setSent(null)} />}
    </div>
  );
}

/** What this account is scoped to, in the words that role would use. */
function detailFor(account: AccountSummary, orgName: Map<number, string>) {
  if (account.role === 'SERVICE_ENGINEER') return 'Sees only the jobs assigned to them';
  if (account.role === 'PLATFORM_ADMIN') return 'Every organization and vessel';
  if (account.organizationId) {
    const name = orgName.get(account.organizationId) ?? `Organization ${account.organizationId}`;
    return account.vesselIds.length > 0 ? `${name} · ${account.vesselIds.length} vessel(s)` : name;
  }
  return account.role === 'SERVICE_COORDINATOR' ? 'Serves the organizations assigned to them' : undefined;
}

/**
 * Creating Seastella's own staff.
 *
 * <p>Neither role belongs to a client organization — the database says so — so
 * there is no organization field. A Coordinator is instead given the client
 * organizations they serve, which is the whole of their scope (OI-16). An
 * Engineer is given nothing: their assigned jobs are their boundary.
 */
function AddStaffDialog({
  role,
  organizations,
  onClose,
  onCreated,
}: {
  role: 'SERVICE_COORDINATOR' | 'SERVICE_ENGINEER';
  organizations: { id: number; name: string }[];
  onClose: () => void;
  onCreated: (result: LinkSent) => void;
}) {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [organizationIds, setOrganizationIds] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const coordinator = role === 'SERVICE_COORDINATOR';
  const valid = fullName.trim() !== '' && email.trim() !== '' && (!coordinator || organizationIds.length > 0);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await createAccount({
          fullName: fullName.trim(),
          email: email.trim(),
          role,
          organizationIds: coordinator ? organizationIds : undefined,
        }),
      );
    } catch (e) {
      setError(errorText(e, 'The account could not be created.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={coordinator ? 'Add a Service Coordinator' : 'Add a Service Engineer'}
      subtitle="Seastella staff — they belong to no client organization"
      onClose={onClose}
      width={520}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={save}>
            {busy ? 'Creating…' : 'Create and invite'}
          </Button>
        </>
      }
    >
      <Field label="Full name" htmlFor="staff-name">
        <input id="staff-name" className="input" maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
      </Field>
      <Field label="Email" htmlFor="staff-email" hint="Their invitation goes here; they choose their own password.">
        <input id="staff-email" className="input" type="email" maxLength={200} value={email} onChange={(e) => setEmail(e.target.value)} />
      </Field>

      {coordinator ? (
        <Field
          label="Client organizations they serve"
          htmlFor="staff-orgs"
          hint="Their entire scope. A Coordinator sees nothing outside these."
        >
          <ul className="orgpick" id="staff-orgs">
            {organizations.map((org) => (
              <li key={org.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={organizationIds.includes(org.id)}
                    onChange={(e) =>
                      setOrganizationIds((was) =>
                        e.target.checked ? [...was, org.id] : was.filter((id) => id !== org.id),
                      )
                    }
                  />
                  {org.name}
                </label>
              </li>
            ))}
          </ul>
        </Field>
      ) : (
        <p className="otp__note">
          An Engineer is scoped by the jobs they are assigned — nothing else. They reach a vessel only through a job,
          and see no fleet, no vessel list and no costs.
        </p>
      )}
      <FormError message={error} />
    </Dialog>
  );
}
