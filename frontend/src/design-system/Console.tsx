import { Fragment, type ReactNode } from 'react';
import type { ActivityItem, DueStatus } from '@/api/types';
import { relativeTime } from '@/lib/format';
import { DUE_COLOUR, DUE_FILL, DUE_LABEL, DUE_SHAPE } from './status';
import { Icon } from './Icon';
import './sbs.css';

/**
 * Console primitives shared by every role dashboard.
 *
 * <p>Readability comes first: titles are sentence case at 17px, secondary text
 * never drops below 13px, and charts name their values in words instead of
 * asking the reader to decode shapes. Status never relies on colour alone —
 * every status that appears is written out beside its colour.
 */

/* ------------------------------------------------------------------ header */

export interface ScopePart {
  label: ReactNode;
  /** The part of the scope path that identifies whose data this is. */
  strong?: boolean;
}

/**
 * Operational context, never a greeting: the scope path, what this screen is,
 * and when its figures were read.
 */
export function ConsoleHeader({
  scope,
  title,
  subtitle,
  generatedAt,
  actions,
  badge,
}: {
  scope: ScopePart[];
  title: ReactNode;
  subtitle?: ReactNode;
  generatedAt?: string;
  actions?: ReactNode;
  badge?: ReactNode;
}) {
  const generated = generatedAt ? new Date(generatedAt) : null;

  return (
    <header className="ohead">
      <div className="ohead__left">
        <div className="ohead__scope">
          {scope.map((s, i) => (
            <Fragment key={i}>
              {i > 0 && (
                <span className="ohead__sep" aria-hidden="true">
                  /
                </span>
              )}
              {s.strong ? <b>{s.label}</b> : <span>{s.label}</span>}
            </Fragment>
          ))}
          {badge}
        </div>
        <h1 className="ohead__title">{title}</h1>
        {subtitle && <p className="ohead__sub">{subtitle}</p>}
      </div>

      <div className="ohead__right">
        {generated && (
          <div className="ohead__snap">
            <span className="ohead__live" aria-hidden="true" />
            <span>
              Updated <b>{relativeTime(generatedAt)}</b> ·{' '}
              {generated.toLocaleString('en-GB', {
                day: '2-digit',
                month: 'short',
                hour: '2-digit',
                minute: '2-digit',
                timeZone: 'UTC',
              })}{' '}
              UTC
            </span>
          </div>
        )}
        {actions}
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ button */

export function Button({
  children,
  variant = 'secondary',
  size = 'md',
  onClick,
  disabled,
  title,
  type = 'button',
}: {
  children: ReactNode;
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'md' | 'lg' | 'xl';
  onClick?: () => void;
  disabled?: boolean;
  title?: string;
  type?: 'button' | 'submit';
}) {
  return (
    <button
      type={type}
      className={`cbtn cbtn--${variant} cbtn--${size}`}
      onClick={onClick}
      disabled={disabled}
      title={title}
    >
      {children}
    </button>
  );
}

/* ------------------------------------------------------------------- cards */

export function Plate({
  title,
  subtitle,
  count,
  action,
  flush = false,
  tone,
  fill = false,
  children,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  count?: number | string;
  action?: ReactNode;
  /** Flush plates hand their full width to a list that pads its own rows. */
  flush?: boolean;
  /** `attention` marks a queue that is waiting on this user. */
  tone?: 'attention';
  /** The body's single list scrolls within the full height of the row. */
  fill?: boolean;
  children: ReactNode;
}) {
  return (
    <section className={`plate${tone ? ` plate--${tone}` : ''}${fill ? ' plate--fill' : ''}`}>
      <header className="plate__head">
        <div className="plate__titles">
          <h2 className="plate__title">
            {title}
            {count !== undefined && <span className="plate__count">{count}</span>}
          </h2>
          {subtitle && <p className="plate__sub">{subtitle}</p>}
        </div>
        {action && <div className="plate__action">{action}</div>}
      </header>
      <div className={`plate__body${flush ? ' plate__body--flush' : ''}`}>{children}</div>
    </section>
  );
}

/**
 * A headline figure. One number, what it counts, and one line of context —
 * the context line is what stops a grid of figures reading as identical boxes.
 */
export function StatTile({
  label,
  value,
  caption,
  icon,
  tone,
}: {
  label: string;
  value: number | string;
  caption?: ReactNode;
  icon: string;
  tone?: 'alert' | 'warn' | 'good';
}) {
  return (
    <div className={`tile${tone ? ` tile--${tone}` : ''}`}>
      <div className="tile__label">
        <span className="tile__icon" aria-hidden="true">
          <Icon name={icon} size={16} />
        </span>
        {label}
      </div>
      <div className="tile__value">{value}</div>
      {caption && <div className="tile__caption">{caption}</div>}
    </div>
  );
}

/** An empty state that says what would appear here, in a sentence. */
export function EmptyNote({ children }: { children: ReactNode }) {
  return <p className="empty-note">{children}</p>;
}

/* ------------------------------------------------------------------ status */

/** Reserved colour + reserved shape, for badges and list rows. */
export function StatusMark({ status, size = 10 }: { status: DueStatus; size?: number }) {
  if (status === 'NOT_TRACKED') return null;
  return (
    <span
      className={`glyph glyph--${DUE_SHAPE[status]}`}
      style={{ color: DUE_COLOUR[status], width: size, height: size }}
      aria-hidden="true"
    />
  );
}

/** The colour key for a status chart: a swatch and the status written out. */
export function StatusLegend({ items }: { items: { status: DueStatus; value?: number }[] }) {
  return (
    <ul className="legend2">
      {items.map((i) => (
        <li key={i.status} className="legend2__item">
          <span className="swatch" style={{ background: DUE_FILL[i.status] }} aria-hidden="true" />
          {DUE_LABEL[i.status]}
          {i.value !== undefined && <b>{i.value}</b>}
        </li>
      ))}
    </ul>
  );
}

/* ---------------------------------------------------------------- controls */

/** A filter chip. */
export function Chip({
  on,
  onClick,
  children,
  count,
}: {
  on: boolean;
  onClick: () => void;
  children: ReactNode;
  count?: number;
}) {
  return (
    <button type="button" className={`chip${on ? ' chip--on' : ''}`} onClick={onClick} aria-pressed={on}>
      <span>{children}</span>
      {count !== undefined && <b>{count}</b>}
    </button>
  );
}

/** Segmented control, for choosing one of a small fixed set. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { value: T; label: string; count?: number }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={value === o.value} onClick={() => onChange(o.value)}>
          {o.label}
          {o.count !== undefined && <span className="seg__count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ charts */

/**
 * A radial gauge. The arc is the reading; the percentage repeats it for anyone
 * who cannot judge an angle, and the caption names exactly what is measured.
 */
export function RadialGauge({
  value,
  of,
  caption,
  size = 132,
  tone = 'normal',
}: {
  value: number;
  of: number;
  caption: string;
  size?: number;
  /** `normal` is the reserved green, for maintenance readings; `signal` for anything else. */
  tone?: 'normal' | 'signal';
}) {
  const pct = of === 0 ? 0 : value / of;
  const r = size / 2 - 11;
  const c = size / 2;
  // Three-quarter sweep, opening at the bottom the way a gauge face does.
  const circumference = 2 * Math.PI * r;
  const arc = circumference * 0.75;

  return (
    <div className="gauge">
      <svg
        className="gauge__svg"
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-label={`${caption}: ${value} of ${of}`}
      >
        <g transform={`rotate(135 ${c} ${c})`}>
          <circle
            className="gauge__track"
            cx={c}
            cy={c}
            r={r}
            fill="none"
            strokeWidth="10"
            strokeDasharray={`${arc} ${circumference}`}
            strokeLinecap="round"
          />
          <circle
            className={`gauge__value gauge__value--${tone}`}
            cx={c}
            cy={c}
            r={r}
            fill="none"
            strokeWidth="10"
            strokeDasharray={`${arc * pct} ${circumference}`}
          />
        </g>
        <text className="gauge__figure" x={c} y={c + 4} textAnchor="middle">
          {Math.round(pct * 100)}%
        </text>
        <text className="gauge__sub" x={c} y={c + 24} textAnchor="middle">
          {value} of {of}
        </text>
      </svg>
      <span className="gauge__caption">{caption}</span>
    </div>
  );
}

/** Reserved status keys keep their spec colour wherever the API sends them. */
const RESERVED_KEY: Record<string, string> = {
  green: 'var(--c-normal)',
  yellow: 'var(--c-approaching)',
  orange: 'var(--c-urgent)',
  red: 'var(--c-overdue)',
  critical: 'var(--c-critical)',
};
const STEP_RAMP = ['var(--sbs-step-2)', 'var(--sbs-step-3)', 'var(--sbs-step-4)', 'var(--sbs-step-5)'];

/**
 * Labelled horizontal bars — a stage pipeline, a breakdown by role.
 *
 * <p>Colour follows the key the API sent: reserved status keys keep their spec
 * colour, other keyed slices step down one sea-blue ramp so neighbours stay
 * distinct, and unkeyed rows share the signal colour because they are compared
 * by length alone.
 */
export function StageBars({
  rows,
  hideZero = true,
}: {
  rows: { key: string; label: string; value: number; colour?: string }[];
  hideZero?: boolean;
}) {
  const shown = hideZero ? rows.filter((r) => r.value > 0) : rows;
  if (shown.length === 0) return <EmptyNote>Nothing to show yet.</EmptyNote>;

  const max = Math.max(...shown.map((r) => r.value), 1);
  let step = 0;

  return (
    <div className="pipe">
      {shown.map((r) => {
        const colour =
          r.colour === undefined
            ? 'var(--sbs-signal)'
            : (RESERVED_KEY[r.colour] ?? STEP_RAMP[step++ % STEP_RAMP.length]);
        return (
          <div className="pipe__row" key={r.key}>
            <span className="pipe__label">{r.label}</span>
            <span className="pipe__track" aria-hidden="true">
              <i style={{ width: `${(r.value / max) * 100}%`, background: colour }} />
            </span>
            <b>{r.value}</b>
          </div>
        );
      })}
    </div>
  );
}

/* ---------------------------------------------------------------- activity */

/**
 * What changed, on which vessel, by whom. `wide` lays the feed out in columns
 * when it spans the page; `showReason` quotes the note a decision was made with.
 */
export function ActivityList({
  items,
  wide = false,
  showReason = false,
  limit,
}: {
  items: ActivityItem[];
  wide?: boolean;
  showReason?: boolean;
  limit?: number;
}) {
  if (items.length === 0) {
    return (
      <div style={{ padding: '0 20px 20px' }}>
        <EmptyNote>No recorded activity in scope yet.</EmptyNote>
      </div>
    );
  }

  const shown = limit ? items.slice(0, limit) : items;

  return (
    <div className={`act${wide ? ' act--wide' : ''}`}>
      {shown.map((a, i) => (
        <div className="act__item" key={`${a.serviceRequestId}-${a.occurredAt}-${i}`}>
          <span className={`act__icon act__icon--${activityTone(a)}`} aria-hidden="true">
            <Icon name={activityIcon(a.action)} size={17} />
          </span>
          <div className="act__body">
            <div className="act__what">{a.actionLabel}</div>
            <div className="act__meta">
              {a.vesselName} · <span className="act__ref">{a.requestNumber}</span>
            </div>
            {a.actorName && (
              <div className="act__meta">
                {a.actorName}
                {a.actorRole && ` · ${roleName(a.actorRole)}`}
              </div>
            )}
            {showReason && a.reason && <div className="act__reason">“{a.reason}”</div>}
          </div>
          <time className="act__time" dateTime={a.occurredAt}>
            {relativeTime(a.occurredAt)}
          </time>
        </div>
      ))}
    </div>
  );
}

function activityIcon(action: string) {
  if (action.includes('APPROV') || action.includes('ACCEPT') || action.includes('COMPLETE')) return 'check';
  if (action.includes('INVOICE')) return 'invoice';
  if (action.includes('ASSIGN')) return 'users';
  if (action.includes('LIVE') || action.includes('TROUBLESHOOT') || action.includes('CLARIF')) return 'chat';
  if (action.includes('RAISE')) return 'bell';
  return 'history';
}

/** Tints the event icon by what kind of event it was; the label says it in words. */
function activityTone(a: ActivityItem): 'stop' | 'wait' | 'done' | 'move' {
  switch (a.toStatus) {
    case 'REJECTED':
    case 'INVOICE_REJECTED':
      return 'stop';
    case 'PENDING_OPERATIONAL_APPROVAL':
    case 'INVOICE_RAISED':
    case 'INVOICE_QUERIED':
    case 'CLARIFICATION_REQUESTED':
      return 'wait';
    case 'COMPLETED':
    case 'CLOSED_NO_COST':
      return 'done';
    default:
      return 'move';
  }
}

export function roleName(role: string) {
  const lower = role.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
