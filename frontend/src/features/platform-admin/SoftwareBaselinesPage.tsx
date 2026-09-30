import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSoftwareBaseline,
  deleteSoftwareBaseline,
  fetchSoftwareBaselines,
  updateSoftwareBaseline,
  uploadSoftwareBaselineSheet,
  type BaselineUploadResult,
  type SoftwareBaselineForm,
  type SoftwareBaselineRow,
} from '@/api/softwareBaselines';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { Pill } from '@/design-system/StatusBadge';
import { ErrorState, LoadingState } from '@/design-system/States';
import { errorText } from '@/features/admin/AdminParts';
import './software-baselines.css';

/**
 * The software master sheet (SoW §9.3).
 *
 * <p>A vessel's equipment records the version installed on it. On its own that
 * number says nothing — 5.5 is only a finding once the manufacturer has
 * shipped 5.6. This is the other half: one row per equipment model, naming the
 * release it ought to be on, and every vessel's equipment list compares
 * against it.
 *
 * <p>Most of the list arrives as the client's own spreadsheet. A row corrected
 * here is marked "By hand", which is what stops a later upload of a stale
 * sheet from quietly undoing the correction.
 */
export function SoftwareBaselinesPage() {
  const client = useQueryClient();
  const baselines = useQuery({ queryKey: ['software-baselines'], queryFn: fetchSoftwareBaselines });
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<SoftwareBaselineRow | null | undefined>(undefined);
  const [result, setResult] = useState<BaselineUploadResult | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['software-baselines'] });
    // Every vessel's equipment list carries a comparison against these rows.
    client.invalidateQueries({ queryKey: ['vessel-fit'] });
  };

  const upload = useMutation({
    mutationFn: uploadSoftwareBaselineSheet,
    onSuccess: (r) => {
      setResult(r);
      setError(null);
      refresh();
    },
    onError: (e) => {
      setResult(null);
      setError(errorText(e, 'The sheet could not be read.'));
    },
    onSettled: () => {
      if (input.current) input.current.value = '';
    },
  });

  const remove = useMutation({
    mutationFn: deleteSoftwareBaseline,
    onSuccess: () => {
      setNotice('Baseline removed.');
      refresh();
    },
    onError: (e) => setError(errorText(e, 'The baseline could not be removed.')),
  });

  const rows = baselines.data ?? [];
  const shown = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.make, r.model, r.equipmentName, r.latestVersion].some((f) => f?.toLowerCase().includes(q)),
    );
  }, [rows, search]);

  if (baselines.error) return <ErrorState error={baselines.error} onRetry={() => baselines.refetch()} />;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform', strong: true }]}
        title="Software baselines"
        subtitle="The latest release each equipment model should be running. Every vessel's equipment list is compared against this list."
      />

      {notice && (
        <div className="notice notice--ok" role="status">
          <div>
            <p className="notice__title">{notice}</p>
          </div>
        </div>
      )}

      {result && <UploadSummary result={result} onDismiss={() => setResult(null)} />}
      <FormError message={error} />

      <Plate
        title="Equipment models"
        count={rows.length}
        subtitle="Matched to equipment on make and model, ignoring case, spaces and punctuation."
        action={
          <div className="plate-actions">
            <input
              ref={input}
              type="file"
              accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              hidden
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) upload.mutate(file);
              }}
            />
            <Button variant="ghost" disabled={upload.isPending} onClick={() => input.current?.click()}>
              {upload.isPending ? 'Reading…' : 'Upload master sheet'}
            </Button>
            <Button variant="primary" onClick={() => setEditing(null)}>
              Add model
            </Button>
          </div>
        }
        flush
      >
        <div className="baselines__bar">
          <input
            className="input input--search"
            type="search"
            placeholder="Search make, model, version…"
            aria-label="Search make, model, version"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        {baselines.isLoading ? (
          <div style={{ padding: '0 20px 20px' }}>
            <LoadingState rows={5} />
          </div>
        ) : shown.length === 0 ? (
          <div className="baselines__empty">
            <EmptyNote>
              {rows.length === 0
                ? 'No baselines yet. Upload the master sheet, or add one model at a time.'
                : 'Nothing matches that search.'}
            </EmptyNote>
          </div>
        ) : (
          <div className="baselines__scroll">
            <table className="baselines__table">
              <thead>
                <tr>
                  <th>Equipment</th>
                  <th>Make</th>
                  <th>Model</th>
                  <th>Latest version</th>
                  <th>Source</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {shown.map((row) => (
                  <tr key={row.id}>
                    <td>{row.equipmentName ?? <span className="equip__missing">—</span>}</td>
                    <td>{row.make}</td>
                    <td className="mono">{row.model}</td>
                    <td className="mono">
                      <b>{row.latestVersion}</b>
                    </td>
                    <td>
                      <Pill tone={row.source === 'RECORDED' ? 'accent' : 'neutral'} size="sm">
                        {row.source === 'RECORDED' ? 'By hand' : 'From sheet'}
                      </Pill>
                    </td>
                    <td className="baselines__actions">
                      <Button variant="ghost" onClick={() => setEditing(row)}>
                        Edit
                      </Button>
                      <Button
                        variant="ghost"
                        disabled={remove.isPending}
                        onClick={() => {
                          if (window.confirm(`Remove the baseline for ${row.make} ${row.model}?`)) {
                            remove.mutate(row.id);
                          }
                        }}
                      >
                        Remove
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Plate>

      <Plate title="What the sheet needs" subtitle="The file is the client's own; the headings are matched by name.">
        <div className="baselines__help">
          <p>
            Any spreadsheet with a heading row naming <b>Make</b>, <b>Model</b> and <b>Latest software version</b>
            {' '}will be read — a sheet may also carry an <b>Equipment</b> column, which is kept for display. Common
            variations on those headings are recognised, the heading row does not have to be the first row, and every
            tab in the workbook is looked at.
          </p>
          <p>
            A row without a make is skipped and reported: make and model together are what a vessel's equipment is
            matched on, and a model on its own would match the wrong device.
          </p>
        </div>
      </Plate>

      {editing !== undefined && (
        <BaselineDialog
          baseline={editing}
          onClose={() => setEditing(undefined)}
          onSaved={(saved) => {
            setEditing(undefined);
            setNotice(`${saved.make} ${saved.model} saved.`);
            refresh();
          }}
        />
      )}
    </div>
  );
}

/** What one upload did, per row, so nothing changes silently. */
function UploadSummary({ result, onDismiss }: { result: BaselineUploadResult; onDismiss: () => void }) {
  const quiet = result.skipped.length === 0 && result.warnings.length === 0;
  return (
    <div className={`notice ${quiet ? 'notice--ok' : 'notice--wait'}`} role="status">
      <div>
        <p className="notice__title">
          {result.rowsRead} row{result.rowsRead === 1 ? '' : 's'} read · {result.added} added ·{' '}
          {result.updated} updated · {result.unchanged} unchanged
        </p>
        {result.skipped.length > 0 && (
          <>
            <p className="notice__body">
              Kept as they were, because they had been corrected by hand:
            </p>
            <ul className="baselines__notes">
              {result.skipped.map((s) => (
                <li key={s}>{s}</li>
              ))}
            </ul>
          </>
        )}
        {result.warnings.length > 0 && (
          <>
            <p className="notice__body">Rows the sheet could not be read from:</p>
            <ul className="baselines__notes">
              {result.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </>
        )}
      </div>
      <Button variant="ghost" onClick={onDismiss}>
        Dismiss
      </Button>
    </div>
  );
}

function BaselineDialog({
  baseline,
  onClose,
  onSaved,
}: {
  baseline: SoftwareBaselineRow | null;
  onClose: () => void;
  onSaved: (saved: SoftwareBaselineRow) => void;
}) {
  const [form, setForm] = useState<SoftwareBaselineForm>({
    make: baseline?.make ?? '',
    model: baseline?.model ?? '',
    equipmentName: baseline?.equipmentName ?? '',
    latestVersion: baseline?.latestVersion ?? '',
    notes: baseline?.notes ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (key: keyof SoftwareBaselineForm) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const valid = form.make.trim() !== '' && form.model.trim() !== '' && form.latestVersion.trim() !== '';

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onSaved(baseline ? await updateSoftwareBaseline(baseline.id, form) : await createSoftwareBaseline(form));
    } catch (e) {
      setError(errorText(e, 'The baseline could not be saved.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={baseline ? 'Edit baseline' : 'Add a model'}
      subtitle={
        baseline
          ? 'Saving marks this row as entered by hand, so the next sheet upload leaves it alone.'
          : 'The make and model are what a vessel’s equipment is matched on.'
      }
      onClose={onClose}
      width={620}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={save}>
            {busy ? 'Saving…' : 'Save baseline'}
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Make" htmlFor="baseline-make" hint="Furuno, JRC, Sperry…">
          <input id="baseline-make" className="input" maxLength={120} value={form.make} onChange={set('make')} />
        </Field>
        <Field label="Model" htmlFor="baseline-model" hint="FA-170, JLR-7800…">
          <input
            id="baseline-model"
            className="input mono"
            maxLength={120}
            value={form.model}
            onChange={set('model')}
          />
        </Field>
        <Field label="Latest software version" htmlFor="baseline-version" hint="As the manufacturer prints it: 5.6">
          <input
            id="baseline-version"
            className="input mono"
            maxLength={64}
            value={form.latestVersion}
            onChange={set('latestVersion')}
          />
        </Field>
        <Field label="Equipment name" htmlFor="baseline-equipment" hint="Optional. What the sheet calls it: AIS, ECDIS.">
          <input
            id="baseline-equipment"
            className="input"
            maxLength={200}
            value={form.equipmentName}
            onChange={set('equipmentName')}
          />
        </Field>
        <Field label="Notes" htmlFor="baseline-notes" hint="Optional. Where this version came from, for whoever reads it next.">
          <input id="baseline-notes" className="input" maxLength={500} value={form.notes} onChange={set('notes')} />
        </Field>
      </div>
      <FormError message={error} />
    </Dialog>
  );
}
