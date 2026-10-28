import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { deleteOrganization, fetchOrganizationRemoval } from '@/api/admin';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { errorText } from './AdminParts';

/**
 * Deleting a client organization - its vessels with everything on them, and
 * its own people. Shows what would go before anything is touched, and asks for
 * the organization's name, as deleting a vessel does. Thawe Marine's
 * Coordinators and Engineers stay.
 */
export function DeleteOrganizationDialog({
  organization,
  onClose,
  onDeleted,
}: {
  organization: { id: number; name: string; code: string };
  onClose: () => void;
  onDeleted: () => void;
}) {
  const impact = useQuery({
    queryKey: ['organization-removal', organization.id],
    queryFn: () => fetchOrganizationRemoval(organization.id),
  });
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const matches = typed.trim().toLowerCase() === organization.name.trim().toLowerCase();
  const i = impact.data;

  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await deleteOrganization(organization.id, typed.trim());
      onDeleted();
    } catch (e) {
      setError(errorText(e, 'The organization could not be deleted.'));
      setBusy(false);
    }
  };

  const line = (n: number, one: string, many: string) => (n > 0 ? <li>{n} {n === 1 ? one : many}</li> : null);

  return (
    <Dialog
      title={`Delete ${organization.name}`}
      subtitle={`${organization.code} · this cannot be undone`}
      onClose={onClose}
      width={580}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy || !matches || !i} onClick={remove}>
            {busy ? 'Deleting…' : 'Delete organization and everything in it'}
          </Button>
        </>
      }
    >
      {impact.isLoading ? (
        <p className="otp__note">Working out what would be removed…</p>
      ) : i ? (
        <>
          <p className="otp__note">
            <b>Everything belonging to this organization is deleted</b>
            {i.vessels + i.people === 0 ? ' — it has nothing recorded yet.' : ':'}
          </p>
          <ul className="delete-list">
            {line(i.vessels, `vessel (${i.vesselNames.join(', ')})`, `vessels (${i.vesselNames.join(', ')})`)}
            {line(i.equipment, 'equipment item', 'equipment items')}
            {line(i.serviceRequests, 'service request, with its checks, chats and reports', 'service requests, with their checks, chats and reports')}
            {line(i.invoices, 'invoice and its payments', 'invoices and their payments')}
            {line(i.documents, 'document and its file', 'documents and their files')}
            {line(i.people, 'account (Technical Head, Ship Manager or Captain)', 'accounts (Technical Heads, Ship Managers and Captains)')}
          </ul>
          {i.coordinators > 0 && (
            <p className="otp__note">
              {i.coordinators === 1 ? 'The Service Coordinator' : `The ${i.coordinators} Service Coordinators`} serving it
              keep their accounts and simply stop serving it.
            </p>
          )}
          <p className="otp__note">The audit trail keeps a record that the organization was deleted, by whom and when.</p>
        </>
      ) : null}
      <Field label={`Type ${organization.name} to confirm`} htmlFor="delete-org-name">
        <input
          id="delete-org-name"
          className="input"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          autoComplete="off"
          placeholder={organization.name}
        />
      </Field>
      <FormError message={error ?? (impact.error ? errorText(impact.error, 'Could not read what would be removed.') : null)} />
    </Dialog>
  );
}
