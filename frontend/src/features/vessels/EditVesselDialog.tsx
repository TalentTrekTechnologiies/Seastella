import { useState } from 'react';
import { updateVessel, type VesselDetails } from '@/api/vessels';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { errorText } from '@/features/admin/AdminParts';

const STATUSES = [
  ['ACTIVE', 'Active'],
  ['DRY_DOCK', 'Dry dock'],
  ['INACTIVE', 'Inactive'],
  ['DECOMMISSIONED', 'Decommissioned'],
] as const;

/**
 * Correcting a vessel's particulars. The SBMC prints these as they stand, so
 * a wrong call sign or MMSI is fixed here, never typed into a certificate.
 */
export function EditVesselDialog({
  vessel,
  focus,
  onClose,
  onSaved,
}: {
  vessel: VesselDetails;
  /** The field to start on - the one the SBMC preview found missing. */
  focus?: string;
  onClose: () => void;
  onSaved: (v: VesselDetails) => void;
}) {
  const [form, setForm] = useState({
    name: vessel.name,
    imoNumber: vessel.imoNumber,
    mmsi: vessel.mmsi ?? '',
    callSign: vessel.callSign ?? '',
    flag: vessel.flag ?? '',
    vesselClass: vessel.vesselClass ?? '',
    area: vessel.area ?? '',
    vesselType: vessel.vesselType ?? '',
    dwt: vessel.dwt == null ? '' : String(vessel.dwt),
    status: vessel.status,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const save = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const saved = await updateVessel(vessel.id, {
        name: form.name,
        imoNumber: form.imoNumber,
        mmsi: form.mmsi,
        callSign: form.callSign,
        flag: form.flag,
        vesselClass: form.vesselClass,
        area: form.area,
        vesselType: form.vesselType,
        dwt: form.dwt.trim() === '' ? undefined : Number(form.dwt),
        status: form.status,
      });
      onSaved(saved);
    } catch (err) {
      setError(errorText(err, 'The vessel could not be saved.'));
    } finally {
      setBusy(false);
    }
  };

  const input = (key: keyof typeof form, label: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) => (
    <Field label={label} htmlFor={`ev-${key}`}>
      <input
        id={`ev-${key}`}
        className="input"
        value={form[key]}
        onChange={set(key)}
        autoFocus={focus === key}
        {...props}
      />
    </Field>
  );

  return (
    <Dialog
      title="Edit vessel"
      subtitle={vessel.organizationName}
      onClose={onClose}
      width={620}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !form.name.trim() || !form.imoNumber.trim()} onClick={() => save()}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <form onSubmit={save} className="vform">
        {input('name', 'Vessel name', { maxLength: 120 })}
        {input('imoNumber', 'IMO number', { maxLength: 10, inputMode: 'numeric' })}
        {input('mmsi', 'MMSI', { maxLength: 9, inputMode: 'numeric', placeholder: '9 digits' })}
        {input('callSign', 'Call sign', { maxLength: 16 })}
        {input('flag', 'Flag', { maxLength: 64 })}
        {input('vesselClass', 'Class', { maxLength: 64 })}
        {input('area', 'Trading area', { maxLength: 64 })}
        {input('vesselType', 'Vessel type', { maxLength: 64 })}
        {input('dwt', 'DWT (tonnes)', { inputMode: 'decimal' })}
        <Field label="Status" htmlFor="ev-status">
          <select id="ev-status" className="input" value={form.status} onChange={set('status')}>
            {STATUSES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </Field>
        <button type="submit" hidden />
      </form>
      <FormError message={error} />
    </Dialog>
  );
}
