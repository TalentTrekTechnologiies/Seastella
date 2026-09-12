import { useMemo, useState } from 'react';
import type { DueStatus } from '@/api/types';
import './radar.css';

export interface RadarPoint {
  spareId: number;
  vesselId: number;
  vesselName: string;
  spareName: string;
  categoryCode: string;
  daysRemaining: number | null;
  status: DueStatus;
}

/**
 * The maintenance radar — a PPI plot of the whole fleet's service position.
 *
 * <p>Borrowed directly from the instrument this software sits beside. On a
 * ship's radar, bearing is angle and range is distance from centre; here,
 * <b>equipment category is angle and time-to-due is range</b>. Anything close
 * to the centre is close to needing attention, and overdue work sits inside the
 * inner ring, in the zone a navigator would read as a collision risk.
 *
 * <p>It earns its place over a bar chart because it shows two things at once
 * that operators actually ask: how much work is imminent, and whether it is
 * concentrated in one equipment type. A cluster of blips on one spoke is a
 * failing category; a crowded centre is a bad month ahead. Neither reads as
 * quickly from a list.
 *
 * <p>Status colours come from the backend's own banding — the same rule the
 * badges use — so a blip and a badge can never disagree.
 */
export function MaintenanceRadar({
  points,
  onSelect,
}: {
  points: RadarPoint[];
  onSelect?: (p: RadarPoint) => void;
}) {
  const [hover, setHover] = useState<RadarPoint | null>(null);

  const categories = useMemo(() => {
    const set = new Map<string, number>();
    points.forEach((p) => set.set(p.categoryCode, (set.get(p.categoryCode) ?? 0) + 1));
    return [...set.keys()].sort();
  }, [points]);

  const plotted = useMemo(() => {
    if (categories.length === 0) return [];
    const step = 360 / categories.length;

    return points.map((p, i) => {
      const catIndex = categories.indexOf(p.categoryCode);
      // Spread points within their category's sector so co-located spares
      // stay distinguishable instead of stacking into one dot.
      const spread = (hash(p.spareId + i) % 1000) / 1000;
      const angleDeg = catIndex * step + spread * step * 0.82 + step * 0.09 - 90;
      const rad = (angleDeg * Math.PI) / 180;
      const r = rangeFor(p.daysRemaining);

      return {
        point: p,
        x: CENTER + Math.cos(rad) * r,
        y: CENTER + Math.sin(rad) * r,
        r,
      };
    });
  }, [points, categories]);

  if (points.length === 0) {
    return <p className="radar-empty">No tracked spares in scope.</p>;
  }

  const step = 360 / Math.max(categories.length, 1);

  return (
    <div className="radar">
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} className="radar__svg" role="img"
        aria-label={`Maintenance radar: ${points.length} tracked spares by equipment category and time to service`}>
        <defs>
          <radialGradient id="radar-ground" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="var(--c-accent-glow)" />
            <stop offset="100%" stopColor="transparent" />
          </radialGradient>
        </defs>

        <circle cx={CENTER} cy={CENTER} r={MAX_R} className="radar__ground" />
        <circle cx={CENTER} cy={CENTER} r={MAX_R} fill="url(#radar-ground)" />

        {/* The overdue zone. Inside this ring, work is already late. */}
        <circle cx={CENTER} cy={CENTER} r={RINGS[0].r} className="radar__danger" />

        {RINGS.map((ring) => (
          <g key={ring.label}>
            <circle cx={CENTER} cy={CENTER} r={ring.r} className="radar__ring" />
            <text x={CENTER + 3} y={CENTER - ring.r - 3} className="radar__ring-label">
              {ring.label}
            </text>
          </g>
        ))}

        {/* One spoke per equipment category, labelled at the rim. */}
        {categories.map((code, i) => {
          const a = ((i * step - 90) * Math.PI) / 180;
          const mid = (((i + 0.5) * step - 90) * Math.PI) / 180;
          const lx = CENTER + Math.cos(mid) * (MAX_R + 16);
          const ly = CENTER + Math.sin(mid) * (MAX_R + 16);

          return (
            <g key={code}>
              <line
                x1={CENTER}
                y1={CENTER}
                x2={CENTER + Math.cos(a) * MAX_R}
                y2={CENTER + Math.sin(a) * MAX_R}
                className="radar__spoke"
              />
              <text
                x={lx}
                y={ly}
                className="radar__cat"
                textAnchor={Math.cos(mid) > 0.25 ? 'start' : Math.cos(mid) < -0.25 ? 'end' : 'middle'}
                dominantBaseline="middle"
              >
                {code}
              </text>
            </g>
          );
        })}

        {/* Blips, most urgent drawn last so they sit on top. */}
        {[...plotted]
          .sort((a, b) => b.r - a.r)
          .map(({ point, x, y }) => (
            <circle
              key={`${point.spareId}`}
              cx={x}
              cy={y}
              r={point.status === 'OVERDUE' || point.status === 'DUE' ? 4.2 : 3.2}
              className={`radar__blip radar__blip--${point.status.toLowerCase()}${
                hover?.spareId === point.spareId ? ' radar__blip--hover' : ''
              }`}
              onMouseEnter={() => setHover(point)}
              onMouseLeave={() => setHover(null)}
              onClick={() => onSelect?.(point)}
            >
              <title>
                {point.spareName} · {point.vesselName} ·{' '}
                {point.daysRemaining !== null && point.daysRemaining < 0
                  ? `${Math.abs(point.daysRemaining)} days overdue`
                  : `${point.daysRemaining} days`}
              </title>
            </circle>
          ))}
      </svg>

      <div className="radar__readout" aria-live="polite">
        {hover ? (
          <>
            <span className="radar__readout-name">{hover.spareName}</span>
            <span className="radar__readout-meta">
              {hover.vesselName} · {hover.categoryCode}
            </span>
            <span className={`radar__readout-days radar__readout-days--${hover.status.toLowerCase()}`}>
              {hover.daysRemaining !== null && hover.daysRemaining < 0
                ? `${Math.abs(hover.daysRemaining)}d overdue`
                : hover.daysRemaining === 0
                  ? 'due today'
                  : `${hover.daysRemaining}d to service`}
            </span>
          </>
        ) : (
          <>
            <span className="radar__readout-name mono">{points.length} spares tracked</span>
            <span className="radar__readout-meta">Hover a blip for detail</span>
          </>
        )}
      </div>
    </div>
  );
}

const SIZE = 340;
const CENTER = SIZE / 2;
const MAX_R = 134;

/** Range rings, labelled in days. The innermost is the overdue zone. */
const RINGS = [
  { r: 34, label: '0' },
  { r: 62, label: '15' },
  { r: 88, label: '45' },
  { r: 111, label: '120' },
  { r: MAX_R, label: '365+' },
];

/**
 * Maps days-to-due onto a radius.
 *
 * <p>Non-linear on purpose: the first fortnight gets as much of the plot as the
 * following year, because that is where the decisions are. A linear scale would
 * squash every urgent item into a dot at the centre.
 */
function rangeFor(days: number | null): number {
  if (days === null) return MAX_R;
  if (days < 0) return Math.max(10, 34 - Math.min(Math.abs(days), 60) * 0.35);
  if (days === 0) return 34;
  if (days <= 15) return 34 + (days / 15) * 28;
  if (days <= 45) return 62 + ((days - 15) / 30) * 26;
  if (days <= 120) return 88 + ((days - 45) / 75) * 23;
  if (days <= 365) return 111 + ((days - 120) / 245) * 23;
  return MAX_R;
}

/** Deterministic jitter, so a spare keeps its position between renders. */
function hash(n: number): number {
  let x = n * 2654435761;
  x = (x ^ (x >>> 15)) >>> 0;
  return x;
}
