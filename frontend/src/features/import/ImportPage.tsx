import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchAdminVessels } from '@/api/admin';
import {
  commitImport,
  discardImport,
  downloadTemplate,
  fetchImport,
  fetchImports,
  uploadImport,
  OUTCOME_LABEL,
  type ImportBatch,
  type RowOutcome,
} from '@/api/imports';
import { Button, Chip, ConsoleHeader, EmptyNote, Plate, StatTile } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { formatDateTime } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import './import.css';

/**
 * VMP master-data import (SoW §10).
 *
 * <p>Three steps, and the middle one is the whole point: a file is read and
 * every row is shown with what it would do — add, change, leave alone, or
 * refuse with the reason — and nothing reaches a vessel until that preview is
 * confirmed.
 */
export function ImportPage() {
  const client = useQueryClient();
  const vessels = useQuery({ queryKey: ['admin-vessels'], queryFn: fetchAdminVessels });
  const history = useQuery({ queryKey: ['imports'], queryFn: fetchImports });
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [busy, setBusy] = useState<null | 'upload' | 'commit' | 'discard' | 'template'>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [vesselId, setVesselId] = useState<number | ''>('');
  const fileInput = useRef<HTMLInputElement>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['imports'] });
    client.invalidateQueries({ queryKey: ['vessel-fit'] });
    client.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const run = async (kind: NonNullable<typeof busy>, action: () => Promise<void>) => {
    setBusy(kind);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorText(e, 'Something went wrong. Try again.'));
    } finally {
      setBusy(null);
      setConfirming(false);
    }
  };

  const upload = (file: File) =>
    run('upload', async () => {
      const uploaded = await uploadImport(file);
      // Read it back: what is on screen is the preview the server records as
      // seen, and a commit is refused until it has been (IMP-08).
      setBatch(await fetchImport(uploaded.id));
      refresh();
    });

  const commit = () =>
    run('commit', async () => {
      if (!batch) return;
      setBatch(await commitImport(batch.id));
      refresh();
    });

  const discard = () =>
    run('discard', async () => {
      if (!batch) return;
      setBatch(await discardImport(batch.id));
      refresh();
    });

  const open = (id: number) =>
    run('upload', async () => {
      setBatch(await fetchImport(id));
    });

  if (vessels.error) return <ErrorState error={vessels.error} onRetry={() => vessels.refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Master data' }, { label: 'Import', strong: true }]}
        title="Import vessel equipment"
        subtitle="Upload the VMP spreadsheet. SeaStella shows what each row would do and changes nothing until you confirm."
      />

      <Plate title="1. Get the spreadsheet" subtitle="Start from a blank template, or from a vessel's current equipment to correct it">
        <div className="imp-row">
          <Field label="Vessel" htmlFor="imp-vessel" hint="Leave blank for an empty template.">
            <select id="imp-vessel" className="input" value={vesselId} onChange={(e) => setVesselId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">Blank template</option>
              {(vessels.data ?? []).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name} · IMO {v.imoNumber}
                </option>
              ))}
            </select>
          </Field>
          <Button
            onClick={() => run('template', () => downloadTemplate(vesselId === '' ? undefined : vesselId))}
            disabled={busy !== null}
          >
            <Icon name="report" size={16} />
            {busy === 'template' ? 'Preparing…' : 'Download'}
          </Button>
        </div>
      </Plate>

      <Plate title="2. Upload it" subtitle="Excel (.xlsx). Nothing is changed by uploading.">
        <div className="imp-row">
          <input
            ref={fileInput}
            id="imp-file"
            type="file"
            className="input"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void upload(file);
              e.target.value = '';
            }}
            disabled={busy !== null}
          />
          {busy === 'upload' && <span className="imp-busy">Reading the file…</span>}
        </div>
        <FormError message={error} />
      </Plate>

      {batch && <BatchPanel batch={batch} busy={busy} onCommit={() => setConfirming(true)} onDiscard={discard} />}

      <Plate title="Recent uploads" count={history.data?.length ?? 0} flush>
        {history.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={3} />
          </div>
        ) : (history.data ?? []).length === 0 ? (
          <div className="qlist__empty">
            <EmptyNote>No spreadsheets have been uploaded yet.</EmptyNote>
          </div>
        ) : (
          <ul className="setup">
            {(history.data ?? []).map((b) => (
              <li key={b.id} className="setup-row">
                <div className="setup-row__main">
                  <div className="setup-row__title">
                    {b.fileName}
                    <StatusPill status={b.status} />
                  </div>
                  <div className="setup-row__meta">
                    {b.vessels || 'No vessel matched'} · uploaded by {b.uploadedBy} {formatDateTime(b.uploadedAt)}
                  </div>
                  <div className="setup-row__people">
                    <span>
                      {b.rowCount} rows · {b.newCount} to add · {b.modifiedCount} to change
                      {b.status === 'COMMITTED' ? ` · ${b.appliedCount ?? 0} applied` : ''}
                      {b.invalidCount > 0 ? ` · ${b.invalidCount} refused` : ''}
                    </span>
                  </div>
                </div>
                <div className="setup-row__actions">
                  <Button variant="ghost" onClick={() => open(b.id)} disabled={busy !== null}>
                    {b.status === 'PREVIEW' ? 'Continue' : 'View'}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Plate>

      {confirming && batch && (
        <Dialog
          title="Apply these changes?"
          subtitle={`${batch.fileName} · ${batch.vessels ?? ''}`}
          onClose={() => setConfirming(false)}
          width={520}
          footer={
            <>
              <Button onClick={() => setConfirming(false)}>Cancel</Button>
              <Button variant="primary" disabled={busy !== null} onClick={commit}>
                {busy === 'commit' ? 'Applying…' : `Apply ${changeCount(batch)}`}
              </Button>
            </>
          }
        >
          <p className="otp__note">
            {batch.newCount} {batch.newCount === 1 ? 'spare is' : 'spares are'} added and {batch.modifiedCount}{' '}
            {batch.modifiedCount === 1 ? 'is' : 'are'} changed. Rows with no change, refused rows and repeats are left
            alone. This cannot be undone from here — a mistake is corrected by another import or by editing the spare.
          </p>
        </Dialog>
      )}
    </div>
  );
}

function BatchPanel({
  batch,
  busy,
  onCommit,
  onDiscard,
}: {
  batch: ImportBatch;
  busy: string | null;
  onCommit: () => void;
  onDiscard: () => void;
}) {
  const [filter, setFilter] = useState<RowOutcome | 'ALL'>('ALL');
  const counts: Record<RowOutcome, number> = {
    NEW: batch.newCount,
    MODIFIED: batch.modifiedCount,
    UNCHANGED: batch.unchangedCount,
    INVALID: batch.invalidCount,
    DUPLICATE: batch.duplicateCount,
  };
  const rows = filter === 'ALL' ? batch.rows : batch.rows.filter((r) => r.outcome === filter);

  return (
    <Plate
      title={batch.status === 'PREVIEW' ? '3. Check what it would do' : 'What this upload did'}
      subtitle={`${batch.fileName} · ${batch.vessels || 'no vessel matched'}`}
      action={
        batch.status === 'PREVIEW' ? (
          <div className="imp-actions">
            <Button variant="ghost" onClick={onDiscard} disabled={busy !== null}>
              Discard
            </Button>
            <Button variant="primary" onClick={onCommit} disabled={busy !== null || !batch.canCommit}>
              Apply {changeCount(batch)}
            </Button>
          </div>
        ) : (
          <StatusPill status={batch.status} />
        )
      }
    >
      <div className="imp-tiles">
        <StatTile label="Rows read" value={batch.rowCount} icon="report" />
        <StatTile label="To add" value={batch.newCount} icon="spare" />
        <StatTile label="To change" value={batch.modifiedCount} icon="wrench" />
        <StatTile label="No change" value={batch.unchangedCount} icon="check" />
        <StatTile label="Refused" value={batch.invalidCount} icon="bell" tone={batch.invalidCount > 0 ? 'warn' : undefined} />
      </div>

      {batch.status === 'COMMITTED' && (
        <div className="notice notice--ok" role="status">
          <Icon name="check" size={20} />
          <div>
            <p className="notice__title">
              {batch.appliedCount} {batch.appliedCount === 1 ? 'row' : 'rows'} applied
            </p>
            <p className="notice__body">
              Committed by {batch.committedBy} {formatDateTime(batch.committedAt)}.
            </p>
          </div>
        </div>
      )}

      <div className="imp-filters">
        <Chip on={filter === 'ALL'} onClick={() => setFilter('ALL')} count={batch.rowCount}>
          All rows
        </Chip>
        {(Object.keys(counts) as RowOutcome[]).map((outcome) => (
          <Chip key={outcome} on={filter === outcome} onClick={() => setFilter(outcome)} count={counts[outcome]}>
            {OUTCOME_LABEL[outcome]}
          </Chip>
        ))}
      </div>

      {rows.length === 0 ? (
        <EmptyNote>No rows of that kind in this file.</EmptyNote>
      ) : (
        <ul className="imp-rows">
          {rows.map((row) => (
            <li key={row.id} className={`imp-row-item imp-row-item--${row.outcome.toLowerCase()}`}>
              <span className="imp-row-item__n">{row.rowNumber}</span>
              <div className="imp-row-item__main">
                <div className="imp-row-item__title">
                  <b>{row.vmpRef ?? '—'}</b>
                  <span>{row.spareName ?? '(no description)'}</span>
                  <OutcomePill outcome={row.outcome} applied={row.applied} />
                </div>
                <div className="imp-row-item__meta">
                  {row.vesselName ? `${row.vesselName} · IMO ${row.imoNumber}` : `IMO ${row.imoNumber ?? '—'}`}
                </div>
                {row.messages && <p className="imp-row-item__msg">{row.messages}</p>}
                {row.changes.length > 0 && (
                  <ul className="imp-changes">
                    {row.changes.map((c) => (
                      <li key={c.field}>
                        <span className="imp-changes__field">{c.field}</span>
                        {c.before ? <s>{c.before}</s> : <i>empty</i>}
                        <Icon name="check" size={12} />
                        <b>{c.after ?? 'empty'}</b>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Plate>
  );
}

/** "3 changes", "1 change" - the button says exactly what it will do. */
function changeCount(batch: ImportBatch) {
  const n = batch.newCount + batch.modifiedCount;
  return `${n} ${n === 1 ? 'change' : 'changes'}`;
}

function OutcomePill({ outcome, applied }: { outcome: RowOutcome; applied: boolean }) {
  // The four due-status colours are reserved for maintenance; a refused row is
  // marked by its border and its message, not by borrowing one of them.
  const tone = outcome === 'NEW' || outcome === 'MODIFIED' ? 'accent' : 'neutral';
  return (
    <Pill size="sm" tone={tone}>
      {applied ? 'Applied' : OUTCOME_LABEL[outcome]}
    </Pill>
  );
}

function StatusPill({ status }: { status: ImportBatch['status'] }) {
  if (status === 'COMMITTED') {
    return (
      <Pill size="sm" tone="accent">
        Applied
      </Pill>
    );
  }
  if (status === 'DISCARDED') return <Pill size="sm">Discarded</Pill>;
  return (
    <Pill size="sm" tone="approaching">
      Waiting for confirmation
    </Pill>
  );
}
