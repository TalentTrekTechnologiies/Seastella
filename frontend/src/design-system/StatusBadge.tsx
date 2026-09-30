import type { DueStatus, SoftwareStatus } from '@/api/types';
import { DUE_LABEL, DUE_SHAPE, DUE_TONE, SOFTWARE_LABEL, SOFTWARE_SHAPE, SOFTWARE_TONE, formatDays } from './status';
import { formatDate } from '@/lib/format';
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

/**
 * Renders <b>a date</b> in its due-status colour: a service falling due, or the
 * day a unit stops being fit for use. One component for both, so the two
 * columns beside each other are read the same way.
 *
 * <p><b>Receives a status; never derives one.</b> The server bands both dates
 * through the same engine, so a yellow expiry and a yellow service mean the
 * same number of days and move together when the Platform Admin changes the
 * bands.
 *
 * <p><b>The date leads, not the band word.</b> "Approaching" says nothing a
 * superintendent can act on; "12 Nov 2026" in yellow says both what and when,
 * and the day count behind it says how long they have. The word is still in the
 * tooltip, and the shape still carries the band for greyscale printing and
 * colour-vision readers — these lists reach class surveyors on paper.
 */
export function DatedStatusBadge({
  status,
  date,
  daysRemaining,
  what = 'Due',
  size = 'sm',
}: {
  status?: DueStatus | null;
  date?: string | null;
  daysRemaining?: number | null;
  /** Leads the tooltip: "Due 14 Oct 2026 — Urgent". */
  what?: string;
  size?: 'sm' | 'md';
}) {
  // No date is not a finding. Most components are not serviced on a cycle of
  // their own and most never expire; an empty cell says so more quietly than a
  // row of grey "none recorded" chips, which would drown the dates that matter.
  if (!date || !status || status === 'NOT_TRACKED') {
    return <span className="equip__missing">—</span>;
  }

  return (
    <span
      className={`badge badge--${DUE_TONE[status]} badge--${size}`}
      title={`${what} ${formatDate(date)} — ${DUE_LABEL[status]}`}
    >
      <span className={`glyph glyph--${DUE_SHAPE[status]}`} aria-hidden="true" />
      <span>{formatDate(date)}</span>
      {daysRemaining !== null && daysRemaining !== undefined && (
        <span className="badge__detail mono">{formatDays(daysRemaining)}</span>
      )}
    </span>
  );
}

/**
 * Renders how a unit's software compares with the latest release for its model.
 *
 * <p><b>Receives a status; never derives one.</b> The server holds the master
 * sheet and does the comparison — a version parse in the browser would be a
 * second answer to "is this up to date", and the two would disagree the first
 * time a manufacturer printed something unusual on an About screen.
 *
 * <p>The versions themselves are shown beside the word, because "Update due"
 * without "5.5 → 5.6" sends the reader to the equipment record to find out
 * what is actually being asked of them.
 */
export function SoftwareStatusBadge({
  status,
  installed,
  latest,
  size = 'sm',
}: {
  status: SoftwareStatus;
  installed?: string | null;
  latest?: string | null;
  size?: 'sm' | 'md';
}) {
  // Nothing recorded on either side is not a finding — it is an empty cell,
  // and a row of grey "Not known" chips would drown the ones that matter.
  if (status === 'UNKNOWN' && !installed && !latest) {
    return <span className="equip__missing">—</span>;
  }

  return (
    <span className={`badge badge--${SOFTWARE_TONE[status]} badge--${size}`}>
      <span className={`glyph glyph--${SOFTWARE_SHAPE[status]}`} aria-hidden="true" />
      <span>{SOFTWARE_LABEL[status]}</span>
      {status === 'OUTDATED' && installed && latest && (
        <span className="badge__detail mono">
          {installed} → {latest}
        </span>
      )}
      {status !== 'OUTDATED' && installed && <span className="badge__detail mono">{installed}</span>}
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
