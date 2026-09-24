import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { addSpare, fetchEquipmentCategories } from '@/api/admin';
import type { SpareNode } from '@/api/serviceRequests';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { errorText } from './AdminParts';

/**
 * Adding equipment to a vessel, or a spare inside a piece of equipment
 * (SoW §9).
 *
 * <p>The words follow the client's: a vessel carries equipment, and equipment
 * contains spares. Underneath, §9 calls both a Spare — "the individual
 * serviceable item within that category, a full unit or a component of a
 * larger unit" — and nests them by decimal path exactly as the VMP template
 * does. The model and the vocabulary agree; only the label differs, and the
 * label belongs to the people using it.
 *
 * <p>The VMP number is not asked for. A top-level item takes the next free
 * number in its category's block; a component takes the next free number under
 * its parent — 13.1 gains 13.1.4. Letting people type paths is how a tree
 * stops being a tree, and the decimal structure is the thing the client
 * recognises from their own template.
 */
export function AddSpareDialog({
  vesselId,
  parent,
  onClose,
  onAdded,
}: {
  vesselId: number;
  /** Null adds a top-level item; a spare adds a component inside it. */
  parent: SpareNode | null;
  onClose: () => void;
  onAdded: (added: { id: number; path: string; name: string }) => void;
}) {
  const categories = useQuery({
    queryKey: ['equipment-categories', vesselId],
    queryFn: () => fetchEquipmentCategories(vesselId),
    enabled: parent === null,
  });

  const [name, setName] = useState('');
  const [categoryId, setCategoryId] = useState<number | ''>('');
  const [make, setMake] = useState('');
  const [model, setModel] = useState('');
  const [serialNumber, setSerialNumber] = useState('');
  const [installationDate, setInstallationDate] = useState('');
  const [criticality, setCriticality] = useState('MEDIUM');
  const [tracksRunningHours, setTracksRunningHours] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const today = new Date().toISOString().slice(0, 10);
  const valid = name.trim() !== '' && (parent !== null || categoryId !== '');

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onAdded(
        await addSpare(vesselId, {
          name: name.trim(),
          parentSpareId: parent?.id,
          equipmentCategoryId: parent === null ? Number(categoryId) : undefined,
          make: make.trim() || undefined,
          model: model.trim() || undefined,
          serialNumber: serialNumber.trim() || undefined,
          installationDate: installationDate || undefined,
          criticality,
          tracksRunningHours,
        }),
      );
    } catch (e) {
      setError(errorText(e, 'That equipment could not be added.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={parent ? 'Add a spare' : 'Add equipment'}
      subtitle={parent ? `Inside ${parent.path} ${parent.name}` : 'A new top-level item on this vessel'}
      onClose={onClose}
      width={640}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={save}>
            {busy ? 'Adding…' : 'Add'}
          </Button>
        </>
      }
    >
      <Field label="Name" htmlFor="spare-new-name" hint="As it appears on the vessel's equipment list.">
        <input
          id="spare-new-name"
          className="input"
          maxLength={200}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder={parent ? 'e.g. Display Fan' : 'e.g. X-Band Radar'}
        />
      </Field>

      {parent === null ? (
        <Field
          label="Equipment category"
          htmlFor="spare-new-category"
          hint="Its VMP number comes from the category; you do not type one."
        >
          <select
            id="spare-new-category"
            className="input"
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}
            disabled={categories.isLoading}
          >
            <option value="">{categories.isLoading ? 'Loading…' : 'Choose a category'}</option>
            {(categories.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
      ) : (
        <p className="otp__note">
          This spare belongs to {parent.path} {parent.name} and takes the next number beneath it — the VMP decimal
          structure, as in the template. Its equipment category is inherited from the item it sits in.
        </p>
      )}

      <div className="form-grid">
        <Field label="Make" htmlFor="spare-new-make">
          <input id="spare-new-make" className="input" maxLength={120} value={make} onChange={(e) => setMake(e.target.value)} />
        </Field>
        <Field label="Model" htmlFor="spare-new-model">
          <input id="spare-new-model" className="input" maxLength={120} value={model} onChange={(e) => setModel(e.target.value)} />
        </Field>
        <Field label="Serial number" htmlFor="spare-new-serial">
          <input
            id="spare-new-serial"
            className="input mono"
            maxLength={120}
            value={serialNumber}
            onChange={(e) => setSerialNumber(e.target.value)}
          />
        </Field>
        <Field label="Installation date" htmlFor="spare-new-installed">
          <input
            id="spare-new-installed"
            className="input"
            type="date"
            max={today}
            value={installationDate}
            onChange={(e) => setInstallationDate(e.target.value)}
          />
        </Field>
        <Field label="Criticality" htmlFor="spare-new-criticality">
          <select
            id="spare-new-criticality"
            className="input"
            value={criticality}
            onChange={(e) => setCriticality(e.target.value)}
          >
            {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((c) => (
              <option key={c} value={c}>
                {c.charAt(0) + c.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <label className="checkline">
        <input type="checkbox" checked={tracksRunningHours} onChange={(e) => setTracksRunningHours(e.target.checked)} />
        <span>
          The Captain records running hours for this
          <small>For equipment whose service falls due on hours run rather than on the calendar.</small>
        </span>
      </label>

      <p className="otp__note">
        Its service history is entered separately — add it, then open <b>Service history</b> to record when it was
        last serviced. That is what starts maintenance tracking.
      </p>
      <FormError message={error} />
    </Dialog>
  );
}
