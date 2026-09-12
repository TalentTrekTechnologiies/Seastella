import type { ReactNode } from 'react';
import './panel.css';

/**
 * The one container.
 *
 * <p>Border, fill and radius are spent by role rather than stamped on every
 * block: a panel is a real boundary around related content, and nesting panels
 * inside panels flattens the hierarchy rather than deepening it.
 */
export function Panel({
  title,
  subtitle,
  action,
  children,
  padded = true,
  tone = 'default',
  count,
  variant = 'default',
}: {
  title?: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  padded?: boolean;
  tone?: 'default' | 'attention';
  count?: number;
  /** "instrument" adds bezel corner ticks. Reserved for the signature panel. */
  variant?: 'default' | 'instrument';
}) {
  return (
    <section className={`panel panel--${tone}${variant === 'instrument' ? ' panel--instrument' : ''}`}>
      {title && (
        <header className="panel__head">
          <div className="panel__titles">
            <h2 className="panel__title">
              {title}
              {count !== undefined && <span className="panel__count mono">{count}</span>}
            </h2>
            {subtitle && <p className="panel__subtitle">{subtitle}</p>}
          </div>
          {action && <div className="panel__action">{action}</div>}
        </header>
      )}
      <div className={padded ? 'panel__body' : 'panel__body panel__body--flush'}>{children}</div>
    </section>
  );
}

/** Page-level heading with breadcrumb slot and actions. */
export function PageHeader({
  eyebrow,
  title,
  meta,
  actions,
}: {
  eyebrow?: ReactNode;
  title: string;
  meta?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-head">
      <div className="page-head__main">
        {eyebrow && <div className="page-head__eyebrow">{eyebrow}</div>}
        <h1 className="page-head__title">{title}</h1>
        {meta && <div className="page-head__meta">{meta}</div>}
      </div>
      {actions && <div className="page-head__actions">{actions}</div>}
    </header>
  );
}

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
  size?: 'sm' | 'md' | 'lg';
  onClick?: () => void;
  disabled?: boolean;
  title?: string;
  type?: 'button' | 'submit';
}) {
  return (
    <button
      type={type}
      className={`btn btn--${variant} btn--${size}`}
      onClick={onClick}
      disabled={disabled}
      title={title}
    >
      {children}
    </button>
  );
}

/** Label/value pairs, aligned down a column. */
export function DefinitionList({
  items,
}: {
  items: { label: string; value: ReactNode; mono?: boolean }[];
}) {
  return (
    <dl className="deflist">
      {items.map((i) => (
        <div key={i.label} className="deflist__row">
          <dt>{i.label}</dt>
          <dd className={i.mono ? 'mono' : undefined}>{i.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
