import type { Kpi } from '@/api/types';
import './kpi.css';

/**
 * A row of headline figures.
 *
 * <p>Values come from the dashboard API and are rendered as received — no
 * arithmetic happens here. Figures are set in mono because they are instrument
 * readings, and they align in columns.
 *
 * <p>`onSelect` turns a tile into a drill-down rather than a decoration: a
 * number the user cannot act on is a poster, not a dashboard.
 */
export function KpiRow({
  items,
  onSelect,
  activeKey,
}: {
  items: Kpi[];
  onSelect?: (key: string) => void;
  activeKey?: string | null;
}) {
  return (
    <div className="kpi-row" role="list">
      {items.map((k) => {
        const interactive = Boolean(onSelect);
        const Tag = interactive ? 'button' : 'div';

        return (
          <Tag
            key={k.key}
            role="listitem"
            className={`kpi${interactive ? ' kpi--interactive' : ''}${
              activeKey === k.key ? ' kpi--active' : ''
            }`}
            {...(interactive ? { type: 'button' as const, onClick: () => onSelect!(k.key) } : {})}
          >
            <span className="kpi__label">{k.label}</span>
            <span className="kpi__value mono">
              {formatValue(k.value)}
              {k.unit && <span className="kpi__unit">{k.unit}</span>}
            </span>
            {k.trendHint && <span className="kpi__trend">{k.trendHint}</span>}
          </Tag>
        );
      })}
    </div>
  );
}

/** A single emphasised figure, for the one number a screen is actually about. */
export function KpiTile({
  label,
  value,
  tone = 'default',
  caption,
}: {
  label: string;
  value: string | number;
  tone?: 'default' | 'urgent' | 'overdue' | 'normal';
  caption?: string;
}) {
  return (
    <div className={`kpi kpi--tile kpi--tone-${tone}`}>
      <span className="kpi__label">{label}</span>
      <span className="kpi__value kpi__value--lg mono">{value}</span>
      {caption && <span className="kpi__trend">{caption}</span>}
    </div>
  );
}

function formatValue(v: number) {
  return v >= 10000 ? v.toLocaleString('en-US') : String(v);
}
