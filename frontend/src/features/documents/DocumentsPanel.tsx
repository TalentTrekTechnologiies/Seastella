import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  DOCUMENT_TYPE_LABEL,
  downloadDocument,
  expiryState,
  fetchDocuments,
  removeDocument,
  uploadDocument,
  type DocumentRow,
  type DocumentType,
  type OwnerType,
} from '@/api/documents';
import { Button, EmptyNote } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDate, formatDateTime } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import './documents.css';

/**
 * Documents and certificates for one thing — a vessel or a spare (SoW §7).
 *
 * <p>Certificates carry their dates and say how long they have left, because
 * "when does this run out" is the question the folder exists to answer. Nothing
 * is deleted: replacing keeps the old copy, and removing leaves the record.
 */
export function DocumentsPanel({
  ownerType,
  ownerId,
  ownerName,
  canAttach,
}: {
  ownerType: OwnerType;
  ownerId: number;
  ownerName: string;
  canAttach: boolean;
}) {
  const client = useQueryClient();
  const [history, setHistory] = useState(false);
  const [adding, setAdding] = useState<null | { supersedes?: DocumentRow }>(null);
  const [removing, setRemoving] = useState<DocumentRow | null>(null);
  const [error, setError] = useState<string | null>(null);

  const documents = useQuery({
    queryKey: ['documents', ownerType, ownerId, history],
    queryFn: () => fetchDocuments(ownerType, ownerId, history),
  });

  const refresh = () => client.invalidateQueries({ queryKey: ['documents'] });

  const download = async (row: DocumentRow) => {
    setError(null);
    try {
      await downloadDocument(row);
    } catch (e) {
      setError(errorText(e, 'That file could not be downloaded.'));
    }
  };

  const rows = documents.data ?? [];

  return (
    <div className="docs">
      <div className="docs__bar">
        <label className="docs__history">
          <input type="checkbox" checked={history} onChange={(e) => setHistory(e.target.checked)} />
          Show replaced and removed
        </label>
        {canAttach && (
          <Button variant="primary" onClick={() => setAdding({})}>
            <Icon name="report" size={16} />
            Attach a document
          </Button>
        )}
      </div>

      <FormError message={error} />

      {documents.isLoading ? (
        <LoadingState rows={2} />
      ) : rows.length === 0 ? (
        <EmptyNote>
          Nothing filed against {ownerName} yet. Certificates, manuals and photographs belong here.
        </EmptyNote>
      ) : (
        <ul className="docs__list">
          {rows.map((row) => (
            <li key={row.id} className={`docs__item${row.current ? '' : ' docs__item--past'}`}>
              <span className="docs__icon" aria-hidden="true">
                <Icon name={row.documentType === 'CERTIFICATE' ? 'audit' : 'report'} size={18} />
              </span>
              <div className="docs__main">
                <div className="docs__title">
                  <b>{row.title}</b>
                  <Pill size="sm">{DOCUMENT_TYPE_LABEL[row.documentType]}</Pill>
                  <ExpiryPill row={row} />
                  {row.removed && <Pill size="sm">Removed</Pill>}
                  {!row.removed && !row.current && <Pill size="sm">Replaced</Pill>}
                </div>
                <div className="docs__meta">
                  {row.fileName} · {kilobytes(row.sizeBytes)} · uploaded by {row.uploadedBy ?? 'someone'}{' '}
                  {formatDateTime(row.uploadedAt)}
                </div>
                {row.documentType === 'CERTIFICATE' && (
                  <div className="docs__meta">
                    {[
                      row.certificateNumber && `No. ${row.certificateNumber}`,
                      row.issuingAuthority,
                      row.issuedDate && `issued ${formatDate(row.issuedDate)}`,
                      row.expiryDate && `expires ${formatDate(row.expiryDate)}`,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </div>
                )}
              </div>
              <div className="docs__actions">
                <Button variant="ghost" onClick={() => download(row)}>
                  Download
                </Button>
                {canAttach && row.current && (
                  <>
                    <Button variant="ghost" onClick={() => setAdding({ supersedes: row })}>
                      Replace
                    </Button>
                    <Button variant="ghost" onClick={() => setRemoving(row)}>
                      Remove
                    </Button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {adding && (
        <UploadDialog
          ownerType={ownerType}
          ownerId={ownerId}
          ownerName={ownerName}
          supersedes={adding.supersedes}
          onClose={() => setAdding(null)}
          onUploaded={() => {
            setAdding(null);
            refresh();
          }}
        />
      )}
      {removing && (
        <RemoveDialog
          row={removing}
          onClose={() => setRemoving(null)}
          onRemoved={() => {
            setRemoving(null);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function ExpiryPill({ row }: { row: DocumentRow }) {
  const state = expiryState(row);
  if (!state) return null;
  if (state === 'expired') return <Pill size="sm">Expired</Pill>;
  if (state === 'expiring') {
    return (
      <Pill size="sm" tone="approaching">
        {row.daysToExpiry === 0 ? 'Expires today' : `${row.daysToExpiry} days left`}
      </Pill>
    );
  }
  return (
    <Pill size="sm" tone="accent">
      Valid
    </Pill>
  );
}

function UploadDialog({
  ownerType,
  ownerId,
  ownerName,
  supersedes,
  onClose,
  onUploaded,
}: {
  ownerType: OwnerType;
  ownerId: number;
  ownerName: string;
  supersedes?: DocumentRow;
  onClose: () => void;
  onUploaded: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [documentType, setDocumentType] = useState<DocumentType>(supersedes?.documentType ?? 'CERTIFICATE');
  const [title, setTitle] = useState(supersedes?.title ?? '');
  const [certificateNumber, setCertificateNumber] = useState('');
  const [issuingAuthority, setIssuingAuthority] = useState(supersedes?.issuingAuthority ?? '');
  const [issuedDate, setIssuedDate] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isCertificate = documentType === 'CERTIFICATE';
  const valid = file !== null && title.trim() !== '' && (!isCertificate || expiryDate !== '');

  const submit = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await uploadDocument(file, {
        ownerType,
        ownerId,
        documentType,
        title,
        certificateNumber: isCertificate ? certificateNumber : undefined,
        issuingAuthority: isCertificate ? issuingAuthority : undefined,
        issuedDate: isCertificate ? issuedDate : undefined,
        expiryDate: isCertificate ? expiryDate : undefined,
        supersedesId: supersedes?.id,
      });
      onUploaded();
    } catch (e) {
      setError(errorText(e, 'The document could not be attached.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={supersedes ? 'Replace document' : 'Attach a document'}
      subtitle={supersedes ? `${supersedes.title} · ${ownerName}` : ownerName}
      onClose={onClose}
      width={560}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={submit}>
            {busy ? 'Uploading…' : supersedes ? 'Replace' : 'Attach'}
          </Button>
        </>
      }
    >
      {supersedes && (
        <p className="otp__note">
          The current copy is kept and marked as replaced, so what was valid before is still on file.
        </p>
      )}
      <Field label="File" htmlFor="doc-file" hint="PDF, image, Word document or spreadsheet.">
        <input
          id="doc-file"
          className="input"
          type="file"
          accept=".pdf,.png,.jpg,.jpeg,.webp,.docx,.xlsx"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </Field>
      <Field label="What is it" htmlFor="doc-type">
        <select id="doc-type" className="input" value={documentType} onChange={(e) => setDocumentType(e.target.value as DocumentType)}>
          {(Object.keys(DOCUMENT_TYPE_LABEL) as DocumentType[]).map((t) => (
            <option key={t} value={t}>
              {DOCUMENT_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Title" htmlFor="doc-title" hint="What someone looking for it would call it.">
        <input id="doc-title" className="input" maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </Field>

      {isCertificate && (
        <>
          <div className="form-row">
            <Field label="Certificate number" htmlFor="doc-number">
              <input id="doc-number" className="input" maxLength={80} value={certificateNumber} onChange={(e) => setCertificateNumber(e.target.value)} />
            </Field>
            <Field label="Issued by" htmlFor="doc-authority">
              <input id="doc-authority" className="input" maxLength={160} value={issuingAuthority} onChange={(e) => setIssuingAuthority(e.target.value)} />
            </Field>
          </div>
          <div className="form-row">
            <Field label="Issued on" htmlFor="doc-issued">
              <input id="doc-issued" className="input" type="date" value={issuedDate} onChange={(e) => setIssuedDate(e.target.value)} />
            </Field>
            <Field label="Expires on" htmlFor="doc-expiry" hint="Required: reminders are sent before this date.">
              <input id="doc-expiry" className="input" type="date" value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)} />
            </Field>
          </div>
        </>
      )}
      <FormError message={error} />
    </Dialog>
  );
}

function RemoveDialog({ row, onClose, onRemoved }: { row: DocumentRow; onClose: () => void; onRemoved: () => void }) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await removeDocument(row.id, reason);
      onRemoved();
    } catch (e) {
      setError(errorText(e, 'The document could not be removed.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Remove this document?"
      subtitle={row.title}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={busy} onClick={submit}>
            {busy ? 'Removing…' : 'Remove'}
          </Button>
        </>
      }
    >
      <p className="otp__note">
        It stops appearing in the folder, but the record and the file are kept, with who removed it and why.
      </p>
      <Field label="Why" htmlFor="doc-reason" hint="Optional, but it is what someone reads later.">
        <input id="doc-reason" className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}

function kilobytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
