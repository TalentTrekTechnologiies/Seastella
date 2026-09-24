import { useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchVesselMaintenance, updateSpare, type SpareDetails, type SpareDue } from '@/api/admin';
import { fetchVesselFit, type SpareNode } from '@/api/serviceRequests';
import { Button, ConsoleHeader, Plate, StatusMark } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { CriticalityChip } from '@/design-system/StatusBadge';
import { formatDays } from '@/design-system/status';
import { formatDate } from '@/lib/format';
import { errorText } from './AdminParts';
import { DocumentsPanel } from '@/features/documents/DocumentsPanel';
import { CriticalSparesPanel } from '@/features/parts/CriticalSparesPanel';
import { SpareTree } from '@/features/spares/SpareTree';
import { ServiceHistoryPanel } from './ServiceHistoryPanel';
import { AddSpareDialog } from './AddSpareDialog';
import { Dialog as DocumentsDialog } from '@/design-system/Dialog';

/**
 * A vessel's equipment, as the Technical Head maintains it (SoW §9.3).
 *
 * <p>A vessel added with the standard fit starts with the structure only. This
 * is where its own facts go in — make, model, serial, dates — and where
 * entering the last annual service date brings a spare under maintenance
 * tracking, with its due status shown straight back.
 */
export function VesselEquipmentPage() {
  const vesselId = Number(useParams().vesselId);
  // Arrived from the fleet drawer on one spare (DSH-13).
  const focusSpare = Number(useSearchParams()[0].get('spare')) || undefined;
  const client = useQueryClient();
  const fit = useQuery({ queryKey: ['vessel-fit', vesselId], queryFn: () => fetchVesselFit(vesselId), enabled: Number.isFinite(vesselId) });
  const due = useQuery({ queryKey: ['vessel-maintenance', vesselId], queryFn: () => fetchVesselMaintenance(vesselId), enabled: Number.isFinite(vesselId) });
  const [editing, setEditing] = useState<SpareNode | null>(null);
  const [documentsFor, setDocumentsFor] = useState<SpareNode | null>(null);
  const [historyFor, setHistoryFor] = useState<SpareNode | null>(null);
  const [addingUnder, setAddingUnder] = useState<SpareNode | null | undefined>(undefined);
  const [notice, setNotice] = useState<string | null>(null);

  const dueBySpare = useMemo(() => new Map((due.data ?? []).map((d) => [d.spareId, d])), [due.data]);

  if (fit.error) return <ErrorState error={fit.error} onRetry={() => fit.refetch()} />;

  const spares = fit.data?.spares ?? [];
  const withDetails = spares.filter((s) => s.make || s.model || s.serialNumber).length;
  const tracked = spares.filter((s) => dueBySpare.has(s.id)).length;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: <Link to="/fleet/setup">Vessels &amp; managers</Link> }, { label: fit.data?.vesselName ?? 'Vessel', strong: true }]}
        title={`${fit.data?.vesselName ?? 'Vessel'} equipment`}
        subtitle="The vessel's navigational equipment in VMP order. Record each item's details and last annual service to bring it under maintenance tracking."
      />

      {notice && (
        <div className="notice notice--ok" role="status">
          <div>
            <p className="notice__title">{notice}</p>
          </div>
        </div>
      )}

      <Plate
        title="Equipment"
        count={spares.length}
        subtitle={`${withDetails} with details recorded · ${tracked} under maintenance tracking`}
        action={
          <Button variant="primary" onClick={() => setAddingUnder(null)}>
            Add equipment
          </Button>
        }
        flush
      >
        {fit.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={6} />
          </div>
        ) : (
          <SpareTree
            spares={spares}
            focusId={focusSpare}
            emptyNote="This vessel has no equipment recorded."
            meta={(spare) => <EquipmentMeta spare={spare} due={dueBySpare.get(spare.id)} />}
            actions={(spare) => (
              <>
                {/* The history is what a surveyor and an auditor ask for. */}
                <Button variant="ghost" onClick={() => setHistoryFor(spare)}>
                  Service history
                </Button>
                <Button variant="ghost" onClick={() => setAddingUnder(spare)}>
                  Add spare
                </Button>
                <Button variant="ghost" onClick={() => setDocumentsFor(spare)}>
                  Documents
                </Button>
                <Button onClick={() => setEditing(spare)}>Edit details</Button>
              </>
            )}
          />
        )}
      </Plate>

      {/* The client's own minimum-spares form (GM 2.3.9.9), per vessel. */}
      <CriticalSparesPanel vesselId={vesselId} equipment={spares} canManage />

      <Plate
        title="Vessel documents"
        subtitle="Certificates and paperwork that belong to the vessel rather than to one piece of equipment"
      >
        <DocumentsPanel ownerType="VESSEL" ownerId={vesselId} ownerName={fit.data?.vesselName ?? 'this vessel'} canAttach />
      </Plate>

      {historyFor && (
        <DocumentsDialog
          title="Service history"
          subtitle={`${historyFor.path} · ${historyFor.name}`}
          onClose={() => setHistoryFor(null)}
          width={820}
        >
          <ServiceHistoryPanel
            spareId={historyFor.id}
            spareName={historyFor.name}
            canRecord
            onChanged={() => {
              client.invalidateQueries({ queryKey: ['vessel-fit', vesselId] });
              client.invalidateQueries({ queryKey: ['vessel-maintenance', vesselId] });
            }}
          />
        </DocumentsDialog>
      )}

      {addingUnder !== undefined && (
        <AddSpareDialog
          vesselId={vesselId}
          parent={addingUnder}
          onClose={() => setAddingUnder(undefined)}
          onAdded={(added) => {
            setAddingUnder(undefined);
            setNotice(`${added.path} ${added.name} added.`);
            client.invalidateQueries({ queryKey: ['vessel-fit', vesselId] });
          }}
        />
      )}

      {documentsFor && (
        <DocumentsDialog
          title="Documents"
          subtitle={`${documentsFor.path} · ${documentsFor.name}`}
          onClose={() => setDocumentsFor(null)}
          width={760}
        >
          <DocumentsPanel ownerType="SPARE" ownerId={documentsFor.id} ownerName={documentsFor.name} canAttach />
        </DocumentsDialog>
      )}

      {editing && (
        <EditSpareDialog
          spare={editing}
          onClose={() => setEditing(null)}
          onSaved={(details) => {
            setNotice(
              details.lastAnnualServiceDate && details.lastAnnualServiceDate !== editing.lastAnnualServiceDate
                ? `${editing.name} saved. Maintenance is now tracked from its last annual service.`
                : `${editing.name} saved.`,
            );
            setEditing(null);
            client.invalidateQueries({ queryKey: ['vessel-fit', vesselId] });
            client.invalidateQueries({ queryKey: ['vessel-maintenance', vesselId] });
            client.invalidateQueries({ queryKey: ['dashboard'] });
            client.invalidateQueries({ queryKey: ['notifications'] });
          }}
        />
      )}
    </div>
  );
}

function EquipmentMeta({ spare: s, due }: { spare: SpareNode; due?: SpareDue }) {
  const details = [s.make, s.model].filter(Boolean).join(' ');
  return (
    <>
      <CriticalityChip value={s.criticality} />
      {details || s.serialNumber ? (
        <span>
          {details || 'Make not recorded'}
          {s.serialNumber && <span className="mono"> · S/N {s.serialNumber}</span>}
        </span>
      ) : (
        <span className="equip__missing">Details not recorded yet</span>
      )}
      <span>
        Last annual service {s.lastAnnualServiceDate ? <b>{formatDate(s.lastAnnualServiceDate)}</b> : 'not recorded'}
      </span>
      {due && due.status !== 'NOT_TRACKED' && (
        <span className="equip__due">
          <StatusMark status={due.status} size={10} />
          <b>{due.statusLabel}</b>
          {due.nextDueDate && ` · due ${formatDate(due.nextDueDate)} (${formatDays(due.daysRemaining)})`}
        </span>
      )}
    </>
  );
}

function EditSpareDialog({ spare, onClose, onSaved }: { spare: SpareNode; onClose: () => void; onSaved: (d: SpareDetails) => void }) {
  const [form, setForm] = useState<SpareDetails>({
    make: spare.make ?? '',
    model: spare.model ?? '',
    serialNumber: spare.serialNumber ?? '',
    softwareVersion: spare.softwareVersion ?? '',
    installationDate: spare.installationDate ?? '',
    expirationDate: spare.expirationDate ?? '',
    lastAnnualServiceDate: spare.lastAnnualServiceDate ?? '',
    criticality: spare.criticality,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (key: keyof SpareDetails) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const save = async () => {
    setBusy(true);
    setError(null);
    const blankToUndefined = (v?: string) => (v && v.trim() !== '' ? v : undefined);
    try {
      onSaved(
        await updateSpare(spare.id, {
          make: blankToUndefined(form.make),
          model: blankToUndefined(form.model),
          serialNumber: blankToUndefined(form.serialNumber),
          softwareVersion: blankToUndefined(form.softwareVersion),
          installationDate: blankToUndefined(form.installationDate),
          expirationDate: blankToUndefined(form.expirationDate),
          lastAnnualServiceDate: blankToUndefined(form.lastAnnualServiceDate),
          criticality: form.criticality,
        }),
      );
    } catch (e) {
      setError(errorText(e, 'The details could not be saved.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Edit equipment details"
      subtitle={`${spare.path} ${spare.name}`}
      onClose={onClose}
      width={640}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy} onClick={save}>
            {busy ? 'Saving…' : 'Save details'}
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Make" htmlFor="spare-make">
          <input id="spare-make" className="input" maxLength={120} value={form.make} onChange={set('make')} />
        </Field>
        <Field label="Model" htmlFor="spare-model">
          <input id="spare-model" className="input" maxLength={120} value={form.model} onChange={set('model')} />
        </Field>
        <Field label="Serial number" htmlFor="spare-serial">
          <input id="spare-serial" className="input mono" maxLength={120} value={form.serialNumber} onChange={set('serialNumber')} />
        </Field>
        <Field label="Software version" htmlFor="spare-software">
          <input id="spare-software" className="input mono" maxLength={64} value={form.softwareVersion} onChange={set('softwareVersion')} />
        </Field>
        <Field label="Installation date" htmlFor="spare-installed">
          <input id="spare-installed" type="date" className="input" value={form.installationDate} onChange={set('installationDate')} />
        </Field>
        <Field label="Expiration date" htmlFor="spare-expires" hint="Batteries, releases and similar.">
          <input id="spare-expires" type="date" className="input" value={form.expirationDate} onChange={set('expirationDate')} />
        </Field>
        <Field label="Last annual service" htmlFor="spare-service" hint="Starts maintenance tracking: next due one year later.">
          <input id="spare-service" type="date" className="input" value={form.lastAnnualServiceDate} onChange={set('lastAnnualServiceDate')} />
        </Field>
        <Field label="Criticality" htmlFor="spare-criticality">
          <select id="spare-criticality" className="input" value={form.criticality} onChange={set('criticality')}>
            {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((c) => (
              <option key={c} value={c}>
                {c.charAt(0) + c.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <FormError message={error} />
    </Dialog>
  );
}
