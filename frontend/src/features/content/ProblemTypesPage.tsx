import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createProblemType,
  fetchFlows,
  fetchProblemTypes,
  reorderProblemTypes,
  updateProblemType,
  type ProblemTypeRow,
} from '@/api/content';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { Dialog, Field, FormError } from '@/design-system/Dialog';
import { ErrorState, LoadingState } from '@/design-system/States';
import { Pill } from '@/design-system/StatusBadge';
import { errorText } from '@/features/admin/AdminParts';
import './content.css';

/**
 * PROBLEM TYPES — Platform Admin (SoW §13).
 *
 * <p>What a Captain can say is wrong with a piece of equipment when starting
 * the guided checks. Each can have its own checks. A problem type is never
 * deleted - requests name it - so it is retired instead.
 */
export function ProblemTypesPage() {
  const client = useQueryClient();
  const catalogue = useQuery({ queryKey: ['problem-types'], queryFn: fetchProblemTypes });
  const flows = useQuery({ queryKey: ['content-flows'], queryFn: fetchFlows });
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<ProblemTypeRow | null>(null);

  useEffect(() => {
    if (categoryId === null && catalogue.data?.length) {
      const first = catalogue.data.find((c) => c.problemTypes.length > 0) ?? catalogue.data[0];
      setCategoryId(first.id);
    }
  }, [catalogue.data, categoryId]);

  if (catalogue.error) return <ErrorState error={catalogue.error} onRetry={() => catalogue.refetch()} />;
  if (!catalogue.data) return <LoadingState rows={6} />;

  const category = catalogue.data.find((c) => c.id === categoryId) ?? catalogue.data[0];
  const types = category?.problemTypes ?? [];
  const withChecks = new Set((flows.data ?? []).filter((f) => f.published && f.target.problemTypeId).map((f) => f.target.problemTypeId));
  const equipmentChecks = (flows.data ?? []).some((f) => f.published && f.target.scope === 'EQUIPMENT' && f.target.equipmentCategoryId === category?.id);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['problem-types'] });
    client.invalidateQueries({ queryKey: ['content-flows'] });
  };

  const act = async (action: () => Promise<unknown>, fallback: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      refresh();
      return true;
    } catch (e) {
      setError(errorText(e, fallback));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!category || !label.trim()) return;
    if (await act(() => createProblemType(category.id, label), 'The problem type could not be added.')) setLabel('');
  };

  const move = (index: number, delta: number) => {
    if (!category) return;
    const ids = types.map((t) => t.id);
    const [id] = ids.splice(index, 1);
    ids.splice(index + delta, 0, id);
    void act(() => reorderProblemTypes(category.id, ids), 'The order could not be saved.');
  };

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Platform administration' }, { label: 'Problem types', strong: true }]}
        title="Problem types"
        subtitle="What a Captain can choose as the problem when starting guided checks, per equipment. Changes apply to the next request raised."
      />

      <div className="ptypes">
        <nav className="ptypes__cats" aria-label="Equipment">
          {catalogue.data.map((c) => {
            const active = c.problemTypes.filter((p) => p.active).length;
            return (
              <button
                key={c.id}
                type="button"
                className={`ptypes__cat${c.id === category?.id ? ' ptypes__cat--on' : ''}`}
                aria-pressed={c.id === category?.id}
                onClick={() => {
                  setCategoryId(c.id);
                  setError(null);
                }}
              >
                <span>{c.name}</span>
                <b>{active}</b>
              </button>
            );
          })}
        </nav>

        <Plate
          title={category?.name ?? 'Equipment'}
          count={types.filter((t) => t.active).length}
          subtitle={
            equipmentChecks
              ? 'Problems without their own checks use this equipment’s checks'
              : 'Problems without their own checks use the general checks'
          }
          flush
        >
          {types.length === 0 ? (
            <div className="qlist__empty">
              <EmptyNote>No problem types for {category?.name} yet. Captains can still choose “Something else”.</EmptyNote>
            </div>
          ) : (
            <ul className="setup">
              {types.map((t, i) => (
                <li key={t.id} className={`setup-row${t.active ? '' : ' ptypes__row--retired'}`}>
                  <div className="setup-row__main">
                    <div className="setup-row__title">
                      {t.label}
                      {!t.active && <Pill size="sm">Retired</Pill>}
                      {t.active && withChecks.has(t.id) && (
                        <Pill size="sm" tone="accent">
                          Own checks
                        </Pill>
                      )}
                    </div>
                    <div className="setup-row__meta">
                      <span className="mono">{t.code}</span> · used on {t.requestCount} {t.requestCount === 1 ? 'request' : 'requests'}
                    </div>
                  </div>
                  <div className="setup-row__actions">
                    <button type="button" className="flow-step__tool" aria-label={`Move ${t.label} up`} disabled={busy || i === 0} onClick={() => move(i, -1)}>
                      ↑
                    </button>
                    <button
                      type="button"
                      className="flow-step__tool"
                      aria-label={`Move ${t.label} down`}
                      disabled={busy || i === types.length - 1}
                      onClick={() => move(i, 1)}
                    >
                      ↓
                    </button>
                    <Button variant="ghost" onClick={() => setRenaming(t)} disabled={busy}>
                      Rename
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => act(() => updateProblemType(t.id, { active: !t.active }), 'The problem type could not be changed.')}
                    >
                      {t.active ? 'Retire' : 'Bring back'}
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <form className="ptypes__add" onSubmit={add}>
            <Field label={`Add a problem for ${category?.name ?? 'this equipment'}`} htmlFor="ptype-label" hint="As a Captain would describe it, e.g. “No echoes on the display”.">
              <div className="ptypes__add-row">
                <input id="ptype-label" className="input" maxLength={160} value={label} onChange={(e) => setLabel(e.target.value)} />
                <Button type="submit" variant="primary" disabled={busy || !label.trim()}>
                  Add
                </Button>
              </div>
            </Field>
            <FormError message={error} />
          </form>
        </Plate>
      </div>

      {renaming && (
        <RenameDialog
          type={renaming}
          onClose={() => setRenaming(null)}
          onSaved={() => {
            setRenaming(null);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function RenameDialog({ type, onClose, onSaved }: { type: ProblemTypeRow; onClose: () => void; onSaved: () => void }) {
  const [label, setLabel] = useState(type.label);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await updateProblemType(type.id, { label });
      onSaved();
    } catch (e) {
      setError(errorText(e, 'The problem type could not be renamed.'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Rename problem type"
      subtitle={type.code}
      onClose={onClose}
      width={480}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !label.trim() || label.trim() === type.label} onClick={save}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <Field
        label="Problem"
        htmlFor="ptype-rename"
        hint="The code stays the same, so its checks and past requests stay linked to it."
      >
        <input id="ptype-rename" className="input" maxLength={160} value={label} onChange={(e) => setLabel(e.target.value)} />
      </Field>
      <FormError message={error} />
    </Dialog>
  );
}
