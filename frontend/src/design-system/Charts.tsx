import type { Distribution } from '@/api/types';
import './charts.css';

/**
 * Charts read their colours from the same tokens as the badges, via the
 * `colour` key the backend already puts on each slice. Nothing here invents a
 * palette, so a chart segment and the badge for the same status are the same
 * colour by construction rather than by coincidence.
 */

const TONE_VAR: Record<string, string> = {
  green: 'var(--c-normal)',
  yellow: 'var(--c-approaching)',
  orange: 'var(--c-urgent)',
  red: 'var(--c-overdue)',
  accent: 'var(--c-accent)',
  critical: 'var(--c-critical)',
  neutral: 'var(--c-text-faint)',
};

function colourFor(key: string) {
  return TONE_VAR[key] ?? 'var(--c-text-faint)';
}

/**
 * A horizontal stacked bar with a legend.
 *
 * <p>Chosen over a donut for status breakdowns: proportions of a known whole
 * compare better along one axis, and the legend can carry exact counts, which
 * is what an operator actually reads.
 */
export function StackedBar({ data, showZero = false }: { data: Distribution; showZero?: boolean }) {
  const slices = data.slices.filter((s) => showZero || s.value > 0);

  if (data.total === 0) {
    return <p className="chart-empty">No data in scope.</p>;
  }

  return (
    <div className="chart">
      <div
        className="stackbar"
        role="img"
        aria-label={`${data.label}: ${slices
          .map((s) => `${s.label} ${s.value}`)
          .join(', ')}`}
      >
        {slices.map((s) => (
          <span
            key={s.key}
            className="stackbar__seg"
            style={{
              width: `${(s.value / data.total) * 100}%`,
              background: colourFor(s.colour),
            }}
            title={`${s.label}: ${s.value}`}
          />
        ))}
      </div>
      <ul className="legend">
        {slices.map((s) => (
          <li key={s.key}>
            <span className="legend__swatch" style={{ background: colourFor(s.colour) }} />
            <span className="legend__label">{s.label}</span>
            <span className="legend__value mono">{s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Ranked horizontal bars, for comparing magnitudes across categories. */
export function BarList({
  data,
  max,
}: {
  data: { key: string; label: string; value: number; colour?: string }[];
  max?: number;
}) {
  const ceiling = max ?? Math.max(1, ...data.map((d) => d.value));

  if (data.length === 0) {
    return <p className="chart-empty">No data in scope.</p>;
  }

  return (
    <ul className="barlist">
      {data.map((d) => (
        <li key={d.key}>
          <span className="barlist__label">{d.label}</span>
          <span className="barlist__track">
            <span
              className="barlist__fill"
              style={{
                width: `${(d.value / ceiling) * 100}%`,
                background: colourFor(d.colour ?? 'accent'),
              }}
            />
          </span>
          <span className="barlist__value mono">{d.value}</span>
        </li>
      ))}
    </ul>
  );
}

/** Two-value comparison, e.g. resolved without cost vs engineer visit. */
export function SplitMeter({
  leftLabel,
  leftValue,
  rightLabel,
  rightValue,
}: {
  leftLabel: string;
  leftValue: number;
  rightLabel: string;
  rightValue: number;
}) {
  const total = leftValue + rightValue;
  const leftPct = total === 0 ? 50 : (leftValue / total) * 100;

  return (
    <div className="split">
      <div className="split__head">
        <span>
          <strong className="mono">{leftValue}</strong> {leftLabel}
        </span>
        <span>
          <strong className="mono">{rightValue}</strong> {rightLabel}
        </span>
      </div>
      <div className="split__track" role="img" aria-label={`${leftValue} ${leftLabel}, ${rightValue} ${rightLabel}`}>
        <span className="split__left" style={{ width: `${leftPct}%` }} />
        <span className="split__right" style={{ width: `${100 - leftPct}%` }} />
      </div>
      {total > 0 && (
        <p className="split__caption">
          {Math.round(leftPct)}% resolved without a service visit
        </p>
      )}
    </div>
  );
}
