import { useMemo, useState } from 'react';
import {
  allocateVessels,
  assignCaptain,
  createAccount,
  createOrganization,
  createVessel,
  type AccountSummary,
  type AdminVessel,
  type LinkSent,
  type OrganizationRow,
} from '@/api/admin';
import { Button, Segmented } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { errorText } from './AdminParts';

/** Platform Admin: a new client organization (SoW s4.1 step 1). */
export function AddOrganizationDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (org: OrganizationRow) => void }) {
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [codeTouched, setCodeTouched] = useState(false);
  const [address, setAddress] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const suggested = codeTouched ? code : suggestCode(name);
  const valid = name.trim() !== '' && /^[A-Z0-9]{2,12}$/.test(suggested);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(await createOrganization({ name, code: suggested, address, contactEmail: email, contactPhone: phone }));
    } catch (e) {
      setError(errorText(e, 'The organization could not be created.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Add organization"
      subtitle="A client company whose vessels Seastella manages"
      onClose={onClose}
      width={600}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Adding…' : 'Add organization'}
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <div className="ffield--wide">
          <Field label="Company name" htmlFor="org-name">
            <input id="org-name" className="input" value={name} maxLength={160} onChange={(e) => setName(e.target.value)} placeholder="e.g. Oceanic Bulk Carriers Pvt Ltd" />
          </Field>
        </div>
        <Field label="Short code" htmlFor="org-code" hint="2–12 letters or digits. Appears in request and invoice numbers.">
          <input
            id="org-code"
            className="input mono"
            value={suggested}
            maxLength={12}
            onChange={(e) => {
              setCodeTouched(true);
              setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''));
            }}
          />
        </Field>
        <Field label="Contact email" htmlFor="org-email">
          <input id="org-email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <div className="ffield--wide">
          <Field label="Address" htmlFor="org-address">
            <input id="org-address" className="input" value={address} maxLength={400} onChange={(e) => setAddress(e.target.value)} />
          </Field>
        </div>
        <Field label="Contact phone" htmlFor="org-phone">
          <input id="org-phone" className="input" value={phone} maxLength={40} onChange={(e) => setPhone(e.target.value)} />
        </Field>
      </div>
      <FormError message={error} />
    </Dialog>
  );
}

/**
 * Creates one account of a fixed role. Used for a Technical Head (by the
 * Platform Admin) and a Ship Manager (by the Technical Head); the server
 * decides whether the caller may.
 */
export function AddPersonDialog({
  title,
  subtitle,
  role,
  organizationId,
  vessels,
  vesselHint,
  onClose,
  onCreated,
}: {
  title: string;
  subtitle: string;
  role: 'TECHNICAL_HEAD' | 'SHIP_MANAGER';
  organizationId?: number;
  vessels?: AdminVessel[];
  vesselHint?: string;
  onClose: () => void;
  onCreated: (invitation: LinkSent) => void;
}) {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [chosen, setChosen] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = fullName.trim() !== '' && email.trim() !== '';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(await createAccount({ fullName, email, role, organizationId, vesselIds: chosen.length ? chosen : undefined }));
    } catch (e) {
      setError(errorText(e, 'The account could not be created.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={title}
      subtitle={subtitle}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Creating…' : 'Create and invite'}
          </Button>
        </>
      }
    >
      <Field label="Full name" htmlFor="person-name">
        <input id="person-name" className="input" value={fullName} maxLength={160} onChange={(e) => setFullName(e.target.value)} />
      </Field>
      <Field label="Work email" htmlFor="person-email" hint="We email an invitation to choose a password. They sign in with this address.">
        <input id="person-email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      </Field>
      {vessels && vessels.length > 0 && (
        <VesselChecklist label="Vessels they are responsible for" hint={vesselHint} vessels={vessels} chosen={chosen} onChange={setChosen} />
      )}
      <FormError message={error} />
    </Dialog>
  );
}

/** Technical Head: a vessel with the VMP template's fields and the standard fit. */
export function AddVesselDialog({
  organizations,
  onClose,
  onCreated,
}: {
  /** Only for the Platform Admin, who chooses the operating organization. */
  organizations?: OrganizationRow[];
  onClose: () => void;
  onCreated: (vessel: AdminVessel) => void;
}) {
  const [form, setForm] = useState({
    organizationId: '',
    name: '',
    imoNumber: '',
    mmsi: '',
    callSign: '',
    flag: '',
    vesselClass: '',
    area: '',
    vesselType: '',
    dwt: '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));
  const valid =
    form.name.trim() !== '' && /^\d{7}$/.test(form.imoNumber.trim()) && (!organizations || form.organizationId !== '');

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await createVessel({
          organizationId: form.organizationId ? Number(form.organizationId) : undefined,
          name: form.name,
          imoNumber: form.imoNumber.trim(),
          mmsi: form.mmsi || undefined,
          callSign: form.callSign || undefined,
          flag: form.flag || undefined,
          vesselClass: form.vesselClass || undefined,
          area: form.area || undefined,
          vesselType: form.vesselType || undefined,
          dwt: form.dwt ? Number(form.dwt) : undefined,
        }),
      );
    } catch (e) {
      setError(errorText(e, 'The vessel could not be added.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Add vessel"
      subtitle="Vessel particulars as in the VMP template"
      onClose={onClose}
      width={680}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Adding…' : 'Add vessel'}
          </Button>
        </>
      }
    >
      <div className="form-grid">
        {organizations && (
          <div className="ffield--wide">
            <Field label="Organization" htmlFor="vessel-org">
              <select id="vessel-org" className="input" value={form.organizationId} onChange={set('organizationId')}>
                <option value="">Choose organization</option>
                {organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        )}
        <Field label="Vessel name" htmlFor="vessel-name">
          <input id="vessel-name" className="input" value={form.name} maxLength={120} onChange={set('name')} placeholder="e.g. MV Ocean Pioneer" />
        </Field>
        <Field label="IMO number" htmlFor="vessel-imo" hint="7 digits; the last is a check digit.">
          <input id="vessel-imo" className="input mono" inputMode="numeric" maxLength={7} value={form.imoNumber} onChange={set('imoNumber')} />
        </Field>
        <Field label="Vessel type" htmlFor="vessel-type">
          <input id="vessel-type" className="input" value={form.vesselType} maxLength={64} onChange={set('vesselType')} placeholder="e.g. Bulk Carrier" />
        </Field>
        <Field label="Flag" htmlFor="vessel-flag">
          <input id="vessel-flag" className="input" value={form.flag} maxLength={64} onChange={set('flag')} />
        </Field>
        <Field label="MMSI" htmlFor="vessel-mmsi">
          <input id="vessel-mmsi" className="input mono" inputMode="numeric" maxLength={9} value={form.mmsi} onChange={set('mmsi')} />
        </Field>
        <Field label="Call sign" htmlFor="vessel-callsign">
          <input id="vessel-callsign" className="input mono" maxLength={16} value={form.callSign} onChange={set('callSign')} />
        </Field>
        <Field label="Class" htmlFor="vessel-class">
          <input id="vessel-class" className="input" maxLength={64} value={form.vesselClass} onChange={set('vesselClass')} placeholder="e.g. DNV" />
        </Field>
        <Field label="Trading area" htmlFor="vessel-area">
          <input id="vessel-area" className="input" maxLength={64} value={form.area} onChange={set('area')} />
        </Field>
        <Field label="DWT (tonnes)" htmlFor="vessel-dwt">
          <input id="vessel-dwt" className="input" inputMode="decimal" value={form.dwt} onChange={set('dwt')} />
        </Field>
      </div>
      <div className="fit-note">
        <div>
          <b>Standard bridge fit included.</b> The vessel starts with the VMP template's navigational equipment list — AIS
          through GPS, with EPIRB batteries, radar magnetrons and ECDIS fans nested under their units. Makes, models,
          serial numbers and service dates are added from the vessel's own records.
        </div>
      </div>
      <FormError message={error} />
    </Dialog>
  );
}

/** Technical Head: exactly which vessels a Ship Manager is responsible for. */
export function AllocateVesselsDialog({
  manager,
  vessels,
  onClose,
  onSaved,
}: {
  manager: AccountSummary;
  vessels: AdminVessel[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [chosen, setChosen] = useState<number[]>(manager.vesselIds);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await allocateVessels(manager.id, chosen);
      onSaved();
    } catch (e) {
      setError(errorText(e, 'The allocation could not be saved.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Allocate vessels"
      subtitle={manager.fullName}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy} onClick={submit}>
            {busy ? 'Saving…' : 'Save allocation'}
          </Button>
        </>
      }
    >
      <VesselChecklist
        label="Vessels"
        hint="Each vessel has one responsible Ship Manager. Choosing a vessel that is with someone else moves it here."
        vessels={vessels}
        chosen={chosen}
        onChange={setChosen}
        currentManagerId={manager.id}
      />
      <FormError message={error} />
    </Dialog>
  );
}

/** Ship Manager: the Captain of one of their vessels - an existing account or a new one. */
export function AssignCaptainDialog({
  vessel,
  captains,
  onClose,
  onAssigned,
  onCreated,
}: {
  vessel: AdminVessel;
  captains: AccountSummary[];
  onClose: () => void;
  onAssigned: () => void;
  onCreated: (invitation: LinkSent) => void;
}) {
  // Invited Captains count: a vessel can have its Captain before they accept.
  const available = useMemo(() => captains.filter((c) => c.status !== 'SUSPENDED' && c.id !== vessel.captain?.id), [captains, vessel]);
  const [mode, setMode] = useState<'existing' | 'new'>(available.length > 0 ? 'existing' : 'new');
  const [captainId, setCaptainId] = useState<number | ''>('');
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = mode === 'existing' ? captainId !== '' : fullName.trim() !== '' && email.trim() !== '';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      if (mode === 'existing') {
        await assignCaptain(vessel.id, Number(captainId));
        onAssigned();
      } else {
        onCreated(await createAccount({ fullName, email, role: 'CAPTAIN', vesselId: vessel.id }));
      }
    } catch (e) {
      setError(errorText(e, 'The Captain could not be assigned.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={vessel.captain ? 'Change Captain' : 'Assign Captain'}
      subtitle={`${vessel.name} · IMO ${vessel.imoNumber}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Saving…' : mode === 'existing' ? 'Assign Captain' : 'Create, assign and invite'}
          </Button>
        </>
      }
    >
      {vessel.captain && (
        <p className="otp__note">
          {vessel.captain.fullName} is Captain now. A vessel has one Captain, so they will be released from this vessel.
        </p>
      )}
      <div className="ffield">
        <span className="ffield__label">Captain</span>
        <Segmented<'existing' | 'new'>
          label="Captain"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'existing', label: 'Existing account', count: available.length },
            { value: 'new', label: 'New account' },
          ]}
        />
      </div>
      {mode === 'existing' ? (
        <Field label="Choose Captain" htmlFor="captain-existing" hint="A Captain serves on one vessel; one already serving moves here.">
          <select id="captain-existing" className="input" value={captainId} onChange={(e) => setCaptainId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">{available.length ? 'Choose a Captain' : 'No other Captain accounts'}</option>
            {available.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName} {c.vesselIds.length ? '· serving on another vessel' : '· not assigned'}
                {c.status === 'INVITED' ? ' · invitation pending' : ''}
              </option>
            ))}
          </select>
        </Field>
      ) : (
        <>
          <Field label="Full name" htmlFor="captain-name">
            <input id="captain-name" className="input" value={fullName} maxLength={160} onChange={(e) => setFullName(e.target.value)} placeholder="e.g. Capt. Arjun Nair" />
          </Field>
          <Field label="Email" htmlFor="captain-email" hint="We email the Captain an invitation to choose a password. They sign in with this address.">
            <input id="captain-email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
        </>
      )}
      <FormError message={error} />
    </Dialog>
  );
}

function VesselChecklist({
  label,
  hint,
  vessels,
  chosen,
  onChange,
  currentManagerId,
}: {
  label: string;
  hint?: string;
  vessels: AdminVessel[];
  chosen: number[];
  onChange: (ids: number[]) => void;
  currentManagerId?: number;
}) {
  const toggle = (id: number) => onChange(chosen.includes(id) ? chosen.filter((v) => v !== id) : [...chosen, id]);
  return (
    <div className="ffield">
      <span className="ffield__label">{label}</span>
      <div className="checklist">
        {vessels.map((v) => (
          <label key={v.id}>
            <input type="checkbox" checked={chosen.includes(v.id)} onChange={() => toggle(v.id)} />
            <span>{v.name}</span>
            <span className="checklist__note">
              {v.shipManager && v.shipManager.id !== currentManagerId ? `with ${v.shipManager.fullName}` : v.shipManager ? '' : 'unallocated'}
            </span>
          </label>
        ))}
      </div>
      {hint && <p className="ffield__hint">{hint}</p>}
    </div>
  );
}

function suggestCode(name: string) {
  const words = name
    .toUpperCase()
    .replace(/[^A-Z0-9 ]/g, ' ')
    .split(/\s+/)
    .filter((w) => w && !['PVT', 'LTD', 'PTE', 'AS', 'INC', 'LLC', 'THE', 'AND', 'OF'].includes(w));
  if (words.length === 0) return '';
  if (words.length === 1) return words[0].slice(0, 6);
  return words.map((w) => w[0]).join('').slice(0, 6);
}
