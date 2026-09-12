import type { DueStatus } from '@/api/types';
import './status.css';

/**
 * Renders a maintenance colour status.
 *
 * <p><b>This component receives a status. It never derives one.</b> The
 * maintenance engine on the server is the only thing that decides whether a
 * spare is Urgent, and a `daysRemaining` comparison in here would be a second
 * implementation of that rule which threshold configuration would silently
 * fail to update. `daysRemaining` is display only.
 *
 * <p>Each status also carries a distinct shape, because colour alone fails
 * greyscale printing and colour-vision deficiency — and these reports reach
 * class surveyors on paper.
 */
export function DueStatusBadge({
  status,
  daysRemaining,
  size = 'md',
}: {
  status: DueStatus;
  daysRemaining?: number | null;
  size?: 'sm' | 'md';
}) {
  const label = LABELS[status];

  return (
    <span className={`badge badge--${TONE[status]} badge--${size}`}>
      <span className={`glyph glyph--${SHAPE[status]}`} aria-hidden="true" />
      <span>{label}</span>
      {daysRemaining !== null && daysRemaining !== undefined && status !== 'NOT_TRACKED' && (
        <span className="badge__detail mono">{formatDays(daysRemaining)}</span>
      )}
    </span>
  );
}

const LABELS: Record<DueStatus, string> = {
  NORMAL: 'Normal',
  APPROACHING: 'Approaching',
  URGENT: 'Urgent',
  DUE: 'Due',
  OVERDUE: 'Overdue',
  NOT_TRACKED: 'Not tracked',
};

const TONE: Record<DueStatus, string> = {
  NORMAL: 'normal',
  APPROACHING: 'approaching',
  URGENT: 'urgent',
  DUE: 'overdue',
  OVERDUE: 'overdue',
  NOT_TRACKED: 'neutral',
};

const SHAPE: Record<DueStatus, string> = {
  NORMAL: 'dot',
  APPROACHING: 'half',
  URGENT: 'triangle',
  DUE: 'square',
  OVERDUE: 'square',
  NOT_TRACKED: 'none',
};

function formatDays(days: number) {
  if (days === 0) return 'today';
  if (days < 0) return `${Math.abs(days)}d over`;
  return `${days}d`;
}

/** Generic pill for non-maintenance state: request status, invoice status. */
export function Pill({
  children,
  tone = 'neutral',
  size = 'md',
}: {
  children: React.ReactNode;
  tone?: 'neutral' | 'accent' | 'normal' | 'approaching' | 'urgent' | 'overdue' | 'critical';
  size?: 'sm' | 'md';
}) {
  return <span className={`badge badge--${tone} badge--${size}`}>{children}</span>;
}

/** Criticality uses its own hue — never the reserved four. */
export function CriticalityChip({ value }: { value: string }) {
  return (
    <Pill tone={value === 'CRITICAL' ? 'critical' : 'neutral'} size="sm">
      {value.charAt(0) + value.slice(1).toLowerCase()}
    </Pill>
  );
}

/** Request priority. Distinct from due-status, so distinct visual weight. */
export function PriorityChip({ value }: { value: string }) {
  const tone = value === 'CRITICAL' ? 'overdue' : value === 'HIGH' ? 'urgent' : 'neutral';
  return (
    <Pill tone={tone} size="sm">
      {value.charAt(0) + value.slice(1).toLowerCase()}
    </Pill>
  );
}

/** Vessel operational status. */
export function VesselStatusChip({ value }: { value: string }) {
  return (
    <span className="vessel-status">
      <span className={`vessel-dot vessel-dot--${value.toLowerCase()}`} aria-hidden="true" />
      {value === 'DRY_DOCK' ? 'Dry dock' : value.charAt(0) + value.slice(1).toLowerCase()}
    </span>
  );
}
