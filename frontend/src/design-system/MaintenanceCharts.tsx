import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { Distribution, DueItem, DueStatus, RadarPoint } from '@/api/types';
import { formatDate } from '@/lib/format';
import { DUE_FILL, DUE_LABEL, DUE_ORDER, categoryLabel } from './status';
import { Chip, EmptyNote, Plate, Segmented, StatusLegend } from './Console';
import { DueStatusBadge } from './StatusBadge';
import './sbs.css';

/**
 * Maintenance charts.
 *
 * <p>These replace the service horizon, which asked people to decode shapes
 * scattered along a compressed axis. The rule now is that nothing has to be
 * decoded: every bar carries its count, every colour is named in the legend,
 * and every axis says what it measures in plain words.
 *
 * <p>Statuses are the server's. The only grouping done here is by the
 * `daysRemaining` figure the server also sent, and it is labelled as days —
 * a bucket is never recoloured into a status the engine did not assign.
 */

type Counts = Record<DueStatus, number>;

const emptyCounts = (): Counts => ({
  OVERDUE: 0,
  DUE: 0,
  URGENT: 0,
  APPROACHING: 0,
  NORMAL: 0,
  NOT_TRACKED: 0,
});

/* --------------------------------------------------------------------------
   Overall health: one bar across every tracked spare.
   -------------------------------------------------------------------------- */

export function HealthBar({ distribution }: { distribution: Distribution }) {
  const bands = DUE_ORDER.map((status) => ({
    status,
    value: distribution.slices.find((s) => s.key === status)?.value ?? 0,
  })).filter((b) => b.value > 0);

  const total = distribution.total;
  if (total === 0) return <p className="empty-line">No tracked spares in scope.</p>;

  return (
    <div className="healthbar">
      <div
        className="healthbar__bar"
        role="img"
        aria-label={`${bands.map((b) => `${DUE_LABEL[b.status]} ${b.value}`).join(', ')}, of ${total} spares`}
      >
        {bands.map((b) => {
          const share = b.value / total;
          return (
            <span
              key={b.status}
              className="healthbar__seg"
              style={{ flexGrow: b.value, background: DUE_FILL[b.status] }}
              title={`${DUE_LABEL[b.status]}: ${b.value} (${Math.round(share * 100)}%)`}
            >
              {share >= 0.07 && <span className="healthbar__num">{b.value}</span>}
            </span>
          );
        })}
      </div>
      <StatusLegend items={bands} />
    </div>
  );
}

/* --------------------------------------------------------------------------
   When maintenance falls due: columns by days-to-service.
   -------------------------------------------------------------------------- */

const BUCKETS: { key: string; label: string; test: (d: number) => boolean }[] = [
  { key: 'past', label: 'Past due date', test: (d) => d < 0 },
  { key: '0-7', label: '0–7 days', test: (d) => d >= 0 && d <= 7 },
  { key: '8-15', label: '8–15 days', test: (d) => d >= 8 && d <= 15 },
  { key: '16-30', label: '16–30 days', test: (d) => d >= 16 && d <= 30 },
  { key: '31-90', label: '31–90 days', test: (d) => d >= 31 && d <= 90 },
  { key: '90+', label: 'Over 90 days', test: (d) => d > 90 },
];

const PLOT_HEIGHT = 300;

export function DueTimeline({ points }: { points: RadarPoint[] }) {
  const { columns, max, notTracked, present } = useMemo(() => {
    const cols = BUCKETS.map((b) => ({ ...b, counts: emptyCounts(), total: 0 }));
    let untracked = 0;
    for (const p of points) {
      if (p.daysRemaining === null || p.status === 'NOT_TRACKED') {
        untracked += 1;
        continue;
      }
      const col = cols.find((c) => c.test(p.daysRemaining as number));
      if (!col) continue;
      col.counts[p.status] += 1;
      col.total += 1;
    }
    const seen = DUE_ORDER.filter((s) => cols.some((c) => c.counts[s] > 0));
    return {
      columns: cols,
      max: niceMax(Math.max(1, ...cols.map((c) => c.total))),
      notTracked: untracked,
      present: seen,
    };
  }, [points]);

  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));

  return (
    <div className="timeline">
      <StatusLegend items={present.map((s) => ({ status: s }))} />

      <div className="timeline__chart">
        <div className="timeline__yaxis" style={{ height: PLOT_HEIGHT }} aria-hidden="true">
          {ticks.map((t) => (
            <span key={t} className="timeline__ytick" style={{ bottom: `${(t / max) * 100}%` }}>
              {t}
            </span>
          ))}
        </div>

        <div className="timeline__plot" style={{ height: PLOT_HEIGHT }}>
          {ticks.slice(1).map((t) => (
            <span key={t} className="timeline__grid" style={{ bottom: `${(t / max) * 100}%` }} aria-hidden="true" />
          ))}
          {columns.map((col) => (
            <div
              key={col.key}
              className="timeline__col"
              tabIndex={0}
              aria-label={`${col.label}: ${col.total} spares${
                col.total
                  ? ` — ${DUE_ORDER.filter((s) => col.counts[s] > 0)
                      .map((s) => `${col.counts[s]} ${DUE_LABEL[s].toLowerCase()}`)
                      .join(', ')}`
                  : ''
              }`}
            >
              <span className="timeline__total">{col.total}</span>
              <div className="timeline__stack" style={{ height: `${(col.total / max) * 100}%` }}>
                {DUE_ORDER.filter((s) => col.counts[s] > 0).map((s) => (
                  <span
                    key={s}
                    className="timeline__seg"
                    style={{ flexGrow: col.counts[s], background: DUE_FILL[s] }}
                  />
                ))}
              </div>

              {col.total > 0 && (
                <div className="timeline__tip" role="presentation">
                  <strong>{col.label}</strong>
                  {DUE_ORDER.filter((s) => col.counts[s] > 0).map((s) => (
                    <span key={s}>
                      <i style={{ background: DUE_FILL[s] }} />
                      {DUE_LABEL[s]} <b>{col.counts[s]}</b>
                    </span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>

        <div />
        <div className="timeline__xlabels" aria-hidden="true">
          {columns.map((c) => (
            <span key={c.key}>{c.label}</span>
          ))}
        </div>
      </div>

      <p className="chart-note">
        Number of spares, counted on each vessel
        {notTracked > 0 ? ` · ${notTracked} not on a service interval` : ''}
      </p>
    </div>
  );
}

/* --------------------------------------------------------------------------
   Equipment health by category: one labelled bar per category.
   -------------------------------------------------------------------------- */

export function CategoryHealth({
  points,
  selected,
  onSelect,
}: {
  points: RadarPoint[];
  selected: string | null;
  onSelect: (code: string | null) => void;
}) {
  const [showAll, setShowAll] = useState(false);

  const rows = useMemo(() => {
    const byCat = new Map<string, Counts>();
    for (const p of points) {
      const c = byCat.get(p.categoryCode) ?? emptyCounts();
      c[p.status] += 1;
      byCat.set(p.categoryCode, c);
    }
    return [...byCat.entries()]
      .map(([code, counts]) => ({
        code,
        counts,
        total: DUE_ORDER.reduce((n, s) => n + counts[s], 0),
        problems: counts.OVERDUE + counts.DUE + counts.URGENT,
      }))
      .sort(
        (a, b) =>
          b.counts.OVERDUE + b.counts.DUE - (a.counts.OVERDUE + a.counts.DUE) ||
          b.counts.URGENT - a.counts.URGENT ||
          b.counts.APPROACHING - a.counts.APPROACHING ||
          categoryLabel(a.code).localeCompare(categoryLabel(b.code)),
      );
  }, [points]);

  if (rows.length === 0) return <p className="empty-line">No tracked equipment in scope.</p>;

  const maxTotal = Math.max(...rows.map((r) => r.total));
  const LIMIT = 8;
  const visible = showAll ? rows : rows.slice(0, LIMIT);
  const present = DUE_ORDER.filter((s) => rows.some((r) => r.counts[s] > 0));

  return (
    <div className="cathealth">
      <StatusLegend items={present.map((s) => ({ status: s }))} />

      <ul className="cathealth__list">
        {visible.map((r) => {
          const on = selected === r.code;
          const breakdown = DUE_ORDER.filter((s) => s !== 'NORMAL' && r.counts[s] > 0)
            .map((s) => `${r.counts[s]} ${DUE_LABEL[s].toLowerCase()}`)
            .join(' · ');
          return (
            <li key={r.code}>
              <button
                type="button"
                className={`cathealth__row${on ? ' cathealth__row--on' : ''}`}
                onClick={() => onSelect(on ? null : r.code)}
                aria-pressed={on}
              >
                <span className="cathealth__name">{categoryLabel(r.code)}</span>
                <span className="cathealth__track">
                  <span className="cathealth__bar" style={{ width: `${(r.total / maxTotal) * 100}%` }}>
                    {DUE_ORDER.filter((s) => r.counts[s] > 0).map((s) => (
                      <span
                        key={s}
                        className="cathealth__seg"
                        style={{ flexGrow: r.counts[s], background: DUE_FILL[s] }}
                      />
                    ))}
                  </span>
                </span>
                <span className="cathealth__total">{r.total}</span>
                <span className="cathealth__detail">
                  {breakdown || 'All within service interval'}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      {rows.length > LIMIT && (
        <button type="button" className="linkbtn" onClick={() => setShowAll((v) => !v)}>
          {showAll ? 'Show fewer categories' : `Show all ${rows.length} categories`}
        </button>
      )}
    </div>
  );
}

/** Round an axis ceiling up to a figure a reader can divide in their head. */
function niceMax(n: number) {
  const steps = [4, 8, 12, 20, 40, 60, 80, 100, 120, 160, 200, 300, 400, 500, 800, 1000];
  const headroom = n * 1.12;
  return steps.find((s) => s >= headroom) ?? Math.ceil(headroom / 100) * 100;
}

/* --------------------------------------------------------------------------
   Needs attention: the spares past due or due soon, most urgent first.
   -------------------------------------------------------------------------- */

type AttentionView = 'all' | 'overdue' | 'soon';

/**
 * The work list that sits beside the maintenance charts on every role that
 * holds maintenance data. Dashboards send a bounded list, so when the full
 * counts are known the plate says how much of it is shown — a filtered or
 * truncated list must never pass for the complete one.
 */
export function DueAttention({
  overdue,
  dueSoon,
  totals,
  category,
  onClearCategory,
  showVessel = true,
  spareHref,
  subtitle = 'Spares past due or due within 15 days, most urgent first',
}: {
  overdue: DueItem[];
  dueSoon: DueItem[];
  /** The KPI counts, when the role's payload carries them. */
  totals?: { overdue: number; dueSoon: number };
  category?: string | null;
  onClearCategory?: () => void;
  showVessel?: boolean;
  /** Where a row leads, when the role has an equipment page to drill into (DSH-13). */
  spareHref?: (item: DueItem) => string;
  subtitle?: string;
}) {
  const [view, setView] = useState<AttentionView>('all');

  const inCategory = (d: DueItem) => !category || d.categoryCode === category;
  const overdueHere = overdue.filter(inCategory);
  const soonHere = dueSoon.filter(inCategory);

  const items = (view === 'overdue' ? overdueHere : view === 'soon' ? soonHere : [...overdueHere, ...soonHere])
    .slice()
    .sort((a, b) => (a.daysRemaining ?? 0) - (b.daysRemaining ?? 0));

  const truncated =
    totals !== undefined && (overdue.length < totals.overdue || dueSoon.length < totals.dueSoon);

  return (
    <Plate title="Needs attention" count={items.length} subtitle={subtitle} flush>
      <div className="attn__tools">
        <Segmented<AttentionView>
          label="Show"
          value={view}
          onChange={setView}
          options={[
            { value: 'all', label: 'All', count: overdueHere.length + soonHere.length },
            { value: 'overdue', label: 'Overdue', count: overdueHere.length },
            { value: 'soon', label: 'Due soon', count: soonHere.length },
          ]}
        />
        {category && onClearCategory && (
          <Chip on onClick={onClearCategory}>
            {categoryLabel(category)} <span aria-hidden="true">✕</span>
            <span className="sr-only">Clear category filter</span>
          </Chip>
        )}
      </div>

      {items.length === 0 ? (
        <div className="attn__empty">
          <EmptyNote>
            {category
              ? `Nothing in ${categoryLabel(category)} needs attention.`
              : 'Every tracked spare is within its service interval.'}
          </EmptyNote>
        </div>
      ) : (
        <div className="attn">
          {items.map((d) => (
            <div className="attn__item" key={d.spareId}>
              {spareHref ? (
                <Link className="attn__link" to={spareHref(d)}>
                  <div className="attn__name">{d.spareName}</div>
                  <div className="attn__meta">
                    {showVessel && `${d.vesselName} · `}
                    {categoryLabel(d.categoryCode)} · <span className="mono">{d.sparePath}</span>
                  </div>
                </Link>
              ) : (
                <div>
                  <div className="attn__name">{d.spareName}</div>
                  <div className="attn__meta">
                    {showVessel && `${d.vesselName} · `}
                    {categoryLabel(d.categoryCode)} · <span className="mono">{d.sparePath}</span>
                  </div>
                </div>
              )}
              <div className="attn__right">
                <DueStatusBadge status={d.status} daysRemaining={d.daysRemaining} />
                <span className="attn__date">Due {formatDate(d.nextDueDate)}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {truncated && totals && (
        <p className="attn__note">
          Showing {overdue.length} of {totals.overdue} overdue and {dueSoon.length} of {totals.dueSoon} due-soon
          spares — the most urgent first.
        </p>
      )}
    </Plate>
  );
}
