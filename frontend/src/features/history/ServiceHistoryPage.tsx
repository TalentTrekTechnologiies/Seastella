import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  downloadServiceHistoryExcel,
  downloadServiceHistoryTemplate,
  fetchAdminVessels,
  fetchFleetServiceHistory,
  uploadServiceHistory,
  type FleetServiceRecord,
  type HistoryUpload,
} from '@/api/admin';
import { fetchVesselFit } from '@/api/serviceRequests';
import { downloadReportPdf } from '@/api/reports';
import type { Role } from '@/api/types';
import { Button, Chip, ConsoleHeader, EmptyNote, Plate, StatTile } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDate } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import { RecordServiceDialog } from '@/features/admin/ServiceHistoryPanel';
import './history.css';

/**
 * SERVICE HISTORY — the whole fleet's, in one place (SoW §6.3, §9.3).
 *
 * <p>Every service, repair and part replaced, on every vessel in scope: what
 * the platform saw as requests completed, and what was entered by hand or
 * brought in from the fleet's own spreadsheets. The same history is on each
 * equipment item; this is where it is read across the fleet and uploaded in
 * bulk.
 */
export function ServiceHistoryPage({ role }: { role: Role }) {
  const client = useQueryClient();
  const canRecord = role === 'TECHNICAL_HEAD' || role === 'PLATFORM_ADMIN';
  const [vesselId, setVesselId] = useState<number | ''>('');
  const [search, setSearch] = useState('');
  const [source, setSource] = useState<'ALL' | 'PLATFORM' | 'RECORDED'>('ALL');
  const [adding, setAdding] = useState(false);
  const [recordFor, setRecordFor] = useState<{ id: number; name: string } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [downloading, setDownloading] = useState<null | 'pdf' | 'xlsx'>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const download = async (kind: 'pdf' | 'xlsx') => {
    setDownloading(kind);
    setDownloadError(null);
    const target = vesselId === '' ? undefined : vesselId;
    try {
      if (kind === 'pdf') await downloadReportPdf('service-history', target);
      else await downloadServiceHistoryExcel({ vesselId: target });
    } catch (e) {
      setDownloadError(errorText(e, 'The download could not be prepared.'));
    } finally {
      setDownloading(null);
    }
  };

  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });
  const history = useQuery({
    queryKey: ['fleet-service-history', vesselId],
    queryFn: () => fetchFleetServiceHistory(vesselId === '' ? undefined : vesselId),
  });

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['fleet-service-history'] });
    client.invalidateQueries({ queryKey: ['vessel-fit'] });
    client.invalidateQueries({ queryKey: ['vessel-maintenance'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const all = history.data ?? [];
  const shown = useMemo(() => {
    const q = search.trim().toLowerCase();
    return all.filter((r) => {
      if (source !== 'ALL' && r.source !== source) return false;
      if (!q) return true;
      return [r.vesselName, r.sparePath, r.spareName, r.workPerformed, r.partsUsed, r.performedBy, r.requestNumber, r.notes]
        .some((v) => v?.toLowerCase().includes(q));
    });
  }, [all, search, source]);

  const thisYear = new Date().getFullYear();
  const lastYear = all.filter((r) => new Date(r.serviceDate).getFullYear() === thisYear).length;
  const withParts = all.filter((r) => r.partsUsed).length;
  const fromPlatform = all.filter((r) => r.source === 'PLATFORM').length;

  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Fleet' }, { label: 'Service history', strong: true }]}
        title="Service history"
        subtitle="Every service, repair and part replaced across the fleet — from completed requests, entered by hand, or uploaded from Excel."
        actions={
          <>
            <Button onClick={() => download('pdf')} disabled={downloading !== null} title="The history as a printable PDF — the vessel chosen below, or all">
              <Icon name="file" size={16} />
              {downloading === 'pdf' ? 'Preparing…' : 'Download PDF'}
            </Button>
            <Button onClick={() => download('xlsx')} disabled={downloading !== null} title="The history as Excel, in the same columns the upload reads">
              <Icon name="report" size={16} />
              {downloading === 'xlsx' ? 'Preparing…' : 'Download Excel'}
            </Button>
            {canRecord && (
            <>
              <Button onClick={() => setUploading(true)}>
                <Icon name="report" size={16} />
                Upload from Excel
              </Button>
              <Button variant="primary" onClick={() => setAdding(true)} disabled={vesselId === ''} title={vesselId === '' ? 'Choose a vessel first' : undefined}>
                Add work done
              </Button>
            </>
            )}
          </>
        }
      />
      <FormError message={downloadError} />

      <div className="hist-tiles">
        <StatTile label="Entries" value={all.length} icon="history" />
        <StatTile label={`In ${thisYear}`} value={lastYear} icon="gauge" />
        <StatTile label="With parts replaced" value={withParts} icon="spare" />
        <StatTile label="From service requests" value={fromPlatform} icon="wrench" />
      </div>

      <Plate
        title="History"
        count={shown.length}
        subtitle={vesselId === '' ? 'All vessels, newest first' : 'Newest first'}
        flush
      >
        <div className="hist-filters">
          <Field label="Vessel" htmlFor="hist-vessel">
            <select
              id="hist-vessel"
              className="input"
              value={vesselId}
              onChange={(e) => setVesselId(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">All vessels</option>
              {(vessels.data ?? []).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name} · IMO {v.imoNumber}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Search" htmlFor="hist-search">
            <input
              id="hist-search"
              className="input"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Equipment, work, part, engineer, request no."
            />
          </Field>
          <div className="hist-chips">
            <Chip on={source === 'ALL'} onClick={() => setSource('ALL')} count={all.length}>
              All
            </Chip>
            <Chip on={source === 'PLATFORM'} onClick={() => setSource('PLATFORM')} count={fromPlatform}>
              Service requests
            </Chip>
            <Chip on={source === 'RECORDED'} onClick={() => setSource('RECORDED')} count={all.length - fromPlatform}>
              Entered / uploaded
            </Chip>
          </div>
        </div>

        {history.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={6} />
          </div>
        ) : history.error ? (
          <div style={{ padding: '0 20px 20px' }}>
            <ErrorState error={history.error} onRetry={() => history.refetch()} />
          </div>
        ) : shown.length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>
              {all.length === 0
                ? 'No service history yet. Add work done on a vessel, or upload the fleet’s history from Excel.'
                : 'Nothing matches that search.'}
            </EmptyNote>
          </div>
        ) : (
          <div className="hist">
            <div className="hist__head">
              <span>Date</span>
              <span>Vessel</span>
              <span>Equipment</span>
              <span>Work done</span>
              <span>Source</span>
            </div>
            {shown.map((r) => (
              <HistoryRow key={r.id} record={r} linkEquipment={role === 'TECHNICAL_HEAD'} />
            ))}
          </div>
        )}
      </Plate>

      {adding && vesselId !== '' && (
        <PickEquipmentDialog
          vesselId={vesselId}
          vesselName={vessels.data?.find((v) => v.id === vesselId)?.name ?? 'this vessel'}
          onClose={() => setAdding(false)}
          onPicked={(spare) => {
            setAdding(false);
            setRecordFor(spare);
          }}
        />
      )}
      {recordFor && (
        <RecordServiceDialog
          spareId={recordFor.id}
          spareName={recordFor.name}
          onClose={() => setRecordFor(null)}
          onSaved={() => {
            setRecordFor(null);
            refresh();
          }}
        />
      )}
      {uploading && (
        <UploadHistoryDialog
          vessels={vessels.data ?? []}
          initialVesselId={vesselId}
          onClose={() => setUploading(false)}
          onApplied={() => {
            setUploading(false);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function HistoryRow({ record: r, linkEquipment }: { record: FleetServiceRecord; linkEquipment: boolean }) {
  const equipment = `${r.sparePath ?? ''} ${r.spareName ?? ''}`.trim() || '—';
  return (
    <div className="hist__row">
      <span className="hist__date">{formatDate(r.serviceDate)}</span>
      <span className="hist__vessel">{r.vesselName ?? '—'}</span>
      <span className="hist__equipment">
        {linkEquipment ? <Link to={`/fleet/vessels/${r.vesselId}?spare=${r.spareId}`}>{equipment}</Link> : equipment}
      </span>
      <span className="hist__work">
        <span>{r.workPerformed}</span>
        <span className="hist__meta">
          {r.partsUsed && <span>Parts: {r.partsUsed}</span>}
          {r.performedBy && <span>By {r.performedBy}</span>}
          {r.recordedBy && <span>Entered by {r.recordedBy}</span>}
        </span>
        {r.notes && <span className="hist__notes">{r.notes}</span>}
      </span>
      <span className="hist__source">
        {r.source === 'PLATFORM' ? (
          <Pill size="sm" tone="normal">
            {r.requestNumber ?? 'Request'}
          </Pill>
        ) : (
          <Pill size="sm">Recorded</Pill>
        )}
      </span>
    </div>
  );
}

/** Which equipment the work was done on, then the usual "Add work done" form. */
function PickEquipmentDialog({
  vesselId,
  vesselName,
  onClose,
  onPicked,
}: {
  vesselId: number;
  vesselName: string;
  onClose: () => void;
  onPicked: (spare: { id: number; name: string }) => void;
}) {
  const fit = useQuery({ queryKey: ['vessel-fit', vesselId], queryFn: () => fetchVesselFit(vesselId) });
  const [spareId, setSpareId] = useState<number | ''>('');
  const spares = fit.data?.spares ?? [];
  const chosen = spares.find((s) => s.id === spareId);

  return (
    <Dialog
      title="Add work done"
      subtitle={vesselName}
      onClose={onClose}
      width={520}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!chosen} onClick={() => chosen && onPicked({ id: chosen.id, name: chosen.name })}>
            Next
          </Button>
        </>
      }
    >
      {fit.isLoading ? (
        <LoadingState rows={2} />
      ) : (
        <Field label="Equipment" htmlFor="hist-pick" hint="The item the service, repair or replacement was done on.">
          <select
            id="hist-pick"
            className="input"
            value={spareId}
            onChange={(e) => setSpareId(e.target.value ? Number(e.target.value) : '')}
          >
            <option value="">Choose equipment</option>
            {spares.map((s) => (
              <option key={s.id} value={s.id}>
                {' '.repeat(Math.max(0, s.depth) * 2)}
                {s.path} {s.name}
              </option>
            ))}
          </select>
        </Field>
      )}
    </Dialog>
  );
}

/**
 * Upload: the file is checked first and every row shown with what it would
 * do; nothing is added until the second step, and then all rows or none.
 */
function UploadHistoryDialog({
  vessels,
  initialVesselId,
  onClose,
  onApplied,
}: {
  vessels: { id: number; name: string; imoNumber: string }[];
  initialVesselId: number | '';
  onClose: () => void;
  onApplied: () => void;
}) {
  const [vesselId, setVesselId] = useState<number | ''>(initialVesselId);
  const [file, setFile] = useState<File | null>(null);
  const [check, setCheck] = useState<HistoryUpload | null>(null);
  const [busy, setBusy] = useState<null | 'check' | 'apply' | 'template'>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<'ALL' | 'ADD' | 'SKIP' | 'ERROR'>('ALL');
  const input = useRef<HTMLInputElement>(null);
  const target = vesselId === '' ? undefined : vesselId;

  const run = async (kind: NonNullable<typeof busy>, action: () => Promise<void>) => {
    setBusy(kind);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorText(e, 'Something went wrong. Try again.'));
    } finally {
      setBusy(null);
    }
  };

  const checkFile = (chosen: File) =>
    run('check', async () => {
      setFile(chosen);
      setCheck(null);
      setFilter('ALL');
      setCheck(await uploadServiceHistory(chosen, false, target));
    });

  const apply = () =>
    run('apply', async () => {
      if (!file) return;
      await uploadServiceHistory(file, true, target);
      onApplied();
    });

  const rows = (check?.rows ?? []).filter((r) => filter === 'ALL' || r.status === filter);

  return (
    <Dialog
      title="Upload service history"
      subtitle="From Excel (.xlsx) · checked first, nothing is added until you confirm"
      onClose={onClose}
      width={900}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!check || check.errorCount > 0 || check.addCount === 0 || busy !== null}
            onClick={apply}
          >
            {busy === 'apply' ? 'Adding…' : check ? `Add ${check.addCount} ${check.addCount === 1 ? 'entry' : 'entries'}` : 'Add'}
          </Button>
        </>
      }
    >
      <div className="hist-upload">
        <Field label="Vessel" htmlFor="hist-up-vessel" hint="Optional when the sheet has an IMO Number column.">
          <select
            id="hist-up-vessel"
            className="input"
            value={vesselId}
            onChange={(e) => {
              setVesselId(e.target.value ? Number(e.target.value) : '');
              setCheck(null);
              setFile(null);
            }}
          >
            <option value="">Named in the file (IMO Number column)</option>
            {vessels.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name} · IMO {v.imoNumber}
              </option>
            ))}
          </select>
        </Field>
        <div className="hist-upload__buttons">
          <input
            ref={input}
            type="file"
            hidden
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(e) => {
              const chosen = e.target.files?.[0];
              if (chosen) void checkFile(chosen);
              e.target.value = '';
            }}
          />
          <Button onClick={() => run('template', downloadServiceHistoryTemplate)} disabled={busy !== null}>
            {busy === 'template' ? 'Preparing…' : 'Download template'}
          </Button>
          <Button variant="primary" onClick={() => input.current?.click()} disabled={busy !== null}>
            {busy === 'check' ? 'Checking…' : file ? 'Choose another file' : 'Choose Excel file'}
          </Button>
        </div>
      </div>
      <p className="otp__note">
        Columns read: <b>Equipment</b> (VMP number like 13.1, or its name), <b>Date</b>, <b>Work done</b>, and
        optionally <b>IMO Number</b>, <b>Parts replaced</b>, <b>Performed by</b>, <b>Notes</b>. A row already in the
        history is skipped, so uploading the same file twice adds nothing.
      </p>

      {check && (
        <>
          <div className="hist-chips hist-chips--upload">
            <Chip on={filter === 'ALL'} onClick={() => setFilter('ALL')} count={check.rowCount}>
              All rows
            </Chip>
            <Chip on={filter === 'ADD'} onClick={() => setFilter('ADD')} count={check.addCount}>
              Will add
            </Chip>
            <Chip on={filter === 'SKIP'} onClick={() => setFilter('SKIP')} count={check.skipCount}>
              Already there
            </Chip>
            <Chip on={filter === 'ERROR'} onClick={() => setFilter('ERROR')} count={check.errorCount}>
              Problems
            </Chip>
          </div>
          {check.errorCount > 0 && (
            <p className="form-error" role="alert">
              {check.errorCount} {check.errorCount === 1 ? 'row has a problem' : 'rows have problems'}. Correct the
              file and choose it again — nothing is added while any row has a problem.
            </p>
          )}
          <div className="hist-check">
            {rows.map((r) => (
              <div key={r.rowNumber} className={`hist-check__row hist-check__row--${r.status.toLowerCase()}`}>
                <span className="hist-check__num">{r.rowNumber}</span>
                <span>
                  <b>{r.equipment ?? '—'}</b>
                  {r.vessel && <span className="hist__meta"> · {r.vessel}</span>}
                  <br />
                  <span>
                    {r.date ? formatDate(r.date) : '—'} · {r.work ?? '—'}
                  </span>
                  {r.parts && <span className="hist__meta"> · Parts: {r.parts}</span>}
                  {r.message && <span className="hist-check__msg">{r.message}</span>}
                </span>
                <span>
                  {r.status === 'ADD' ? (
                    <Pill size="sm" tone="normal">Add</Pill>
                  ) : r.status === 'SKIP' ? (
                    <Pill size="sm">Already there</Pill>
                  ) : (
                    <Pill size="sm" tone="overdue">Problem</Pill>
                  )}
                </span>
              </div>
            ))}
          </div>
        </>
      )}
      <FormError message={error} />
    </Dialog>
  );
}
