import type { DueStatus } from '@/api/types';
import { DUE_LABEL, DUE_SHAPE, DUE_TONE, formatDays } from './status';
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
  const label = DUE_LABEL[status];

  return (
    <span className={`badge badge--${DUE_TONE[status]} badge--${size}`}>
      <span className={`glyph glyph--${DUE_SHAPE[status]}`} aria-hidden="true" />
      <span>{label}</span>
      {daysRemaining !== null && daysRemaining !== undefined && status !== 'NOT_TRACKED' && (
        <span className="badge__detail mono">{formatDays(daysRemaining)}</span>
      )}
    </span>
  );
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
