import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  downloadPreSurvey,
  fetchPreSurveyDraft,
  generatePreSurvey,
  type FormCell,
  type PreSurveyRow,
} from '@/api/vessels';
import { Button, ConsoleHeader, Plate } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { errorText } from '@/features/admin/AdminParts';
import './vessels.css';

/**
 * The QM-F11 pre-survey form, filled from the vessel's equipment register and
 * reviewed before it is exported (developer brief, Feature 2).
 *
 * <p>It reads in the form's own order and sections. What the records do not
 * hold is marked amber; typing over any value changes this form only - the
 * vessel's records stay as they are, and the audit trail notes what was typed.
 * Export prints it on the client's own QM-F11 file.
 */
export function PreSurveyPage() {
  const vesselId = Number(useParams().vesselId);
  const client = useQueryClient();
  const draft = useQuery({
    queryKey: ['pre-survey-draft', vesselId],
    queryFn: () => fetchPreSurveyDraft(vesselId),
    enabled: Number.isFinite(vesselId),
  });
  const [values, setValues] = useState<Record<string, string>>({});
  const [remarks, setRemarks] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<PreSurveyRow | null>(null);

  const recorded = useMemo(() => {
    const out: Record<string, string> = {};
    for (const s of draft.data?.sections ?? []) {
      for (const c of [s.status, s.error, ...s.rows.flatMap((r) => r.cells)]) if (c) out[c.key] = c.value;
    }
    return out;
  }, [draft.data]);

  useEffect(() => setValues(recorded), [recorded]);

  if (draft.error) return <ErrorState error={draft.error} onRetry={() => draft.refetch()} />;

  const changed = Object.keys(values).filter((k) => (values[k] ?? '') !== (recorded[k] ?? '')).length;

  const exportPdf = async () => {
    setBusy(true);
    setError(null);
    try {
      const typed: Record<string, string> = {};
      for (const k of Object.keys(values)) if ((values[k] ?? '') !== (recorded[k] ?? '')) typed[k] = values[k];
      const row = await generatePreSurvey(vesselId, typed, remarks);
      setSaved(row);
      client.invalidateQueries({ queryKey: ['vessel-pre-surveys', vesselId] });
      client.invalidateQueries({ queryKey: ['documents'] });
      await downloadPreSurvey(row);
    } catch (e) {
      setError(errorText(e, 'The form could not be exported.'));
    } finally {
      setBusy(false);
    }
  };

  const field = (c: FormCell) => {
    const value = values[c.key] ?? '';
    const gap = c.missing && !value.trim();
    const typed = value !== (recorded[c.key] ?? '');
    const cls = `input psf__input${gap ? ' psf__input--gap' : ''}${typed ? ' psf__input--typed' : ''}`;
    const set = (v: string) => setValues((all) => ({ ...all, [c.key]: v }));
    return c.options ? (
      <select className={cls} aria-label={c.label} value={value} onChange={(e) => set(e.target.value)}>
        <option value="">{gap ? 'Not recorded' : '—'}</option>
        {c.options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    ) : (
      <input
        className={cls}
        aria-label={c.label}
        value={value}
        maxLength={200}
        placeholder={gap ? 'Not recorded' : ''}
        onChange={(e) => set(e.target.value)}
      />
    );
  };

  const d = draft.data;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[
          { label: <Link to={`/fleet/vessels/${vesselId}`}>{d?.vesselName ?? 'Vessel'}</Link> },
          { label: 'Pre-Survey Form', strong: true },
        ]}
        title="Pre-Survey Form"
        subtitle="QM-F11 · filled from the vessel's equipment register. Review it, then export the PDF."
        actions={
          <div className="plate-actions">
            <Link to={`/fleet/vessels/${vesselId}`} className="cbtn cbtn--secondary cbtn--md">
              Back to vessel
            </Link>
            <Button variant="primary" disabled={!d || busy} onClick={exportPdf}>
              {busy ? 'Exporting…' : 'Export PDF'}
            </Button>
          </div>
        }
      />

      {saved && (
        <div className="notice notice--ok" role="status">
          <div>
            <p className="notice__title">Exported and saved on the vessel</p>
            <p className="notice__body">
              The PDF has downloaded and is filed with the vessel's documents.{' '}
              <button type="button" className="linkbtn" onClick={() => downloadPreSurvey(saved).catch(() => undefined)}>
                Download again
              </button>
            </p>
          </div>
        </div>
      )}
      <FormError message={error} />

      {!d ? (
        <LoadingState rows={8} />
      ) : (
        <>
          <div className="psf__legend">
            <span>
              <i className="psf__swatch psf__swatch--gap" /> Not in the records{d.missingCount ? ` (${d.missingCount})` : ''}: prints blank unless you type it
            </span>
            <span>
              <i className="psf__swatch psf__swatch--typed" /> Typed here: this form only{changed ? ` (${changed})` : ''}; the vessel's records do not change
            </span>
            <span>Dates print in full, like {d.dateExample}.</span>
          </div>

          {d.sections.map((s) => (
            <Plate key={s.title} title={s.title} flush>
              {(s.status || s.error) && (
                <div className="psf__status">
                  {s.status && (
                    <label>
                      <span>Status</span>
                      {field(s.status)}
                    </label>
                  )}
                  {s.error && (
                    <label className="psf__error">
                      <span>Error (IF any)</span>
                      {field(s.error)}
                    </label>
                  )}
                </div>
              )}
              <div className="psf__table" role="table" aria-label={s.title}>
                {s.rows.map((r, i) => (
                  <div className="psf__row" role="row" key={`${r.label}-${i}`}>
                    {r.label && (
                      <div className="psf__label" role="rowheader">
                        {r.label}
                      </div>
                    )}
                    {r.cells.map((c) => (
                      <label className="psf__cell" role="cell" key={c.key}>
                        <span>{c.label}</span>
                        {field(c)}
                      </label>
                    ))}
                  </div>
                ))}
              </div>
            </Plate>
          ))}

          <Plate title="Page 2: remarks" subtitle="Printed under the header on page 2: survey port and date, agent, anything the vessel should know.">
            <textarea
              className="textarea"
              rows={3}
              maxLength={1000}
              value={remarks}
              aria-label="Remarks"
              placeholder="Survey at Singapore, 4 Nov 2026. Agent: …"
              onChange={(e) => setRemarks(e.target.value)}
            />
            <p className="otp__note psf__notes">The page's notes for the GMDSS radio survey and the VDR APT print as the form has them.</p>
          </Plate>
        </>
      )}
    </div>
  );
}
