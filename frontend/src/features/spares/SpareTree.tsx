import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { SpareNode } from '@/api/serviceRequests';
import { Button, EmptyNote } from '@/design-system/Console';
import './spares.css';

/**
 * The Spare tree, browsed (SoW §9.1, SPR-11).
 *
 * <p>The VMP numbering is a hierarchy — 13.1.4 is the fan inside the scanner
 * unit inside the radar — and a flat list of ninety rows hides that. Branches
 * open and close; a search opens whatever it needs to reach a match and closes
 * again when it is cleared, so finding one item never costs the reader their
 * place in the structure.
 *
 * <p>The tree itself is presentation only. What each row offers — edit, count,
 * raise a request — is the caller's, which is what keeps one component usable
 * by the Technical Head and the Captain without either seeing the other's
 * actions.
 */
export function SpareTree({
  spares,
  meta,
  actions,
  focusId,
  emptyNote = 'No equipment is recorded for this vessel.',
  searchLabel = 'Search name, make, serial…',
}: {
  spares: SpareNode[];
  /** The line under a row's name: due status, running hours, whatever fits. */
  meta?: (spare: SpareNode) => ReactNode;
  actions?: (spare: SpareNode) => ReactNode;
  /** Arrived here from a dashboard: open this item and say which one it is (DSH-13). */
  focusId?: number;
  emptyNote?: string;
  searchLabel?: string;
}) {
  const [search, setSearch] = useState('');
  const [collapsed, setCollapsed] = useState<Set<number>>(() => new Set());

  // A drill-down lands on one item: open the branches above it and scroll to it.
  useEffect(() => {
    if (focusId == null || spares.length === 0) return;
    const byId = new Map(spares.map((s) => [s.id, s]));
    const ancestors = new Set<number>();
    let parent = byId.get(focusId)?.parentId;
    while (parent != null) {
      ancestors.add(parent);
      parent = byId.get(parent)?.parentId;
    }
    setCollapsed((was) => {
      if (![...ancestors].some((id) => was.has(id))) return was;
      const next = new Set(was);
      ancestors.forEach((id) => next.delete(id));
      return next;
    });
    window.requestAnimationFrame(() => {
      document.getElementById(`spare-${focusId}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
  }, [focusId, spares]);

  const children = useMemo(() => {
    const byParent = new Map<number, SpareNode[]>();
    const roots: SpareNode[] = [];
    const known = new Set(spares.map((s) => s.id));
    for (const spare of spares) {
      // A node whose parent is outside this list is a root here: a subtree
      // shown on its own must still render.
      if (spare.parentId != null && known.has(spare.parentId)) {
        const siblings = byParent.get(spare.parentId) ?? [];
        siblings.push(spare);
        byParent.set(spare.parentId, siblings);
      } else {
        roots.push(spare);
      }
    }
    return { byParent, roots };
  }, [spares]);

  const q = search.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!q) return null;
    const hit = new Set<number>();
    const byId = new Map(spares.map((s) => [s.id, s]));
    for (const spare of spares) {
      const text = [spare.name, spare.path, spare.make, spare.model, spare.serialNumber, spare.categoryName];
      if (!text.some((f) => f?.toLowerCase().includes(q))) continue;
      hit.add(spare.id);
      // Keep the branch that leads to a match, so a result is never orphaned.
      let parent = spare.parentId == null ? undefined : byId.get(spare.parentId);
      while (parent) {
        hit.add(parent.id);
        parent = parent.parentId == null ? undefined : byId.get(parent.parentId);
      }
    }
    return hit;
  }, [q, spares]);

  const visible = (spare: SpareNode) => matches === null || matches.has(spare.id);
  const hasChildren = (spare: SpareNode) => (children.byParent.get(spare.id) ?? []).some(visible);
  // A search opens the branches it needs; nothing is hidden behind a closed one.
  const isOpen = (spare: SpareNode) => (matches !== null ? true : !collapsed.has(spare.id));

  const toggle = (id: number) =>
    setCollapsed((was) => {
      const next = new Set(was);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const parents = spares.filter((s) => (children.byParent.get(s.id) ?? []).length > 0);
  const allCollapsed = parents.length > 0 && parents.every((s) => collapsed.has(s.id));

  const rows: ReactNode[] = [];
  const walk = (spare: SpareNode, depth: number) => {
    if (!visible(spare)) return;
    const kids = children.byParent.get(spare.id) ?? [];
    const open = isOpen(spare);
    rows.push(
      <li
        key={spare.id}
        id={`spare-${spare.id}`}
        className={`tree__row${spare.id === focusId ? ' tree__row--focus' : ''}`}
        style={{ paddingLeft: 16 + depth * 20 }}
      >
        {hasChildren(spare) ? (
          <button
            type="button"
            className={`tree__twist${open ? ' tree__twist--open' : ''}`}
            aria-expanded={open}
            aria-label={`${open ? 'Collapse' : 'Expand'} ${spare.name}`}
            onClick={() => toggle(spare.id)}
          >
            <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
              <path d="M3 1l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        ) : (
          <span className="tree__twist tree__twist--leaf" aria-hidden="true" />
        )}
        <div className="tree__main">
          <div className="tree__title">
            <span className="mono tree__path">{spare.path}</span>
            <span className="tree__name">{spare.name}</span>
            {!open && kids.length > 0 && (
              <button type="button" className="tree__count" onClick={() => toggle(spare.id)}>
                {kids.length} inside
              </button>
            )}
          </div>
          {meta && <div className="tree__meta">{meta(spare)}</div>}
        </div>
        {actions && <div className="tree__actions">{actions(spare)}</div>}
      </li>,
    );
    if (open) kids.forEach((kid) => walk(kid, depth + 1));
  };
  children.roots.forEach((root) => walk(root, 0));

  return (
    <div className="tree">
      <div className="tree__bar">
        <input
          className="input input--search"
          type="search"
          placeholder={searchLabel}
          aria-label={searchLabel}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        {parents.length > 0 && !q && (
          <Button
            variant="ghost"
            onClick={() => setCollapsed(allCollapsed ? new Set() : new Set(parents.map((s) => s.id)))}
          >
            {allCollapsed ? 'Expand all' : 'Collapse all'}
          </Button>
        )}
        {q && (
          <span className="tree__found">
            {rows.length === 0 ? 'Nothing matches' : `${matches?.size ?? 0} in view`}
          </span>
        )}
      </div>

      {rows.length === 0 ? (
        <div className="tree__empty">
          <EmptyNote>{q ? 'Nothing matches that search.' : emptyNote}</EmptyNote>
        </div>
      ) : (
        <ul className="tree__list">{rows}</ul>
      )}
    </div>
  );
}
