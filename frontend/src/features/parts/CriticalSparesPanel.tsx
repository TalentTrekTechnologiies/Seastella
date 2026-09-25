import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addPart,
  countStock,
  declareCompliance,
  fetchParts,
  removePart,
  updatePartDetails,
  type PartRow,
} from '@/api/parts';
import type { SpareNode } from '@/api/serviceRequests';
import { Button, EmptyNote, Plate } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDate } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import { ImportFileButton } from '@/features/import/ImportFileButton';
import './critical-spares.css';

/**
 * The vessel's minimum spares, as the client's own form records them
 * (GM 2.3.9.9 Navigation Equipment — Minimum Spares Recommendation).
 *
 * <p>Their form is a table: per equipment, the parts that must be aboard, how
 * many, whether the vessel complies, and a remark — a requisition number, a
 * shelf-life note, whatever the follow-up is. The form says the inventory is
 * checked monthly and deficiencies acted upon, so the two things that have to
 * be obvious on this screen are what is short and what has not been assessed.
 *
 * <p>The minimum is shown twice where the form needs it to be: the number the
 * shortage alert is calculated from, and the requirement as written, because
 * "2 pcs each athwartship, fore and aft and Flinders bar" is not a number.
 */
export function CriticalSparesPanel({
  vesselId,
  equipment,
  canManage,
}: {
  vesselId: number;
  /** The vessel's equipment, to file each part against. */
  equipment: SpareNode[];
  /** Master data is the Technical Head's; the bridge counts, it does not set. */
  canManage: boolean;
}) {
  const client = useQueryClient();
  const parts = useQuery({ queryKey: ['parts', vesselId], queryFn: () => fetchParts(vesselId) });
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<PartRow | null>(null);
  const [declaring, setDeclaring] = useState<PartRow | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['parts', vesselId] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const critical = (parts.data ?? []).filter((p) => p.critical);
  const short = critical.filter((p) => p.belowMinimum).length;
  const unassessed = critical.filter((p) => p.compliance === null).length;

  // Grouped by equipment, which is how their form reads.
  const groups = useMemo(() => {
    const byEquipment = new Map<string, PartRow[]>();
    for (const part of critical) {
      const key = part.spareName ?? part.equipmentLabel ?? 'Not linked to equipment';
      const list = byEquipment.get(key) ?? [];
      list.push(part);
      byEquipment.set(key, list);
    }
    return [...byEquipment.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [critical]);

  return (
    <Plate
      title="Critical spares"
      count={critical.length || undefined}
      subtitle={
        critical.length === 0
          ? 'The minimum spares this vessel must hold on board'
          : [
              short > 0 ? `${short} below minimum` : 'none below minimum',
              unassessed > 0 ? `${unassessed} not yet assessed` : null,
            ]
              .filter(Boolean)
              .join(' · ')
      }
      action={
        canManage && (
          <div className="plate-actions">
            {/* The client's minimum-spares form, read as it is. */}
            <ImportFileButton vesselId={vesselId} />
            <Button variant="primary" onClick={() => setAdding(true)}>
              Add critical spare
            </Button>
          </div>
        )
      }
      flush
    >
      {parts.isLoading ? (
        <div style={{ padding: '0 20px 20px' }}>
          <LoadingState rows={5} />
        </div>
      ) : critical.length === 0 ? (
        <div className="qlist__empty">
          <EmptyNote>
            Nothing recorded yet. Add the parts this vessel must always carry, or bring the whole list in from the
            spreadsheet.
          </EmptyNote>
        </div>
      ) : (
        <div className="crit">
          <div className="crit__head">
            <span>Spare part</span>
            <span>Minimum</span>
            <span>On board</span>
            <span>Compliance</span>
            <span>Remarks</span>
            <span />
          </div>
          {groups.map(([equipmentName, rows]) => (
            <div className="crit__group" key={equipmentName}>
              <div className="crit__equipment">{equipmentName}</div>
              {rows.map((part) => (
                <div className={`crit__row${part.belowMinimum ? ' crit__row--short' : ''}`} key={part.id}>
                  <span className="crit__name">
                    {part.name}
                    {part.partNumber && <span className="mono"> {part.partNumber}</span>}
                  </span>
                  <span className="crit__min">
                    {/* The form's own words where they exist; the number is what the alert uses. */}
                    {part.minimumNote || `${part.minimumQuantity}`}
                  </span>
                  <span className="crit__qty">
                    <b>{part.quantityOnHand}</b>
                    {part.belowMinimum && <Pill size="sm" tone="overdue">Short</Pill>}
                    {part.expiryDate && <small>expires {formatDate(part.expiryDate)}</small>}
                  </span>
                  <span className="crit__comply">
                    {canManage ? (
                      <button type="button" className={`crit__pill crit__pill--${tone(part.compliance)}`} onClick={() => setDeclaring(part)}>
                        {label(part.compliance)}
                      </button>
                    ) : (
                      <span className={`crit__pill crit__pill--${tone(part.compliance)}`}>{label(part.compliance)}</span>
                    )}
                  </span>
                  <span className="crit__remarks">{part.remarks || '—'}</span>
                  <span className="crit__actions">
                    {canManage && (
                      <Button variant="ghost" onClick={() => setEditing(part)}>
                        Edit
                      </Button>
                    )}
                  </span>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {(adding || editing) && (
        <CriticalSpareDialog
          vesselId={vesselId}
          equipment={equipment}
          part={editing}
          onClose={() => {
            setAdding(false);
            setEditing(null);
          }}
          onSaved={() => {
            setAdding(false);
            setEditing(null);
            refresh();
          }}
        />
      )}

      {declaring && (
        <ComplianceDialog
          part={declaring}
          onClose={() => setDeclaring(null)}
          onSaved={() => {
            setDeclaring(null);
            refresh();
          }}
        />
      )}
    </Plate>
  );
}

function label(compliance: PartRow['compliance']) {
  if (compliance === 'YES') return 'Yes';
  if (compliance === 'NO') return 'No';
  if (compliance === 'NA') return 'N/A';
  return 'Not assessed';
}

/** Not assessed is not the same as N/A, and must not look like it. */
function tone(compliance: PartRow['compliance']) {
  if (compliance === 'YES') return 'yes';
  if (compliance === 'NO') return 'no';
  if (compliance === 'NA') return 'na';
  return 'unknown';
}

/** Adds a line of the minimum-spares form, or corrects one when `part` is given. */
function CriticalSpareDialog({
  vesselId,
  equipment,
  part,
  onClose,
  onSaved,
}: {
  vesselId: number;
  equipment: SpareNode[];
  part: PartRow | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(part?.name ?? '');
  const [spareId, setSpareId] = useState<number | ''>(part?.spareId ?? '');
  const [minimumQuantity, setMinimumQuantity] = useState(String(part?.minimumQuantity ?? 1));
  const [minimumNote, setMinimumNote] = useState(part?.minimumNote ?? '');
  const [quantityOnHand, setQuantityOnHand] = useState(String(part?.quantityOnHand ?? 0));
  const [partNumber, setPartNumber] = useState(part?.partNumber ?? '');
  const [location, setLocation] = useState(part?.location ?? '');
  const [expiryDate, setExpiryDate] = useState(part?.expiryDate ?? '');
  const [remarks, setRemarks] = useState(part?.remarks ?? '');
  const [busy, setBusy] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = name.trim() !== '' && Number.isFinite(Number(minimumQuantity));

  const save = async () => {
    setBusy(true);
    setError(null);
    const details = {
      name: name.trim(),
      spareId: spareId === '' ? undefined : Number(spareId),
      minimumQuantity: Number(minimumQuantity),
      minimumNote: minimumNote.trim() || undefined,
      partNumber: partNumber.trim() || undefined,
      location: location.trim() || undefined,
      expiryDate: expiryDate || undefined,
      remarks: remarks.trim() || undefined,
    };
    try {
      if (part) {
        await updatePartDetails(part.id, {
          ...details,
          manufacturer: part.manufacturer ?? undefined,
          compliance: part.compliance ?? undefined,
        });
        // The on-board figure is a stock count: it has its own shortage alert.
        const counted = Number(quantityOnHand) || 0;
        if (counted !== part.quantityOnHand) await countStock(part.id, counted, 'Corrected from the critical spares list');
      } else {
        await addPart(vesselId, { ...details, quantityOnHand: Number(quantityOnHand) || 0, critical: true });
      }
      onSaved();
    } catch (e) {
      setError(errorText(e, part ? 'That spare could not be saved.' : 'That spare could not be added.'));
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!part) return;
    setBusy(true);
    setError(null);
    try {
      await removePart(part.id);
      onSaved();
    } catch (e) {
      setError(errorText(e, 'That spare could not be removed.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={part ? 'Edit critical spare' : 'Add a critical spare'}
      subtitle={part ? part.spareName ?? part.equipmentLabel ?? 'Not linked to equipment' : 'A part this vessel must always hold on board'}
      onClose={onClose}
      width={620}
      footer={
        <>
          {part && (
            <Button variant="danger" disabled={busy} onClick={() => (confirmRemove ? void remove() : setConfirmRemove(true))}>
              {confirmRemove ? 'Confirm remove' : 'Remove'}
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={save}>
            {busy ? 'Saving…' : part ? 'Save' : 'Add'}
          </Button>
        </>
      }
    >
      <Field label="Spare part name" htmlFor="crit-name" hint="As the minimum-spares list names it.">
        <input
          id="crit-name"
          className="input"
          maxLength={200}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. Magnetron (S Band)"
        />
      </Field>

      <Field label="Equipment it belongs to" htmlFor="crit-equipment" hint="Optional, but it is how the list is read.">
        <select
          id="crit-equipment"
          className="input"
          value={spareId}
          onChange={(e) => setSpareId(e.target.value ? Number(e.target.value) : '')}
        >
          <option value="">
            {part?.equipmentLabel ? `Not linked (listed under ${part.equipmentLabel})` : 'Not linked to a specific item'}
          </option>
          {equipment.map((s) => (
            <option key={s.id} value={s.id}>
              {s.path} {s.name}
            </option>
          ))}
        </select>
      </Field>

      <div className="form-grid">
        <Field label="Minimum quantity" htmlFor="crit-min" hint="The number the shortage alert uses.">
          <input
            id="crit-min"
            className="input"
            type="number"
            min={0}
            value={minimumQuantity}
            onChange={(e) => setMinimumQuantity(e.target.value)}
          />
        </Field>
        <Field label="On board now" htmlFor="crit-onhand">
          <input
            id="crit-onhand"
            className="input"
            type="number"
            min={0}
            value={quantityOnHand}
            onChange={(e) => setQuantityOnHand(e.target.value)}
          />
        </Field>
      </div>

      <Field
        label="Minimum as written"
        htmlFor="crit-minnote"
        hint="Optional. Use where the requirement is not a plain number."
      >
        <input
          id="crit-minnote"
          className="input"
          maxLength={300}
          value={minimumNote}
          onChange={(e) => setMinimumNote(e.target.value)}
          placeholder="e.g. 2 pcs each athwartship, fore & aft and Flinders bar"
        />
      </Field>

      <div className="form-grid">
        <Field label="Part number" htmlFor="crit-pn">
          <input id="crit-pn" className="input mono" maxLength={120} value={partNumber} onChange={(e) => setPartNumber(e.target.value)} />
        </Field>
        <Field label="Stored at" htmlFor="crit-loc">
          <input id="crit-loc" className="input" maxLength={120} value={location} onChange={(e) => setLocation(e.target.value)} />
        </Field>
        <Field label="Expiry date" htmlFor="crit-exp" hint="For anything with a shelf life.">
          <input id="crit-exp" className="input" type="date" value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)} />
        </Field>
      </div>

      <Field label="Remarks" htmlFor="crit-remarks" hint="Applicability, status, requisition number.">
        <input id="crit-remarks" className="input" maxLength={1000} value={remarks} onChange={(e) => setRemarks(e.target.value)} />
      </Field>

      <FormError message={error} />
    </Dialog>
  );
}

function ComplianceDialog({ part, onClose, onSaved }: { part: PartRow; onClose: () => void; onSaved: () => void }) {
  const [compliance, setCompliance] = useState<'YES' | 'NO' | 'NA' | ''>(part.compliance ?? '');
  const [remarks, setRemarks] = useState(part.remarks ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await declareCompliance(part.id, compliance === '' ? null : compliance, remarks.trim() || undefined);
      onSaved();
    } catch (e) {
      setError(errorText(e, 'That could not be recorded.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Compliance"
      subtitle={`${part.name}${part.spareName ? ` · ${part.spareName}` : ''}`}
      onClose={onClose}
      width={520}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy} onClick={save}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <Field label="Does this vessel comply?" htmlFor="crit-comply">
        <select
          id="crit-comply"
          className="input"
          value={compliance}
          onChange={(e) => setCompliance(e.target.value as 'YES' | 'NO' | 'NA' | '')}
        >
          <option value="">Not assessed</option>
          <option value="YES">Yes — the minimum is held</option>
          <option value="NO">No — below the minimum</option>
          <option value="NA">N/A — this vessel does not carry that equipment</option>
        </select>
      </Field>

      <Field
        label="Remarks"
        htmlFor="crit-comply-remarks"
        hint="For No, the form asks for the follow-up: requisition number, expected date."
      >
        <textarea
          id="crit-comply-remarks"
          className="textarea"
          rows={3}
          maxLength={1000}
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
        />
      </Field>

      <p className="otp__note">
        Leaving this as <b>Not assessed</b> is not the same as N/A — one means nobody has checked, the other means
        the equipment is not carried. The monthly inventory check is looking for the first.
      </p>
      <FormError message={error} />
    </Dialog>
  );
}
