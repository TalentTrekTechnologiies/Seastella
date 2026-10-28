import { useEffect, useState } from 'react';
import {
  downloadCertificate,
  fetchSbmcPreview,
  generateSbmc,
  type CertificateRow,
  type SbmcPreview,
} from '@/api/vessels';
import { Button } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { LoadingState } from '@/design-system/States';
import { errorText } from '@/features/admin/AdminParts';
import { formatDate } from '@/lib/format';

/**
 * Issuing a Shore Based Maintenance Certificate: what it will say, checked
 * before it is issued. The vessel's particulars are read-only here - they come
 * from the vessel record - and anything missing blocks issue with a way to fix
 * it. The dates and the owner line are the administrator's to set.
 */
export function SbmcDialog({
  vesselId,
  onClose,
  onIssued,
  onEditVessel,
}: {
  vesselId: number;
  onClose: () => void;
  onIssued: (c: CertificateRow) => void;
  onEditVessel: (field?: string) => void;
}) {
  const [preview, setPreview] = useState<SbmcPreview | null>(null);
  const [issueDate, setIssueDate] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [ownedBy, setOwnedBy] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [issued, setIssued] = useState<CertificateRow | null>(null);

  useEffect(() => {
    fetchSbmcPreview(vesselId)
      .then((p) => {
        setPreview(p);
        setIssueDate(p.issueDate);
        setExpiryDate(p.expiryDate);
        setOwnedBy(p.ownedBy ?? '');
      })
      .catch((e) => setError(errorText(e, 'The certificate details could not be loaded.')));
  }, [vesselId]);

  /** A year and a day, as the sample runs; moving the issue date moves the expiry with it. */
  const changeIssue = (value: string) => {
    setIssueDate(value);
    if (value) {
      const d = new Date(`${value}T00:00:00`);
      d.setFullYear(d.getFullYear() + 1);
      d.setDate(d.getDate() + 1);
      setExpiryDate(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`);
    }
  };

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      const c = await generateSbmc(vesselId, { issueDate, expiryDate, ownedBy });
      setIssued(c);
      onIssued(c);
    } catch (e) {
      setError(errorText(e, 'The certificate could not be issued.'));
    } finally {
      setBusy(false);
    }
  };

  if (issued) {
    return (
      <Dialog
        title="SBMC issued"
        subtitle={issued.certificateNumber}
        onClose={onClose}
        width={480}
        footer={
          <>
            <Button onClick={onClose}>Close</Button>
            <Button variant="primary" onClick={() => downloadCertificate(issued).catch(() => undefined)}>
              Download PDF
            </Button>
          </>
        }
      >
        <p className="otp__note">
          Certificate <b>{issued.certificateNumber}</b> is valid from {formatDate(issued.issueDate)} to{' '}
          {formatDate(issued.expiryDate)}. It is filed with the vessel's documents, and its expiry will be announced
          like any other certificate's.
          {preview?.replaces && (
            <>
              {' '}
              {preview.replaces.certificateNumber} is now marked superseded and kept on record.
            </>
          )}
        </p>
      </Dialog>
    );
  }

  const missing = preview?.missing.filter((m) => m.field !== 'ownedBy') ?? [];
  const ownerMissing = !ownedBy.trim();
  const blocked = missing.length > 0 || ownerMissing || !issueDate || !expiryDate;

  return (
    <Dialog
      title="Generate SBMC"
      subtitle="Shore Based Maintenance Certificate"
      onClose={onClose}
      width={560}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!preview || busy || blocked} onClick={generate}>
            {busy ? 'Issuing…' : 'Generate SBMC'}
          </Button>
        </>
      }
    >
      {!preview ? (
        error ? <FormError message={error} /> : <LoadingState rows={4} />
      ) : (
        <div className="sbmc">
          {missing.length > 0 && (
            <div className="notice notice--wait" role="alert">
              <div>
                <p className="notice__title">The vessel record is missing {missing.map((m) => m.label).join(', ')}</p>
                <p className="notice__body">
                  The certificate prints these from the vessel, so add them there first.{' '}
                  <button type="button" className="linkbtn" onClick={() => onEditVessel(missing[0].field)}>
                    Edit vessel
                  </button>
                </p>
              </div>
            </div>
          )}
          <dl className="sbmc__facts">
            <dt>Vessel</dt>
            <dd>{vesselLine(preview.vesselName)}</dd>
            <dt>Call sign</dt>
            <dd className={preview.callSign ? '' : 'sbmc__gap'}>{preview.callSign || 'Not recorded'}</dd>
            <dt>MMSI</dt>
            <dd className={missing.some((m) => m.field === 'mmsi') ? 'sbmc__gap mono' : 'mono'}>
              {preview.mmsi || 'Not recorded'}
            </dd>
            <dt>IMO</dt>
            <dd className="mono">{preview.imo || 'Not recorded'}</dd>
          </dl>
          <Field
            label="Owned and/or managed by"
            htmlFor="sbmc-owner"
            hint="The organization by default. Change it when the owner is not the manager."
          >
            <input
              id="sbmc-owner"
              className="input"
              maxLength={200}
              value={ownedBy}
              onChange={(e) => setOwnedBy(e.target.value)}
            />
          </Field>
          <div className="sbmc__dates">
            <Field label="Issue date" htmlFor="sbmc-issue">
              <input id="sbmc-issue" className="input" type="date" value={issueDate} onChange={(e) => changeIssue(e.target.value)} />
            </Field>
            <Field label="Certificate expires" htmlFor="sbmc-expiry" hint="A year and a day from issue, as the sample runs.">
              <input id="sbmc-expiry" className="input" type="date" value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)} />
            </Field>
          </div>
          {preview.replaces && (
            <p className="otp__note">
              This replaces <b>{preview.replaces.certificateNumber}</b> (valid to {formatDate(preview.replaces.expiryDate)}),
              which will be marked superseded. Nothing is deleted.
            </p>
          )}
          <FormError message={error} />
        </div>
      )}
    </Dialog>
  );
}

/** As the certificate prints it: the template's own "MV." is not doubled for "MV Kestrel Trader". */
function vesselLine(name: string) {
  return `MV. ${name.trim().toUpperCase().replace(/^M\s*[./]?\s*V\.?\s+/, '')}`;
}
